package top.aole.rent.modules.supplier.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.file.domain.FileObject;
import top.aole.rent.modules.file.mapper.FileObjectMapper;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.domain.SupplierInspection;
import top.aole.rent.modules.supplier.dto.InspectionDtos;
import top.aole.rent.modules.supplier.mapper.SupplierInspectionMapper;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 供应商考察服务。新增/编辑/判定;合格 → 供应商池建档(阶段=入库)并回填关联,不合格 → 不建档。
 *
 * <p><b>关联口径</b>:考察 → 供应商为 {@code inspection.supplier_id} 单向指针,只在「判定合格」时写一次。
 * 供应商池已有同名公司时直接关联、不重复建档,也不改其阶段(淘汰的供应商需人工复核)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierInspectionService {

    public static final String BIZ_TYPE = "supplier_inspection";

    static final String PENDING = "待考察";
    static final String PASSED = "合格";
    static final String FAILED = "不合格";
    /** 合格供应商进入供应商池的初始阶段 */
    private static final String PASSED_SUPPLIER_STATUS = "入库";

    private static final List<String> VALID_SCOPE = Arrays.asList("货架", "阁楼", "播种墙");

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
                        .or().like(SupplierInspection::getLegalPerson, keyword.trim()))
                .orderByDesc(SupplierInspection::getId));

        long total = rows.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(rows.size(), from + size);
        List<SupplierInspection> pageRows = from >= rows.size() ? new ArrayList<>() : rows.subList(from, to);
        return new PageResult<>(total, page, size, toItems(pageRows));
    }

    public InspectionDtos.Item detail(Long id) {
        return toItems(Collections.singletonList(load(id))).get(0);
    }

    /** 供应商详情页用:取该供应商最近一条合格考察;无则 null。 */
    public InspectionDtos.Item latestPassedFor(Long supplierId) {
        SupplierInspection row = inspectionMapper.selectOne(new LambdaQueryWrapper<SupplierInspection>()
                .eq(SupplierInspection::getSupplierId, supplierId)
                .eq(SupplierInspection::getResult, PASSED)
                .orderByDesc(SupplierInspection::getId)
                .last("LIMIT 1"));
        return row == null ? null : toItems(Collections.singletonList(row)).get(0);
    }

    /** 供应商池用:有合格考察记录的供应商 id → 考察 id。 */
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
        row.setResult(PENDING);
        row.setProjectId(UserContext.getProjectId());
        inspectionMapper.insert(row);
        return row.getId();
    }

    @Transactional
    public void update(Long id, InspectionDtos.SaveRequest req) {
        SupplierInspection row = requirePending(load(id), "编辑");
        apply(row, req);
        inspectionMapper.updateById(row);
    }

    @Transactional
    public void delete(Long id) {
        requirePending(load(id), "删除");
        inspectionMapper.deleteById(id);
    }

    /** 上传考察压缩包前的业务校验(FileStorageService 调用):记录须存在且未判定。 */
    public void assertCanAttach(Long id) {
        if (id == null) {
            throw new BizException(400, "请先保存考察记录再上传压缩包");
        }
        requirePending(load(id), "上传考察记录");
    }

    // ============ 判定 ============

    @Transactional
    public InspectionDtos.DecideResult decide(Long id, InspectionDtos.DecideRequest req) {
        SupplierInspection row = requirePending(load(id), "判定");
        String result = req.getResult() == null ? "" : req.getResult().trim();
        if (!PASSED.equals(result) && !FAILED.equals(result)) {
            throw new BizException(400, "考察结果应为「合格」或「不合格」");
        }

        CurrentUser u = UserContext.require();
        InspectionDtos.DecideResult out = new InspectionDtos.DecideResult();
        out.setResult(result);

        if (PASSED.equals(result)) {
            Supplier existing = supplierMapper.selectOne(new LambdaQueryWrapper<Supplier>()
                    .eq(Supplier::getName, row.getCompanyName())
                    .last("LIMIT 1"));
            if (existing != null) {
                row.setSupplierId(existing.getId());
                out.setSupplierCreated(false);
                out.setMessage("供应商池已有同名公司,已直接关联(阶段仍为「" + existing.getStatus() + "」)");
            } else {
                Supplier s = new Supplier();
                s.setName(row.getCompanyName());
                s.setContact(row.getContact());
                s.setPhone(row.getPhone());
                List<String> scope = splitScope(row.getBusinessScope());
                s.setMainCategory(scope.isEmpty() ? null : scope.get(0));
                s.setStatus(PASSED_SUPPLIER_STATUS);
                s.setProjectId(row.getProjectId());
                s.setRemark(scopeRemark(row));
                supplierMapper.insert(s);
                row.setSupplierId(s.getId());
                out.setSupplierCreated(true);
                out.setMessage("考察合格,已列入供应商·上游(阶段=" + PASSED_SUPPLIER_STATUS + ")");
            }
            out.setSupplierId(row.getSupplierId());
        } else {
            row.setSupplierId(null);
            out.setMessage("考察不合格,不列入供应商·上游");
        }

        row.setResult(result);
        row.setConclusion(req.getConclusion());
        row.setDecidedBy(u.getUserId());
        row.setDecidedByName(u.getUserName());
        row.setDecidedAt(LocalDateTime.now());
        inspectionMapper.updateById(row);

        auditLogService.record("供应商考察判定", BIZ_TYPE, id, AuditLogService.EXECUTED,
                result + (row.getSupplierId() != null ? " → supplier#" + row.getSupplierId() : "")
                        + (notBlank(req.getConclusion()) ? " · " + req.getConclusion() : ""));
        log.info("供应商考察判定: id={}, result={}, supplierId={}, by={}", id, result, row.getSupplierId(), u.getUserId());
        return out;
    }

    // ============ 内部工具 ============

    private void apply(SupplierInspection row, InspectionDtos.SaveRequest req) {
        row.setCompanyName(req.getCompanyName().trim());
        row.setLegalPerson(trimToNull(req.getLegalPerson()));
        row.setRegisteredCapital(req.getRegisteredCapital());
        row.setBusinessScope(joinScope(req.getBusinessScope()));
        row.setContact(trimToNull(req.getContact()));
        row.setPhone(trimToNull(req.getPhone()));
        row.setRemark(req.getRemark());
    }

    private List<InspectionDtos.Item> toItems(List<SupplierInspection> rows) {
        if (rows.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> ids = rows.stream().map(SupplierInspection::getId).collect(Collectors.toSet());
        Map<Long, Long> archiveCount = fileObjectMapper.selectList(new LambdaQueryWrapper<FileObject>()
                        .eq(FileObject::getBizType, BIZ_TYPE)
                        .in(FileObject::getBizId, ids))
                .stream().collect(Collectors.groupingBy(FileObject::getBizId, Collectors.counting()));

        Set<Long> supplierIds = rows.stream().map(SupplierInspection::getSupplierId)
                .filter(x -> x != null).collect(Collectors.toSet());
        Map<Long, Supplier> suppliers = supplierIds.isEmpty() ? new HashMap<>()
                : supplierMapper.selectBatchIds(supplierIds).stream()
                .collect(Collectors.toMap(Supplier::getId, s -> s));

        List<InspectionDtos.Item> out = new ArrayList<>();
        for (SupplierInspection r : rows) {
            InspectionDtos.Item it = new InspectionDtos.Item();
            it.setId(r.getId());
            it.setCompanyName(r.getCompanyName());
            it.setLegalPerson(r.getLegalPerson());
            it.setRegisteredCapital(r.getRegisteredCapital());
            it.setBusinessScope(splitScope(r.getBusinessScope()));
            it.setContact(r.getContact());
            it.setPhone(r.getPhone());
            it.setResult(r.getResult());
            it.setConclusion(r.getConclusion());
            it.setDecidedByName(r.getDecidedByName());
            it.setDecidedAt(r.getDecidedAt());
            it.setSupplierId(r.getSupplierId());
            Supplier s = r.getSupplierId() == null ? null : suppliers.get(r.getSupplierId());
            it.setSupplierName(s == null ? null : s.getName());
            it.setSupplierStatus(s == null ? null : s.getStatus());
            it.setArchiveCount(archiveCount.getOrDefault(r.getId(), 0L).intValue());
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

    private SupplierInspection requirePending(SupplierInspection row, String action) {
        if (!PENDING.equals(row.getResult())) {
            throw new BizException(400, "考察已判定为「" + row.getResult() + "」,不可再" + action + ";需复查请新建考察记录");
        }
        return row;
    }

    private String joinScope(List<String> scope) {
        if (scope == null || scope.isEmpty()) {
            return null;
        }
        Set<String> picked = new LinkedHashSet<>();
        for (String s : scope) {
            String t = s == null ? "" : s.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (!VALID_SCOPE.contains(t)) {
                throw new BizException(400, "业务范围取值非法: " + t + ",应为 货架/阁楼/播种墙");
            }
            picked.add(t);
        }
        // 按固定顺序落库,便于筛选与展示一致
        return picked.isEmpty() ? null
                : VALID_SCOPE.stream().filter(picked::contains).collect(Collectors.joining(","));
    }

    private List<String> splitScope(String scope) {
        if (scope == null || scope.trim().isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(scope.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
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
