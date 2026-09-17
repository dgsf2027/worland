package top.aole.rent.modules.supplier.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.common.util.BusinessScope;
import top.aole.rent.modules.file.domain.FileObject;
import top.aole.rent.modules.file.mapper.FileObjectMapper;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.domain.SupplierInspection;
import top.aole.rent.modules.supplier.dto.InspectionDtos;
import top.aole.rent.modules.supplier.mapper.SupplierInspectionMapper;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 供应商考察服务(对齐「厂家考察汇总表」)。列表按序号排序;新增/编辑/判定/改判;Excel 导入落库。
 *
 * <p><b>关联口径</b>(考察 → 供应商为 {@code inspection.supplier_id} 单向指针):
 * <ul>
 *   <li>合格:供应商池有同名公司(名称规范化后比对)则直接关联,否则新建(阶段=入库)并关联。
 *       若该供应商此前因「考察改判不合格」被置淘汰,恢复为入库。</li>
 *   <li>不合格:解除关联;原关联供应商置「淘汰」并写明原因(不物理删除,可能已被设备/采购引用)。</li>
 *   <li>待考察:未判定,不关联。</li>
 * </ul>
 * 每次判定/改判写审计日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierInspectionService {

    public static final String BIZ_TYPE = "supplier_inspection";
    /** 产品图片(对象存储 biz_type) */
    public static final String IMAGE_BIZ_TYPE = "supplier_inspection_image";

    public static final String PENDING = "待考察";
    public static final String PASSED = "合格";
    public static final String FAILED = "不合格";
    /** 合格供应商进入供应商池的阶段 */
    private static final String PASSED_SUPPLIER_STATUS = "入库";
    private static final String RETIRED_STATUS = "淘汰";
    /** 因考察改判不合格而淘汰的供应商,retire_reason 以此开头(改回合格时据此恢复) */
    private static final String RETIRE_MARK = "考察改判不合格";

    private final SupplierInspectionMapper inspectionMapper;
    private final SupplierMapper supplierMapper;
    private final FileObjectMapper fileObjectMapper;
    private final AuditLogService auditLogService;

    // ============ 列表 / 详情 ============

    public PageResult<InspectionDtos.Item> list(String keyword, String result, int page, int size) {
        List<SupplierInspection> rows = inspectionMapper.selectList(new LambdaQueryWrapper<SupplierInspection>()
                .eq(notBlank(result), SupplierInspection::getResult, result == null ? null : result.trim())
                .and(notBlank(keyword), w -> w
                        .like(SupplierInspection::getCompanyName, keyword.trim())
                        .or().like(SupplierInspection::getContact, keyword.trim())
                        .or().like(SupplierInspection::getLegalPerson, keyword.trim())));
        rows.sort(LIST_ORDER);

        long total = rows.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(rows.size(), from + size);
        List<SupplierInspection> pageRows = from >= rows.size() ? new ArrayList<>() : rows.subList(from, to);
        return new PageResult<>(total, page, size, toItems(pageRows));
    }

    /** 导出用:全部考察记录,按列表顺序。 */
    public List<InspectionDtos.Item> listAll() {
        List<SupplierInspection> rows = inspectionMapper.selectList(new LambdaQueryWrapper<>());
        rows.sort(LIST_ORDER);
        return toItems(rows);
    }

    public InspectionDtos.Item detail(Long id) {
        return toItems(Collections.singletonList(load(id))).get(0);
    }

    /** 供应商详情页用:取该供应商当前关联的最近一条合格考察;无则 null。 */
    public InspectionDtos.Item latestPassedFor(Long supplierId) {
        SupplierInspection row = inspectionMapper.selectOne(new LambdaQueryWrapper<SupplierInspection>()
                .eq(SupplierInspection::getSupplierId, supplierId)
                .eq(SupplierInspection::getResult, PASSED)
                .orderByDesc(SupplierInspection::getId)
                .last("LIMIT 1"));
        return row == null ? null : toItems(Collections.singletonList(row)).get(0);
    }

    /** 供应商池用:有合格考察关联的供应商 id → 考察 id。 */
    public Map<Long, Long> passedInspectionIdBySupplier() {
        List<SupplierInspection> rows = inspectionMapper.selectList(new LambdaQueryWrapper<SupplierInspection>()
                .eq(SupplierInspection::getResult, PASSED)
                .isNotNull(SupplierInspection::getSupplierId)
                .orderByAsc(SupplierInspection::getId));
        Map<Long, Long> out = new HashMap<>();
        for (SupplierInspection r : rows) {
            out.put(r.getSupplierId(), r.getId()); // 升序遍历,最终保留最新一条
        }
        return out;
    }

    // ============ 新增 / 编辑 / 删除 ============

    @Transactional
    public Long create(InspectionDtos.SaveRequest req) {
        SupplierInspection row = new SupplierInspection();
        apply(row, req);
        if (row.getSortNo() == null) {
            row.setSortNo(nextSortNo());
        }
        row.setResult(PENDING);
        row.setProjectId(UserContext.getProjectId());
        inspectionMapper.insert(row);
        return row.getId();
    }

    @Transactional
    public void update(Long id, InspectionDtos.SaveRequest req) {
        SupplierInspection row = load(id);
        Integer oldSort = row.getSortNo();
        apply(row, req);
        if (row.getSortNo() == null) {
            row.setSortNo(oldSort);
        }
        writeInfo(row);
    }

    @Transactional
    public void delete(Long id) {
        SupplierInspection row = load(id);
        if (PASSED.equals(row.getResult())) {
            throw new BizException(400, "该供应商考察合格且已列入供应商上游,请先改判为不合格再删除");
        }
        inspectionMapper.deleteById(id);
    }

    /** 上传考察压缩包前的业务校验(FileStorageService 调用):记录须存在。 */
    public void assertCanAttach(Long id) {
        if (id == null) {
            throw new BizException(400, "请先保存考察记录再上传压缩包");
        }
        load(id);
    }

    // ============ 判定 / 改判 ============

    @Transactional
    public InspectionDtos.DecideResult decide(Long id, InspectionDtos.DecideRequest req) {
        SupplierInspection row = load(id);
        String result = req.getResult() == null ? "" : req.getResult().trim();
        if (!PASSED.equals(result) && !FAILED.equals(result)) {
            throw new BizException(400, "考察结果应为「合格」或「不合格」");
        }
        return changeResult(row, result, req.getConclusion(), "页面判定");
    }

    /**
     * 设置考察结果并同步供应商关联。结果与当前一致时只更新结论说明(若传入)。
     *
     * @param conclusion 为 null 表示保留原结论
     * @param source     留痕来源:页面判定 / Excel导入
     */
    private InspectionDtos.DecideResult changeResult(SupplierInspection row, String result, String conclusion, String source) {
        CurrentUser u = UserContext.require();
        String before = row.getResult();
        InspectionDtos.DecideResult out = new InspectionDtos.DecideResult();
        out.setResult(result);

        if (result.equals(before) && (!PASSED.equals(result) || row.getSupplierId() != null)) {
            if (conclusion != null) {
                row.setConclusion(conclusion);
                writeInfo(row);
            }
            out.setSupplierId(row.getSupplierId());
            out.setMessage("考察结果仍为「" + result + "」,未变化");
            return out;
        }

        if (PASSED.equals(result)) {
            LinkOutcome link = linkSupplier(row);
            row.setSupplierId(link.supplierId);
            out.setSupplierId(link.supplierId);
            out.setSupplierCreated(link.created);
            out.setMessage(link.message);
        } else {
            String retired = unlinkSupplier(row);
            row.setSupplierId(null);
            out.setMessage(retired == null ? "考察不合格,不列入供应商·上游" : "考察改判不合格,已解除关联," + retired);
        }

        row.setResult(result);
        if (conclusion != null) {
            row.setConclusion(conclusion);
        }
        row.setDecidedBy(u.getUserId());
        row.setDecidedByName(u.getUserName());
        row.setDecidedAt(LocalDateTime.now());
        writeInfo(row);

        String action = PENDING.equals(before) ? "供应商考察判定" : "供应商考察改判";
        auditLogService.record(action, BIZ_TYPE, row.getId(), AuditLogService.EXECUTED,
                source + " · " + before + " → " + result
                        + (row.getSupplierId() != null ? " · supplier#" + row.getSupplierId() : "")
                        + (notBlank(conclusion) ? " · " + conclusion : ""));
        log.info("{}: id={}, {} -> {}, supplierId={}, by={}", action, row.getId(), before, result, row.getSupplierId(), u.getUserId());
        return out;
    }

    private static class LinkOutcome {
        Long supplierId;
        Boolean created;
        String message;
    }

    /** 合格:关联同名供应商(若因考察改判被淘汰则恢复入库),无则新建入库。 */
    private LinkOutcome linkSupplier(SupplierInspection row) {
        LinkOutcome o = new LinkOutcome();
        Supplier existing = row.getSupplierId() != null ? supplierMapper.selectById(row.getSupplierId()) : null;
        if (existing == null) {
            existing = findSupplierByName(row.getCompanyName());
        }
        if (existing != null) {
            o.supplierId = existing.getId();
            o.created = false;
            if (RETIRED_STATUS.equals(existing.getStatus()) && existing.getRetireReason() != null
                    && existing.getRetireReason().startsWith(RETIRE_MARK)) {
                supplierMapper.update(null, new LambdaUpdateWrapper<Supplier>()
                        .eq(Supplier::getId, existing.getId())
                        .set(Supplier::getStatus, PASSED_SUPPLIER_STATUS)
                        .set(Supplier::getRetireReason, null)
                        .set(Supplier::getRetiredBy, null)
                        .set(Supplier::getRetiredAt, null));
                o.message = "考察合格,已恢复并关联供应商「" + existing.getName() + "」(阶段=" + PASSED_SUPPLIER_STATUS + ")";
            } else {
                o.message = "考察合格,已关联供应商上游已有的「" + existing.getName() + "」(阶段仍为「" + existing.getStatus() + "」)";
            }
            return o;
        }
        Supplier s = new Supplier();
        s.setName(row.getCompanyName());
        s.setContact(row.getContact());
        s.setPhone(row.getPhone());
        List<String> scope = BusinessScope.split(row.getBusinessScope());
        s.setMainCategory(scope.isEmpty() ? null : scope.get(0));
        s.setStatus(PASSED_SUPPLIER_STATUS);
        s.setProjectId(row.getProjectId());
        s.setRemark(scopeRemark(row));
        supplierMapper.insert(s);
        o.supplierId = s.getId();
        o.created = true;
        o.message = "考察合格,已列入供应商·上游(阶段=" + PASSED_SUPPLIER_STATUS + ")";
        return o;
    }

    /** 不合格:解除关联并把原关联供应商置淘汰。返回提示文案;原本未关联返回 null。 */
    private String unlinkSupplier(SupplierInspection row) {
        if (row.getSupplierId() == null) {
            return null;
        }
        Supplier s = supplierMapper.selectById(row.getSupplierId());
        if (s == null) {
            return null;
        }
        if (RETIRED_STATUS.equals(s.getStatus())) {
            return "供应商「" + s.getName() + "」原已是淘汰状态";
        }
        supplierMapper.update(null, new LambdaUpdateWrapper<Supplier>()
                .eq(Supplier::getId, s.getId())
                .set(Supplier::getStatus, RETIRED_STATUS)
                .set(Supplier::getRetireReason, RETIRE_MARK + "(考察#" + row.getId() + ")")
                .set(Supplier::getRetiredBy, UserContext.getUserId())
                .set(Supplier::getRetiredAt, LocalDateTime.now()));
        auditLogService.record("供应商淘汰", "supplier", s.getId(), AuditLogService.EXECUTED,
                RETIRE_MARK + "(考察#" + row.getId() + ")");
        return "供应商「" + s.getName() + "」已置为淘汰";
    }

    // ============ Excel 导入落库 ============

    /** 解析后的一行汇总表数据(由 SupplierInspectionExcelService 产出)。 */
    @Data
    public static class SheetRow {
        private int rowNum;
        private Integer sortNo;
        private String companyName;
        private String legalPerson;
        private String registeredCapitalWan;
        private LocalDate establishedDate;
        private List<String> businessScope;
        private String address;
        private String contact;
        private String phone;
        private String companyProfile;
        private String performanceWan;
        private String socialStaff;
        private String productImageNote;
        private String impression;
        /** 表格里实际存在的可选列(公司业务范围/业绩/社保员工/产品图片/考察观后感);不存在的列导入时保留原值 */
        private Set<String> columns = new HashSet<>();
        /** 产品图片列里提取到的图片 */
        private List<SheetImage> images = new ArrayList<>();
        /** 合格/不合格;null=表内未填,保持原结果(新增则为待考察) */
        private String result;
        /** 解析阶段产生的提示 */
        private List<String> warnings = new ArrayList<>();
        /** 【回填】导入落库后的考察 id(跳过的行为 null) */
        private Long inspectionId;
    }

    /** 表格单元格里的一张图片 */
    @Data
    public static class SheetImage {
        private byte[] data;
        /** 扩展名(png/jpeg/…) */
        private String ext;

        public SheetImage() {
        }

        public SheetImage(byte[] data, String ext) {
            this.data = data;
            this.ext = ext;
        }
    }

    /** 可选列名(SheetRow.columns 的取值) */
    public static final String COL_PROFILE = "公司业务范围";
    public static final String COL_PERFORMANCE = "业绩";
    public static final String COL_STAFF = "社保员工";
    public static final String COL_IMAGE = "产品图片";
    public static final String COL_IMPRESSION = "考察观后感";

    /**
     * 按公司名称(规范化后)匹配:已存在则更新公司信息,不存在则新增;「是否合格」有值时按表格设置/改判结果。
     * 表格里没有的考察记录不做删除。整批在一个事务里,单行业务校验失败只跳过该行并提示。
     */
    @Transactional
    public InspectionDtos.ImportResult importRows(List<SheetRow> rows) {
        InspectionDtos.ImportResult res = new InspectionDtos.ImportResult();
        Map<String, SupplierInspection> byName = new HashMap<>();
        for (SupplierInspection r : inspectionMapper.selectList(new LambdaQueryWrapper<>())) {
            byName.putIfAbsent(normalizeName(r.getCompanyName()), r);
        }
        Map<String, Integer> seenInFile = new HashMap<>();
        int nextSort = nextSortNo();

        for (SheetRow sr : rows) {
            res.setTotal(res.getTotal() + 1);
            for (String w : sr.getWarnings()) {
                res.getMessages().add(new InspectionDtos.RowMessage(sr.getRowNum(), sr.getCompanyName(), "warn", w));
            }
            if (!notBlank(sr.getCompanyName())) {
                res.setSkipped(res.getSkipped() + 1);
                res.getMessages().add(new InspectionDtos.RowMessage(sr.getRowNum(), null, "warn", "公司名称为空,已跳过"));
                continue;
            }
            String key = normalizeName(sr.getCompanyName());
            if (seenInFile.containsKey(key)) {
                res.setSkipped(res.getSkipped() + 1);
                res.getMessages().add(new InspectionDtos.RowMessage(sr.getRowNum(), sr.getCompanyName(), "warn",
                        "与第 " + seenInFile.get(key) + " 行公司名称重复,已跳过"));
                continue;
            }
            seenInFile.put(key, sr.getRowNum());

            try {
                SupplierInspection row = byName.get(key);
                boolean isNew = row == null;
                if (isNew) {
                    row = new SupplierInspection();
                    row.setResult(PENDING);
                    row.setProjectId(UserContext.getProjectId());
                }
                applySheet(row, sr);
                if (row.getSortNo() == null) {
                    row.setSortNo(nextSort++);
                } else if (row.getSortNo() >= nextSort) {
                    nextSort = row.getSortNo() + 1;
                }
                if (isNew) {
                    inspectionMapper.insert(row);
                    byName.put(key, row);
                    res.setCreated(res.getCreated() + 1);
                } else {
                    writeInfo(row);
                    res.setUpdated(res.getUpdated() + 1);
                }
                sr.setInspectionId(row.getId());

                if (sr.getResult() != null && !sr.getResult().equals(row.getResult())) {
                    String before = row.getResult();
                    InspectionDtos.DecideResult d = changeResult(row, sr.getResult(), null, "Excel导入");
                    if (PASSED.equals(sr.getResult())) {
                        res.setPassed(res.getPassed() + 1);
                    } else {
                        res.setFailed(res.getFailed() + 1);
                    }
                    res.getMessages().add(new InspectionDtos.RowMessage(sr.getRowNum(), row.getCompanyName(), "info",
                            before + " → " + sr.getResult() + ":" + d.getMessage()));
                } else if (PASSED.equals(row.getResult()) && row.getSupplierId() == null) {
                    // 合格但关联丢失(如供应商被删),按表格补关联
                    InspectionDtos.DecideResult d = changeResult(row, PASSED, null, "Excel导入");
                    res.getMessages().add(new InspectionDtos.RowMessage(sr.getRowNum(), row.getCompanyName(), "info", d.getMessage()));
                }
            } catch (BizException e) {
                res.setSkipped(res.getSkipped() + 1);
                res.getMessages().add(new InspectionDtos.RowMessage(sr.getRowNum(), sr.getCompanyName(), "warn",
                        "未导入:" + e.getMessage()));
            }
        }
        auditLogService.record("供应商考察导入", BIZ_TYPE, null, AuditLogService.EXECUTED,
                "共" + res.getTotal() + "行 新增" + res.getCreated() + " 更新" + res.getUpdated()
                        + " 合格" + res.getPassed() + " 不合格" + res.getFailed() + " 跳过" + res.getSkipped());
        return res;
    }

    // ============ 内部工具 ============

    /** 序号升序,空序号排最后;同序号按 id。 */
    private static final Comparator<SupplierInspection> LIST_ORDER = Comparator
            .comparing(SupplierInspection::getSortNo, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(SupplierInspection::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private void apply(SupplierInspection row, InspectionDtos.SaveRequest req) {
        row.setSortNo(req.getSortNo());
        row.setCompanyName(req.getCompanyName().trim());
        row.setLegalPerson(trimToNull(req.getLegalPerson()));
        row.setRegisteredCapitalWan(trimToNull(req.getRegisteredCapitalWan()));
        row.setEstablishedDate(req.getEstablishedDate());
        row.setBusinessScope(BusinessScope.join(req.getBusinessScope()));
        row.setAddress(trimToNull(req.getAddress()));
        row.setContact(trimToNull(req.getContact()));
        row.setPhone(trimToNull(req.getPhone()));
        row.setCompanyProfile(trimToNull(req.getCompanyProfile()));
        row.setPerformanceWan(trimToNull(req.getPerformanceWan()));
        row.setSocialStaff(trimToNull(req.getSocialStaff()));
        row.setProductImageNote(trimToNull(req.getProductImageNote()));
        row.setImpression(trimToNull(req.getImpression()));
        row.setRemark(req.getRemark());
    }

    private void applySheet(SupplierInspection row, SheetRow sr) {
        if (sr.getSortNo() != null) {
            row.setSortNo(sr.getSortNo());
        }
        row.setCompanyName(sr.getCompanyName().trim());
        row.setLegalPerson(trimToNull(sr.getLegalPerson()));
        row.setRegisteredCapitalWan(trimToNull(sr.getRegisteredCapitalWan()));
        row.setEstablishedDate(sr.getEstablishedDate());
        row.setBusinessScope(BusinessScope.join(sr.getBusinessScope()));
        row.setAddress(trimToNull(sr.getAddress()));
        row.setContact(trimToNull(sr.getContact()));
        row.setPhone(trimToNull(sr.getPhone()));
        Set<String> cols = sr.getColumns();
        if (cols.contains(COL_PROFILE)) {
            row.setCompanyProfile(trimToNull(sr.getCompanyProfile()));
        }
        if (cols.contains(COL_PERFORMANCE)) {
            row.setPerformanceWan(trimToNull(sr.getPerformanceWan()));
        }
        if (cols.contains(COL_STAFF)) {
            row.setSocialStaff(trimToNull(sr.getSocialStaff()));
        }
        if (cols.contains(COL_IMAGE)) {
            row.setProductImageNote(trimToNull(sr.getProductImageNote()));
        }
        if (cols.contains(COL_IMPRESSION)) {
            row.setImpression(trimToNull(sr.getImpression()));
        }
    }

    /** 写入公司信息与结果字段(显式 set,允许清空;updateById 会跳过 null)。 */
    private void writeInfo(SupplierInspection row) {
        inspectionMapper.update(null, new LambdaUpdateWrapper<SupplierInspection>()
                .eq(SupplierInspection::getId, row.getId())
                .set(SupplierInspection::getSortNo, row.getSortNo())
                .set(SupplierInspection::getCompanyName, row.getCompanyName())
                .set(SupplierInspection::getLegalPerson, row.getLegalPerson())
                .set(SupplierInspection::getRegisteredCapitalWan, row.getRegisteredCapitalWan())
                .set(SupplierInspection::getEstablishedDate, row.getEstablishedDate())
                .set(SupplierInspection::getBusinessScope, row.getBusinessScope())
                .set(SupplierInspection::getAddress, row.getAddress())
                .set(SupplierInspection::getContact, row.getContact())
                .set(SupplierInspection::getPhone, row.getPhone())
                .set(SupplierInspection::getCompanyProfile, row.getCompanyProfile())
                .set(SupplierInspection::getPerformanceWan, row.getPerformanceWan())
                .set(SupplierInspection::getSocialStaff, row.getSocialStaff())
                .set(SupplierInspection::getProductImageNote, row.getProductImageNote())
                .set(SupplierInspection::getImpression, row.getImpression())
                .set(SupplierInspection::getRemark, row.getRemark())
                .set(SupplierInspection::getResult, row.getResult())
                .set(SupplierInspection::getConclusion, row.getConclusion())
                .set(SupplierInspection::getDecidedBy, row.getDecidedBy())
                .set(SupplierInspection::getDecidedByName, row.getDecidedByName())
                .set(SupplierInspection::getDecidedAt, row.getDecidedAt())
                .set(SupplierInspection::getSupplierId, row.getSupplierId()));
    }

    private int nextSortNo() {
        return inspectionMapper.selectList(new LambdaQueryWrapper<SupplierInspection>()
                        .isNotNull(SupplierInspection::getSortNo)).stream()
                .map(SupplierInspection::getSortNo).filter(Objects::nonNull)
                .max(Integer::compare).orElse(0) + 1;
    }

    private Supplier findSupplierByName(String companyName) {
        String key = normalizeName(companyName);
        return supplierMapper.selectList(new LambdaQueryWrapper<Supplier>().orderByAsc(Supplier::getId)).stream()
                .filter(s -> key.equals(normalizeName(s.getName())))
                .findFirst().orElse(null);
    }

    /** 名称规范化:去首尾及内部空白,全角括号转半角。 */
    static String normalizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.replace('（', '(').replace('）', ')').replaceAll("\\s+", "").trim();
    }

    private List<InspectionDtos.Item> toItems(List<SupplierInspection> rows) {
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> ids = rows.stream().map(SupplierInspection::getId).collect(Collectors.toSet());
        Map<Long, List<FileObject>> archives = new HashMap<>();
        Map<Long, Integer> imageCounts = new HashMap<>();
        for (FileObject fo : fileObjectMapper.selectList(new LambdaQueryWrapper<FileObject>()
                .in(FileObject::getBizType, Arrays.asList(BIZ_TYPE, IMAGE_BIZ_TYPE))
                .in(FileObject::getBizId, ids)
                .orderByAsc(FileObject::getId))) {
            if (IMAGE_BIZ_TYPE.equals(fo.getBizType())) {
                imageCounts.merge(fo.getBizId(), 1, Integer::sum);
            } else {
                archives.computeIfAbsent(fo.getBizId(), k -> new ArrayList<>()).add(fo);
            }
        }

        Set<Long> supplierIds = rows.stream().map(SupplierInspection::getSupplierId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, Supplier> suppliers = supplierIds.isEmpty() ? new HashMap<>()
                : supplierMapper.selectBatchIds(supplierIds).stream()
                .collect(Collectors.toMap(Supplier::getId, s -> s));

        List<InspectionDtos.Item> out = new ArrayList<>();
        for (SupplierInspection r : rows) {
            InspectionDtos.Item it = new InspectionDtos.Item();
            it.setId(r.getId());
            it.setSortNo(r.getSortNo());
            it.setCompanyName(r.getCompanyName());
            it.setLegalPerson(r.getLegalPerson());
            it.setRegisteredCapitalWan(r.getRegisteredCapitalWan());
            it.setEstablishedDate(r.getEstablishedDate());
            it.setBusinessScope(BusinessScope.split(r.getBusinessScope()));
            it.setAddress(r.getAddress());
            it.setContact(r.getContact());
            it.setPhone(r.getPhone());
            it.setCompanyProfile(r.getCompanyProfile());
            it.setPerformanceWan(r.getPerformanceWan());
            it.setSocialStaff(r.getSocialStaff());
            it.setProductImageNote(r.getProductImageNote());
            it.setImpression(r.getImpression());
            it.setImageCount(imageCounts.getOrDefault(r.getId(), 0));
            it.setResult(r.getResult());
            it.setConclusion(r.getConclusion());
            it.setDecidedByName(r.getDecidedByName());
            it.setDecidedAt(r.getDecidedAt());
            it.setSupplierId(r.getSupplierId());
            Supplier s = r.getSupplierId() == null ? null : suppliers.get(r.getSupplierId());
            it.setSupplierName(s == null ? null : s.getName());
            it.setSupplierStatus(s == null ? null : s.getStatus());
            List<FileObject> files = archives.getOrDefault(r.getId(), Collections.emptyList());
            it.setArchiveCount(files.size());
            it.setArchiveNames(files.stream().map(FileObject::getFileName).collect(Collectors.toList()));
            it.setRemark(r.getRemark());
            it.setCreateTime(r.getCreateTime());
            out.add(it);
        }
        return out;
    }

    private SupplierInspection load(Long id) {
        SupplierInspection row = id == null ? null : inspectionMapper.selectById(id);
        if (row == null || Integer.valueOf(1).equals(row.getIsDeleted())) {
            throw new BizException(404, "考察记录不存在: id=" + id);
        }
        return row;
    }

    private String scopeRemark(SupplierInspection row) {
        String remark = "考察合格建档(考察#" + row.getId() + ")";
        if (notBlank(row.getBusinessScope())) {
            remark += " · 业务范围:" + row.getBusinessScope().replace(",", "/");
        }
        return remark;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String trimToNull(String s) {
        return notBlank(s) ? s.trim() : null;
    }
}
