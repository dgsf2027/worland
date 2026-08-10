package top.aole.rent.modules.imports.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.dto.AssetSaveRequest;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.dto.CustomerSaveRequest;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.customer.service.CustomerService;
import top.aole.rent.modules.imports.domain.ImportJob;
import top.aole.rent.modules.imports.dto.ImportDtos;
import top.aole.rent.modules.imports.mapper.ImportJobMapper;
import top.aole.rent.modules.imports.parser.ExcelParser;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.dto.SupplierSaveRequest;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;
import top.aole.rent.modules.supplier.service.SupplierService;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入中心服务(M5-07 · DESIGN §二 · 评审 P1-17)。Excel 映射/预览/去重 →
 * <b>走与正常单据同一校验 + 事件流通道</b>:确认入库逐行调 {@link SupplierService#create}/
 * {@link CustomerService#create}/{@link AssetService#create}(它们各自做校验、写事件),<b>禁直写派生/隔离字段</b>。
 *
 * <ul>
 *   <li>公式注入前缀转义:{@link ExcelParser} 在解析层完成(=+-@ → 单引号中和 + 计数)</li>
 *   <li>去重:按唯一键(供应商/客户 name、设备 serialNo)与现有库比对 → dup 跳过</li>
 *   <li>owner/project 校验:业务(行级隔离)只能导入自己名下客户;行项目≠导入目标项目 → 越权拒</li>
 *   <li>限流:文件大小 / 条数上限走 rule_config(禁硬编码)</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    private final ExcelParser excelParser;
    private final ImportJobMapper jobMapper;
    private final SupplierService supplierService;
    private final CustomerService customerService;
    private final AssetService assetService;
    private final SupplierMapper supplierMapper;
    private final CustomerMapper customerMapper;
    private final AssetMapper assetMapper;
    private final top.aole.rent.modules.rule.service.RuleConfigService ruleConfigService;

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Set<String> TARGETS = new HashSet<>(java.util.Arrays.asList("supplier", "customer", "asset"));

    // ============================== 模板 ==============================

    public ImportDtos.TemplateResp template(String targetType) {
        checkTarget(targetType);
        ImportDtos.TemplateResp t = new ImportDtos.TemplateResp();
        t.setTargetType(targetType);
        switch (targetType) {
            case "supplier":
                t.setLabel("供应商");
                t.setDedupKeyLabel("供应商名称");
                t.getFields().add(new ImportDtos.Field("name", "供应商名称", true));
                t.getFields().add(new ImportDtos.Field("contact", "联系人", false));
                t.getFields().add(new ImportDtos.Field("phone", "电话", false));
                t.getFields().add(new ImportDtos.Field("mainCategory", "主营品类", false));
                t.getFields().add(new ImportDtos.Field("status", "关系阶段", false));
                t.getFields().add(new ImportDtos.Field("remark", "备注", false));
                break;
            case "customer":
                t.setLabel("客户");
                t.setDedupKeyLabel("客户名称");
                t.getFields().add(new ImportDtos.Field("name", "客户名称", true));
                t.getFields().add(new ImportDtos.Field("contact", "联系人", false));
                t.getFields().add(new ImportDtos.Field("phone", "电话", false));
                t.getFields().add(new ImportDtos.Field("industry", "行业", false));
                t.getFields().add(new ImportDtos.Field("valueTier", "价值分层", false));
                t.getFields().add(new ImportDtos.Field("ownerUser", "归属人主键", false));
                t.getFields().add(new ImportDtos.Field("projectId", "项目ID", false));
                t.getFields().add(new ImportDtos.Field("remark", "备注", false));
                break;
            default: // asset
                t.setLabel("设备");
                t.setDedupKeyLabel("序列号");
                t.getFields().add(new ImportDtos.Field("serialNo", "序列号", true));
                t.getFields().add(new ImportDtos.Field("category", "品类", true));
                t.getFields().add(new ImportDtos.Field("model", "型号", false));
                t.getFields().add(new ImportDtos.Field("marketPrice", "市场价", false));
                t.getFields().add(new ImportDtos.Field("purchasePrice", "采购价", false));
                t.getFields().add(new ImportDtos.Field("supplierId", "供应商主键", false));
                t.getFields().add(new ImportDtos.Field("projectId", "项目ID", false));
                t.getFields().add(new ImportDtos.Field("remark", "备注", false));
        }
        return t;
    }

    // ============================== 预览 ==============================

    /**
     * 上传 Excel + 列映射 → 逐行校验/去重预览,落 import_job(待确认)。
     *
     * @param mapping   excel表头 → 目标字段;null/空则按表头名自动匹配字段 key/label
     * @param projectId 本次导入目标项目(默认取操作人上下文项目)
     */
    @Transactional
    public ImportDtos.PreviewResp preview(String targetType, MultipartFile file,
                                          Map<String, String> mapping, Long projectId) {
        checkTarget(targetType);
        CurrentUser u = UserContext.require();
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请上传 .xlsx 文件");
        }
        // 限流:大小(rule import_limit/max_file_bytes)
        long maxBytes = ruleConfigService.getValue("import_limit", "max_file_bytes", LocalDate.now()).longValue();
        if (file.getSize() > maxBytes) {
            throw new BizException(400, "文件超出大小上限 " + (maxBytes / 1024 / 1024) + "MB");
        }
        long maxRows = ruleConfigService.getValue("import_limit", "max_rows", LocalDate.now()).longValue();

        ExcelParser.ParsedSheet sheet;
        try {
            sheet = excelParser.parse(file.getInputStream());
        } catch (IOException e) {
            throw new BizException("读取文件失败:" + e.getMessage());
        }
        if (sheet.getRows().size() > maxRows) {
            throw new BizException(400, "导入条数 " + sheet.getRows().size() + " 超出上限 " + maxRows + " 行");
        }

        Long targetProject = projectId != null ? projectId : u.getProjectId();

        ImportDtos.PreviewResp resp = new ImportDtos.PreviewResp();
        resp.setTargetType(targetType);
        resp.setFileName(file.getOriginalFilename());
        resp.setFileSize(file.getSize());
        resp.setTotalRows(sheet.getRows().size());
        resp.setEscapedCells(sheet.getEscapedCells());
        resp.getHeaders().addAll(sheet.getHeaders());

        Map<String, String> effMapping = resolveMapping(targetType, sheet.getHeaders(), mapping);
        Set<String> seenKeys = new HashSet<>(); // 文件内重复也算 dup

        for (ExcelParser.ParsedRow raw : sheet.getRows()) {
            ImportDtos.RowResult rr = new ImportDtos.RowResult();
            rr.setRowNo(raw.getRowNo());
            Map<String, String> data = new LinkedHashMap<>();
            boolean escaped = false;
            for (Map.Entry<String, String> m : effMapping.entrySet()) {
                String val = raw.getCells().get(m.getKey());
                if (val != null && val.startsWith("'")) {
                    escaped = true; // 解析层转义留下的单引号前缀
                }
                data.put(m.getValue(), val);
            }
            rr.setData(data);
            rr.setEscaped(escaped);
            validateRow(targetType, data, seenKeys, targetProject, u, rr);
            resp.getRows().add(rr);
            switch (rr.getStatus()) {
                case "ok": resp.setOkRows(resp.getOkRows() + 1); break;
                case "dup": resp.setDupRows(resp.getDupRows() + 1); break;
                default: resp.setErrRows(resp.getErrRows() + 1);
            }
        }

        ImportJob job = new ImportJob();
        job.setNo(genNo());
        job.setTargetType(targetType);
        job.setFileName(file.getOriginalFilename());
        job.setFileSize(file.getSize());
        job.setTotalRows(resp.getTotalRows());
        job.setMappingJson(JSONUtil.toJsonStr(effMapping));
        job.setOkRows(resp.getOkRows());
        job.setDupRows(resp.getDupRows());
        job.setErrRows(resp.getErrRows());
        job.setEscapedCells(resp.getEscapedCells());
        job.setImportedRows(0);
        job.setStatus("待确认");
        job.setPreviewJson(JSONUtil.toJsonStr(resp.getRows()));
        job.setProjectId(targetProject);
        job.setOperatorId(u.getUserId());
        job.setOperatorName(u.getUserName());
        job.setOperatorRole(u.getRole());
        jobMapper.insert(job);

        resp.setJobId(job.getId());
        resp.setNo(job.getNo());
        resp.setStatus(job.getStatus());
        return resp;
    }

    // ============================== 提交 ==============================

    /** 确认入库:仅 ok 行逐条调 service.create(同一校验+事件流);dup 跳过;service 抛错记 failed。 */
    @Transactional
    public ImportDtos.CommitResp commit(Long jobId) {
        ImportJob job = jobMapper.selectById(jobId);
        if (job == null || Integer.valueOf(1).equals(job.getIsDeleted())) {
            throw new BizException(404, "导入作业不存在: " + jobId);
        }
        if (!"待确认".equals(job.getStatus())) {
            throw new BizException(400, "该作业状态为「" + job.getStatus() + "」,不可重复入库(幂等)");
        }
        List<ImportDtos.RowResult> rows = JSONUtil.toList(job.getPreviewJson(), ImportDtos.RowResult.class);
        ImportDtos.CommitResp resp = new ImportDtos.CommitResp();
        resp.setJobId(jobId);

        for (ImportDtos.RowResult rr : rows) {
            if ("dup".equals(rr.getStatus())) {
                resp.setSkippedDup(resp.getSkippedDup() + 1);
                continue;
            }
            if (!"ok".equals(rr.getStatus())) {
                continue; // err 行不入库
            }
            try {
                createViaService(job.getTargetType(), rr.getData(), job.getProjectId());
                resp.setImported(resp.getImported() + 1);
            } catch (Exception e) {
                resp.setFailed(resp.getFailed() + 1);
                resp.getFailures().add("第" + rr.getRowNo() + "行:" + e.getMessage());
            }
        }
        job.setImportedRows(resp.getImported());
        job.setStatus("已导入");
        job.setCommittedAt(LocalDateTime.now());
        if (!resp.getFailures().isEmpty()) {
            job.setRemark("入库失败 " + resp.getFailed() + " 行:" + String.join(";", resp.getFailures()));
        }
        jobMapper.updateById(job);
        return resp;
    }

    // ============================== 列表/详情 ==============================

    public List<ImportDtos.JobRow> list(String targetType, String status) {
        LambdaQueryWrapper<ImportJob> qw = new LambdaQueryWrapper<ImportJob>()
                .eq(ImportJob::getIsDeleted, 0)
                .eq(targetType != null && !targetType.isEmpty(), ImportJob::getTargetType, targetType)
                .eq(status != null && !status.isEmpty(), ImportJob::getStatus, status)
                .orderByDesc(ImportJob::getId);
        List<ImportDtos.JobRow> rows = new ArrayList<>();
        for (ImportJob j : jobMapper.selectList(qw)) {
            ImportDtos.JobRow r = new ImportDtos.JobRow();
            r.setId(j.getId());
            r.setNo(j.getNo());
            r.setTargetType(j.getTargetType());
            r.setFileName(j.getFileName());
            r.setTotalRows(j.getTotalRows());
            r.setOkRows(j.getOkRows());
            r.setDupRows(j.getDupRows());
            r.setErrRows(j.getErrRows());
            r.setEscapedCells(j.getEscapedCells());
            r.setImportedRows(j.getImportedRows());
            r.setStatus(j.getStatus());
            r.setOperatorName(j.getOperatorName());
            r.setCreateTime(j.getCreateTime());
            r.setCommittedAt(j.getCommittedAt());
            rows.add(r);
        }
        return rows;
    }

    // ============================== 校验/入库(走同一 service) ==============================

    private void validateRow(String targetType, Map<String, String> data, Set<String> seenKeys,
                             Long targetProject, CurrentUser u, ImportDtos.RowResult rr) {
        // project 越权校验(P1-17):行项目 ≠ 导入目标项目 → 越权拒
        String rowProject = data.get("projectId");
        if (rowProject != null && !rowProject.isEmpty()) {
            Long rp = parseLongOrNull(rowProject);
            if (rp == null || !rp.equals(targetProject)) {
                rr.setStatus("err");
                rr.setMessage("越权:行项目(" + rowProject + ")与导入目标项目("
                        + (targetProject == null ? "无" : targetProject) + ")不一致,跨项目导入被拒");
                return;
            }
        }
        switch (targetType) {
            case "supplier": {
                String name = data.get("name");
                if (isBlank(name)) {
                    rr.setStatus("err");
                    rr.setMessage("供应商名称必填");
                    return;
                }
                if (!seenKeys.add("s:" + name) || supplierMapper.selectCount(new LambdaQueryWrapper<Supplier>()
                        .eq(Supplier::getName, name.trim()).eq(Supplier::getIsDeleted, 0)) > 0) {
                    rr.setStatus("dup");
                    rr.setMessage("供应商已存在(按名称去重)");
                    return;
                }
                break;
            }
            case "customer": {
                String name = data.get("name");
                if (isBlank(name)) {
                    rr.setStatus("err");
                    rr.setMessage("客户名称必填");
                    return;
                }
                // owner 越权校验(P1-17):业务(行级隔离)只能导入自己名下客户
                if (DataScope.isOwnerScoped(u.getRole())) {
                    Long owner = parseLongOrNull(data.get("ownerUser"));
                    if (owner != null && !owner.equals(u.getUserId())) {
                        rr.setStatus("err");
                        rr.setMessage("越权:业务(行级隔离)只能导入自己名下客户(归属人须为本人)");
                        return;
                    }
                }
                if (!seenKeys.add("c:" + name) || customerMapper.selectCount(new LambdaQueryWrapper<Customer>()
                        .eq(Customer::getName, name.trim()).eq(Customer::getIsDeleted, 0)) > 0) {
                    rr.setStatus("dup");
                    rr.setMessage("客户已存在(按名称去重)");
                    return;
                }
                break;
            }
            default: { // asset
                String serial = data.get("serialNo");
                if (isBlank(serial)) {
                    rr.setStatus("err");
                    rr.setMessage("序列号必填");
                    return;
                }
                if (isBlank(data.get("category"))) {
                    rr.setStatus("err");
                    rr.setMessage("品类必填");
                    return;
                }
                if (!seenKeys.add("a:" + serial) || assetMapper.selectCount(new LambdaQueryWrapper<Asset>()
                        .eq(Asset::getSerialNo, serial.trim()).eq(Asset::getIsDeleted, 0)) > 0) {
                    rr.setStatus("dup");
                    rr.setMessage("序列号已存在(按序列号去重)");
                    return;
                }
            }
        }
        rr.setStatus("ok");
        rr.setMessage(rr.isEscaped() ? "校验通过(含公式转义单元格)" : "校验通过");
    }

    /** 走正常单据同一校验 + 事件流通道(禁直写派生/隔离字段)。 */
    private void createViaService(String targetType, Map<String, String> data, Long projectId) {
        switch (targetType) {
            case "supplier": {
                SupplierSaveRequest req = new SupplierSaveRequest();
                req.setName(data.get("name"));
                req.setContact(data.get("contact"));
                req.setPhone(data.get("phone"));
                req.setMainCategory(data.get("mainCategory"));
                req.setStatus(data.get("status"));
                req.setRemark(data.get("remark"));
                supplierService.create(req);
                break;
            }
            case "customer": {
                CustomerSaveRequest req = new CustomerSaveRequest();
                req.setName(data.get("name"));
                req.setContact(data.get("contact"));
                req.setPhone(data.get("phone"));
                req.setIndustry(data.get("industry"));
                req.setValueTier(data.get("valueTier"));
                req.setOwnerUser(parseLongOrNull(data.get("ownerUser")));
                customerService.create(req);
                break;
            }
            default: { // asset
                AssetSaveRequest req = new AssetSaveRequest();
                req.setSerialNo(data.get("serialNo"));
                req.setCategory(data.get("category"));
                req.setModel(data.get("model"));
                req.setMarketPrice(parseDecimalOrNull(data.get("marketPrice")));
                req.setPurchasePrice(parseDecimalOrNull(data.get("purchasePrice")));
                req.setSupplierId(parseLongOrNull(data.get("supplierId")));
                req.setRemark(data.get("remark"));
                assetService.create(req);
            }
        }
    }

    // ============================== 工具 ==============================

    /** 列映射:显式优先;缺省按表头名匹配字段 key/label。 */
    private Map<String, String> resolveMapping(String targetType, List<String> headers, Map<String, String> explicit) {
        Map<String, String> result = new LinkedHashMap<>();
        List<ImportDtos.Field> fields = template(targetType).getFields();
        for (String h : headers) {
            String hClean = h.startsWith("'") ? h.substring(1) : h; // 去表头转义前缀再匹配
            if (explicit != null && explicit.containsKey(h)) {
                result.put(h, explicit.get(h));
                continue;
            }
            for (ImportDtos.Field f : fields) {
                if (hClean.equals(f.getKey()) || hClean.equals(f.getLabel())) {
                    result.put(h, f.getKey());
                    break;
                }
            }
        }
        if (result.isEmpty()) {
            throw new BizException(400, "列映射为空:表头与目标字段无法匹配,请显式指定 mapping");
        }
        return result;
    }

    private void checkTarget(String t) {
        if (t == null || !TARGETS.contains(t)) {
            throw new BizException(400, "不支持的导入目标(supplier/customer/asset): " + t);
        }
    }

    private String genNo() {
        String day = LocalDate.now().format(NO_FMT);
        Long cnt = jobMapper.selectCount(new LambdaQueryWrapper<ImportJob>()
                .likeRight(ImportJob::getNo, "IMP-" + day));
        return String.format("IMP-%s-%03d", day, cnt + 1);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static Long parseLongOrNull(String s) {
        if (isBlank(s)) {
            return null;
        }
        try {
            return new BigDecimal(s.trim()).longValueExact();
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal parseDecimalOrNull(String s) {
        if (isBlank(s)) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
