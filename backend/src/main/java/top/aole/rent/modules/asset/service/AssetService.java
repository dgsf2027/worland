package top.aole.rent.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.RoleGuard;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.domain.AssetBom;
import top.aole.rent.modules.asset.domain.AssetEvent;
import top.aole.rent.modules.asset.dto.AssetDetailResponse;
import top.aole.rent.modules.asset.dto.AssetListItem;
import top.aole.rent.modules.asset.dto.AssetSaveRequest;
import top.aole.rent.modules.asset.dto.BomNodeRequest;
import top.aole.rent.modules.asset.dto.IdleAlertResponse;
import top.aole.rent.modules.asset.dto.StatusChangeRequest;
import top.aole.rent.modules.asset.mapper.AssetBomMapper;
import top.aole.rent.modules.asset.mapper.AssetEventMapper;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.contract.domain.ContractAsset;
import top.aole.rent.modules.contract.domain.RentSchedule;
import top.aole.rent.modules.contract.mapper.ContractAssetMapper;
import top.aole.rent.modules.contract.mapper.RentScheduleMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 设备台账服务(逐件 · M1-06/07)。台账/详情(配件树递归/成本拆解/残值/故障档案/单台收益/状态机)/CRUD/状态流转。
 *
 * <p><b>单一真相源(§4.24/§4.17)</b>:
 * <ul>
 *   <li>{@code status} 状态机由 {@link AssetEvent} 事件流驱动,{@code changeStatus} 走事件+校验,禁直写。</li>
 *   <li>book_value(经营口径) 即时算 = 集采价 - 直线折旧占位【M3 折旧表 asset_depreciation_line 精确化】。</li>
 *   <li>residual_value(残值) 即时算 = 市场价 × transfer_rate[category](rule_config,缺 → null)。</li>
 *   <li>self_purchase_payback(自购回本期·月) 即时算 = 市场价 / 月替代人工价值。</li>
 * </ul>
 * <p>字段级隔离:集采价/账面价/成本拆解/回报率 对 GP/LP 打码(seeCost=false → null + masked)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetService {

    private final AssetMapper assetMapper;
    private final AssetBomMapper bomMapper;
    private final AssetEventMapper eventMapper;
    private final RuleConfigService rules;
    private final SupplierMapper supplierMapper;
    private final CustomerMapper customerMapper;
    private final ContractAssetMapper contractAssetMapper;
    private final RentScheduleMapper rentScheduleMapper;
    private final AuditLogService auditLogService;

    /** 状态机:from → 允许的 to 集合。已转让/报废为终态。 */
    private static final Map<String, Set<String>> TRANSITIONS = new HashMap<>();
    static {
        TRANSITIONS.put("采购", new HashSet<>(Arrays.asList("投放", "报废")));
        TRANSITIONS.put("投放", new HashSet<>(Arrays.asList("在租", "收回待处置", "报废")));
        TRANSITIONS.put("在租", new HashSet<>(Arrays.asList("待转让", "收回待处置")));
        TRANSITIONS.put("待转让", new HashSet<>(Arrays.asList("已转让", "收回待处置")));
        TRANSITIONS.put("收回待处置", new HashSet<>(Arrays.asList("投放", "已转让", "报废")));
        TRANSITIONS.put("已转让", new HashSet<>());
        TRANSITIONS.put("报废", new HashSet<>());
    }
    private static final Set<String> VALID_STATUS = TRANSITIONS.keySet();

    /** 目标状态 → 事件类型(再投放特殊命名)。 */
    private static String eventTypeFor(String from, String to) {
        if ("已转让".equals(to)) {
            return "转让";
        }
        if ("投放".equals(to) && "收回待处置".equals(from)) {
            return "再投放";
        }
        return to;
    }

    // ============ 台账列表 ============

    public PageResult<AssetListItem> list(String status, String category, String keyword, int page, int size) {
        boolean seeCost = DataScope.canSeeCost(UserContext.getRole());
        LambdaQueryWrapper<Asset> qw = new LambdaQueryWrapper<Asset>()
                .eq(status != null && !status.isEmpty(), Asset::getStatus, status)
                .eq(category != null && !category.isEmpty(), Asset::getCategory, category)
                .and(keyword != null && !keyword.trim().isEmpty(), w -> w
                        .like(Asset::getSerialNo, keyword.trim())
                        .or().like(Asset::getModel, keyword.trim()))
                .orderByAsc(Asset::getId);
        List<Asset> all = assetMapper.selectList(qw);

        List<AssetListItem> items = new ArrayList<>();
        for (Asset a : all) {
            AssetListItem it = new AssetListItem();
            it.setId(a.getId());
            it.setSerialNo(a.getSerialNo());
            it.setCategory(a.getCategory());
            it.setModel(a.getModel());
            it.setStatus(a.getStatus());
            it.setMarketPrice(a.getMarketPrice());
            it.setPurchasePrice(seeCost ? a.getPurchasePrice() : null);
            it.setBookValue(seeCost ? bookValue(a) : null);
            it.setResidualValue(residualValue(a));
            it.setSupplierName(supplierName(a.getSupplierId()));
            it.setCurrentHolderCustomerId(a.getCurrentHolderCustomerId());
            it.setCurrentHolderName(customerName(a.getCurrentHolderCustomerId()));
            it.setSensitiveMasked(!seeCost);
            items.add(it);
        }

        long total = items.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(items.size(), from + size);
        List<AssetListItem> records = from >= items.size() ? new ArrayList<>() : items.subList(from, to);
        return new PageResult<>(total, page, size, records);
    }

    // ============ 详情 ============

    public AssetDetailResponse detail(Long id) {
        Asset a = load(id);
        boolean seeCost = DataScope.canSeeCost(UserContext.getRole());

        AssetDetailResponse r = new AssetDetailResponse();
        r.setId(a.getId());
        r.setSerialNo(a.getSerialNo());
        r.setCategory(a.getCategory());
        r.setModel(a.getModel());
        r.setStatus(a.getStatus());
        r.setMarketPrice(a.getMarketPrice());
        r.setPurchasePrice(seeCost ? a.getPurchasePrice() : null);
        r.setMonthlyLaborValue(a.getMonthlyLaborValue());
        r.setReplaceHeadcount(a.getReplaceHeadcount());
        r.setSupplierName(supplierName(a.getSupplierId()));
        r.setCurrentHolderName(customerName(a.getCurrentHolderCustomerId()));
        r.setContractId(a.getContractId());
        r.setRemark(a.getRemark());
        r.setSensitiveMasked(!seeCost);
        r.setBookValue(seeCost ? bookValue(a) : null);
        r.setResidualValue(residualValue(a));
        r.setSelfPurchasePayback(selfPurchasePayback(a));

        List<AssetBom> boms = bomMapper.selectList(new LambdaQueryWrapper<AssetBom>()
                .eq(AssetBom::getAssetId, id)
                .orderByAsc(AssetBom::getId));
        r.setBom(buildBomTree(boms, seeCost));
        r.setCostBreakdown(seeCost ? costBreakdown(a, boms) : null);
        r.setResidualBreakdown(residualBreakdown(a, boms));
        r.setFaultArchive(faultArchive(boms));
        r.setSingleUnitReturn(singleUnitReturn(a, seeCost));
        r.setTimeline(timeline(id));
        return r;
    }

    // ============ CRUD ============

    @Transactional
    public Long create(AssetSaveRequest req) {
        Asset dup = assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getSerialNo, req.getSerialNo().trim()));
        if (dup != null) {
            throw new BizException(400, "序列号已存在: " + req.getSerialNo());
        }
        Asset a = new Asset();
        applySave(a, req);
        a.setStatus("采购");
        assetMapper.insert(a);
        writeEvent(a.getId(), "采购", null, null, "逐件建档");
        return a.getId();
    }

    @Transactional
    public void update(Long id, AssetSaveRequest req) {
        Asset a = load(id);
        if (!a.getSerialNo().equals(req.getSerialNo().trim())) {
            Asset dup = assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                    .eq(Asset::getSerialNo, req.getSerialNo().trim()));
            if (dup != null && !dup.getId().equals(id)) {
                throw new BizException(400, "序列号已存在: " + req.getSerialNo());
            }
        }
        applySave(a, req);
        assetMapper.updateById(a);
    }

    private void applySave(Asset a, AssetSaveRequest req) {
        a.setSerialNo(req.getSerialNo().trim());
        a.setCategory(req.getCategory().trim());
        a.setModel(req.getModel());
        a.setMarketPrice(req.getMarketPrice());
        a.setPurchasePrice(req.getPurchasePrice());
        a.setSupplierId(req.getSupplierId());
        a.setMonthlyLaborValue(req.getMonthlyLaborValue());
        a.setReplaceHeadcount(req.getReplaceHeadcount());
        a.setRemark(req.getRemark());
    }

    // ============ 状态流转(走事件流 + 状态机校验) ============

    @Transactional
    public void changeStatus(Long id, StatusChangeRequest req) {
        Asset a = load(id);
        String from = a.getStatus();
        String to = req.getTargetStatus() == null ? "" : req.getTargetStatus().trim();
        if (!VALID_STATUS.contains(to)) {
            throw new BizException(400, "非法目标状态: " + to);
        }
        Set<String> allowed = TRANSITIONS.getOrDefault(from, new HashSet<>());
        if (!allowed.contains(to)) {
            throw new BizException(400, "非法状态流转: " + from + " → " + to
                    + "(允许: " + (allowed.isEmpty() ? "终态" : String.join("/", allowed)) + ")");
        }
        // 投放审批→老板(P0-D):投放是资金投放决策,任意路径(含手动流转/再投放)统一卡老板。
        // body 依赖(目标状态在入参里),切面无法声明式拦,故在此 service 内联守卫,与 /deploy 端点双保险。
        if ("投放".equals(to)) {
            RoleGuard.assertRole("老板");
        }
        // 手动流转到"投放/收回待处置/报废"时脱离承租关系(在租/转让由合同事件维护)。
        // 用 LambdaUpdateWrapper 显式置 null——updateById 默认跳过 null 字段(FieldStrategy.NOT_NULL)。
        LambdaUpdateWrapper<Asset> uw = new LambdaUpdateWrapper<Asset>()
                .eq(Asset::getId, id)
                .set(Asset::getStatus, to);
        if (!"在租".equals(to)) {
            uw.set(Asset::getCurrentHolderCustomerId, null);
            if (!"待转让".equals(to) && !"已转让".equals(to)) {
                uw.set(Asset::getContractId, null);
            }
        }
        assetMapper.update(null, uw);
        writeEvent(id, eventTypeFor(from, to), req.getRefDocType(), req.getRefDocId(), req.getRemark());
        log.info("设备状态流转: assetId={}, {} → {}, by={}", id, from, to, UserContext.getUserId());
    }

    // ============ 合同驱动的资产状态(设备状态机 owner,供 ContractService 调用) ============

    /** 签约:设备转在租 + 绑定承租客户/合同(走事件流)。允许从 采购/投放 起租。 */
    @Transactional
    public void markRented(Long assetId, Long customerId, Long contractId) {
        Asset a = load(assetId);
        String from = a.getStatus();
        if (!"采购".equals(from) && !"投放".equals(from)) {
            throw new BizException(400, "设备 " + a.getSerialNo() + " 当前状态 " + from + " 不可签约起租(需 采购/投放)");
        }
        a.setStatus("在租");
        a.setCurrentHolderCustomerId(customerId);
        a.setContractId(contractId);
        assetMapper.updateById(a);
        writeEvent(assetId, "在租", "contract", contractId, "签约起租");
    }

    /** 合同作废:释放设备(在租→投放·再投放),清承租关系(走事件流)。 */
    @Transactional
    public void releaseOnVoid(Long assetId, Long contractId) {
        Asset a = assetMapper.selectById(assetId);
        if (a == null) {
            return;
        }
        // 显式置 null 清承租关系(updateById 会跳过 null 字段)
        assetMapper.update(null, new LambdaUpdateWrapper<Asset>()
                .eq(Asset::getId, assetId)
                .set(Asset::getStatus, "投放")
                .set(Asset::getCurrentHolderCustomerId, null)
                .set(Asset::getContractId, null));
        writeEvent(assetId, "再投放", "contract", contractId, "合同作废释放设备");
    }

    // ============ 采购驱动的资产建档(设备状态机 owner,供 PurchaseService 调用) ============

    /** 入库:逐件生成设备(状态=采购)+ 回填 purchase_in_id,走事件流。序列号唯一。返回 assetId。 */
    @Transactional
    public Long createForPurchase(String serialNo, String category, String model,
                                  BigDecimal marketPrice, BigDecimal purchasePrice, Long supplierId,
                                  BigDecimal monthlyLaborValue, BigDecimal replaceHeadcount,
                                  Long purchaseInId, String remark) {
        Asset dup = assetMapper.selectOne(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getSerialNo, serialNo.trim()));
        if (dup != null) {
            throw new BizException(400, "序列号已存在: " + serialNo);
        }
        Asset a = new Asset();
        a.setSerialNo(serialNo.trim());
        a.setCategory(category.trim());
        a.setModel(model);
        a.setMarketPrice(marketPrice);
        a.setPurchasePrice(purchasePrice);
        a.setSupplierId(supplierId);
        a.setMonthlyLaborValue(monthlyLaborValue);
        a.setReplaceHeadcount(replaceHeadcount);
        a.setPurchaseInId(purchaseInId);
        a.setStatus("采购");
        a.setRemark(remark);
        assetMapper.insert(a);
        writeEvent(a.getId(), "采购", "purchase_in", purchaseInId, "采购入库逐件建档");
        return a.getId();
    }

    /** 采购退货红冲:设备报废释放(在租设备禁退货)。走事件流。 */
    @Transactional
    public void scrapOnPurchaseReturn(Long assetId, Long purchaseInId) {
        Asset a = assetMapper.selectById(assetId);
        if (a == null) {
            return;
        }
        if ("在租".equals(a.getStatus())) {
            throw new BizException(400, "设备 " + a.getSerialNo() + " 已在租,不可退货红冲(先处理合同)");
        }
        assetMapper.update(null, new LambdaUpdateWrapper<Asset>()
                .eq(Asset::getId, assetId)
                .set(Asset::getStatus, "报废")
                .set(Asset::getCurrentHolderCustomerId, null)
                .set(Asset::getContractId, null));
        writeEvent(assetId, "报废", "purchase_in", purchaseInId, "采购退货红冲·设备报废释放");
    }

    /** 逾期收回:在租设备 → 收回待处置(物权在我方·清承租关系)。走事件流。供 OverdueService 调用(M2-06)。 */
    @Transactional
    public void repossess(Long assetId, Long contractId, Long repossessOrderId) {
        Asset a = assetMapper.selectById(assetId);
        if (a == null) {
            return;
        }
        // 仅在租/待转让可收回;已收回待处置/已转让/报废则跳过(幂等)
        if (!"在租".equals(a.getStatus()) && !"待转让".equals(a.getStatus())) {
            return;
        }
        assetMapper.update(null, new LambdaUpdateWrapper<Asset>()
                .eq(Asset::getId, assetId)
                .set(Asset::getStatus, "收回待处置")
                .set(Asset::getCurrentHolderCustomerId, null)
                .set(Asset::getContractId, null));
        writeEvent(assetId, "收回待处置", "repossess_order", repossessOrderId, "逾期收回·转收回待处置");
    }

    // ============ 投放/交付确认(M1-14·投放审批→老板) ============

    /** 投放/交付确认:采购/收回待处置 → 投放。走事件流 + audit(EXECUTED)。切面已卡老板。 */
    @Transactional
    public void deliver(Long assetId, String remark) {
        Asset a = load(assetId);
        String from = a.getStatus();
        if (!"采购".equals(from) && !"收回待处置".equals(from)) {
            throw new BizException(400, "设备 " + a.getSerialNo() + " 当前 " + from + " 不可投放(需 采购/收回待处置)");
        }
        String eventType = "收回待处置".equals(from) ? "再投放" : "投放";
        assetMapper.update(null, new LambdaUpdateWrapper<Asset>()
                .eq(Asset::getId, assetId)
                .set(Asset::getStatus, "投放"));
        writeEvent(assetId, eventType, null, null, remark == null ? "投放/交付确认" : remark);
        auditLogService.record("投放审批", "asset", assetId, AuditLogService.EXECUTED,
                from + "→投放 · " + (remark == null ? "交付确认" : remark));
        log.info("投放/交付确认: assetId={}, {}→投放, by={}", assetId, from, UserContext.getUserId());
    }

    // ============ 空置亮灯(M1-14·流程6) ============

    /** 空置亮灯:收回待处置 + 投放超 N 天未起租。老板驾驶舱红点数据源。 */
    public IdleAlertResponse idleAlert() {
        int threshold = idleAlertDays();
        LocalDate today = LocalDate.now();
        List<Asset> all = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .in(Asset::getStatus, Arrays.asList("投放", "收回待处置"))
                .orderByAsc(Asset::getId));

        List<IdleAlertResponse.IdleItem> items = new ArrayList<>();
        for (Asset a : all) {
            String reason = null;
            LocalDate since = null;
            if ("收回待处置".equals(a.getStatus())) {
                reason = "收回待处置";
                LocalDateTime t = lastEventTimeIn(a.getId(), new HashSet<>(Arrays.asList("收回待处置")));
                since = t != null ? t.toLocalDate() : null;
            } else if ("投放".equals(a.getStatus())) {
                // 最近一次进入投放态(投放/再投放)起算未起租天数
                LocalDateTime t = lastEventTimeIn(a.getId(), new HashSet<>(Arrays.asList("投放", "再投放")));
                if (t != null) {
                    long days = ChronoUnit.DAYS.between(t.toLocalDate(), today);
                    if (days > threshold) {
                        reason = "投放超期未起租";
                        since = t.toLocalDate();
                    }
                }
            }
            if (reason == null) {
                continue;
            }
            IdleAlertResponse.IdleItem it = new IdleAlertResponse.IdleItem();
            it.setAssetId(a.getId());
            it.setSerialNo(a.getSerialNo());
            it.setCategory(a.getCategory());
            it.setModel(a.getModel());
            it.setStatus(a.getStatus());
            it.setReason(reason);
            it.setSinceDate(since);
            it.setIdleDays(since != null ? (int) Math.max(0, ChronoUnit.DAYS.between(since, today)) : null);
            it.setResidualValue(residualValue(a));
            items.add(it);
        }
        items.sort(Comparator.comparingInt((IdleAlertResponse.IdleItem i) ->
                i.getIdleDays() == null ? 0 : i.getIdleDays()).reversed());

        IdleAlertResponse r = new IdleAlertResponse();
        r.setThresholdDays(threshold);
        r.setItems(items);
        r.setAlertCount(items.size());
        return r;
    }

    private int idleAlertDays() {
        BigDecimal v = safeValue("idle_alert_days", "");
        return v != null && v.intValue() > 0 ? v.intValue() : 30;
    }

    /** 某设备最近一次指定类型事件的业务时间(无则 null)。 */
    private LocalDateTime lastEventTimeIn(Long assetId, Set<String> eventTypes) {
        List<AssetEvent> es = eventMapper.selectList(new LambdaQueryWrapper<AssetEvent>()
                .eq(AssetEvent::getAssetId, assetId)
                .in(AssetEvent::getEventType, eventTypes)
                .orderByDesc(AssetEvent::getBizTime).orderByDesc(AssetEvent::getId));
        return es.isEmpty() ? null : es.get(0).getBizTime();
    }

    // ============ 配件树 BOM 维护 ============

    @Transactional
    public Long addBom(Long assetId, BomNodeRequest req) {
        load(assetId);
        if (req.getParentId() != null) {
            AssetBom parent = bomMapper.selectById(req.getParentId());
            if (parent == null || !assetId.equals(parent.getAssetId())) {
                throw new BizException(400, "父配件不存在或不属于本设备: parentId=" + req.getParentId());
            }
        }
        AssetBom b = new AssetBom();
        b.setAssetId(assetId);
        applyBom(b, req);
        bomMapper.insert(b);
        return b.getId();
    }

    @Transactional
    public void updateBom(Long bomId, BomNodeRequest req) {
        AssetBom b = bomMapper.selectById(bomId);
        if (b == null || Integer.valueOf(1).equals(b.getIsDeleted())) {
            throw new BizException(404, "配件不存在: id=" + bomId);
        }
        // 不允许把节点挂到自己或自己的后代下(防成环)
        if (req.getParentId() != null) {
            if (req.getParentId().equals(bomId)) {
                throw new BizException(400, "父配件不能是自己");
            }
            Set<Long> descendants = descendantIds(b.getAssetId(), bomId);
            if (descendants.contains(req.getParentId())) {
                throw new BizException(400, "父配件不能是自己的后代(会成环)");
            }
        }
        applyBom(b, req);
        bomMapper.updateById(b);
    }

    @Transactional
    public void deleteBom(Long bomId) {
        AssetBom b = bomMapper.selectById(bomId);
        if (b == null || Integer.valueOf(1).equals(b.getIsDeleted())) {
            throw new BizException(404, "配件不存在: id=" + bomId);
        }
        // 递归逻辑删自身 + 后代
        Set<Long> toDelete = descendantIds(b.getAssetId(), bomId);
        toDelete.add(bomId);
        for (Long delId : toDelete) {
            bomMapper.deleteById(delId);
        }
    }

    private void applyBom(AssetBom b, BomNodeRequest req) {
        b.setParentId(req.getParentId());
        b.setName(req.getName().trim());
        b.setQty(req.getQty() != null ? req.getQty() : BigDecimal.ONE);
        b.setUnitCost(req.getUnitCost());
        b.setSupplierId(req.getSupplierId());
        b.setLifeYears(req.getLifeYears());
        b.setWarrantyUntil(req.getWarrantyUntil());
        b.setRepairable(req.getRepairable() == null || req.getRepairable() ? 1 : 0);
        b.setFaultCount(req.getFaultCount() != null ? req.getFaultCount() : 0);
        b.setResidualRate(req.getResidualRate());
        b.setRemark(req.getRemark());
    }

    /** 某节点的全部后代 id(不含自身)。 */
    private Set<Long> descendantIds(Long assetId, Long rootId) {
        List<AssetBom> all = bomMapper.selectList(new LambdaQueryWrapper<AssetBom>()
                .eq(AssetBom::getAssetId, assetId));
        Map<Long, List<Long>> childMap = new HashMap<>();
        for (AssetBom b : all) {
            childMap.computeIfAbsent(b.getParentId(), k -> new ArrayList<>()).add(b.getId());
        }
        Set<Long> out = new HashSet<>();
        collectDescendants(rootId, childMap, out);
        return out;
    }

    private void collectDescendants(Long node, Map<Long, List<Long>> childMap, Set<Long> out) {
        for (Long child : childMap.getOrDefault(node, new ArrayList<>())) {
            if (out.add(child)) {
                collectDescendants(child, childMap, out);
            }
        }
    }

    // ============ 派生计算(即时算) ============

    /** 残值 = 市场价 × transfer_rate[category];品类缺配置 → null。 */
    private BigDecimal residualValue(Asset a) {
        if (a.getMarketPrice() == null) {
            return null;
        }
        BigDecimal rate = safeRate("transfer_rate", a.getCategory());
        if (rate == null) {
            return null;
        }
        return a.getMarketPrice().multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 经营口径账面价(简化占位·M3 折旧表精确化):
     * 折旧基数 =(集采价 - 残值),按品类租期直线折旧,已用月 = 首次投放/在租至今(封顶租期)。
     */
    private BigDecimal bookValue(Asset a) {
        if (a.getPurchasePrice() == null) {
            return null;
        }
        BigDecimal residual = residualValue(a);
        BigDecimal base = residual != null ? a.getPurchasePrice().subtract(residual) : a.getPurchasePrice();
        int lifeMonths = lifeMonths(a.getCategory());
        int elapsed = monthsInServiceElapsed(a.getId());
        elapsed = Math.min(elapsed, lifeMonths);
        BigDecimal accumDepr = base
                .multiply(BigDecimal.valueOf(elapsed))
                .divide(BigDecimal.valueOf(lifeMonths), 2, RoundingMode.HALF_UP);
        return a.getPurchasePrice().subtract(accumDepr).setScale(2, RoundingMode.HALF_UP);
    }

    /** 自购回本期(月)= 市场价 / 月替代人工价值。 */
    private BigDecimal selfPurchasePayback(Asset a) {
        if (a.getMarketPrice() == null || a.getMonthlyLaborValue() == null
                || a.getMonthlyLaborValue().signum() == 0) {
            return null;
        }
        return a.getMarketPrice().divide(a.getMonthlyLaborValue(), 1, RoundingMode.HALF_UP);
    }

    private int lifeMonths(String category) {
        BigDecimal v = safeValue("term_months", category);
        return v != null && v.intValue() > 0 ? v.intValue() : 36;
    }

    /** 首次投放/在租至今月数(无投放事件 → 0)。 */
    private int monthsInServiceElapsed(Long assetId) {
        LocalDateTime deploy = firstEventTime(assetId, "投放");
        if (deploy == null) {
            deploy = firstEventTime(assetId, "在租");
        }
        if (deploy == null) {
            return 0;
        }
        long months = ChronoUnit.MONTHS.between(deploy.toLocalDate(), LocalDate.now());
        return (int) Math.max(0, months);
    }

    private LocalDateTime firstEventTime(Long assetId, String eventType) {
        List<AssetEvent> es = eventMapper.selectList(new LambdaQueryWrapper<AssetEvent>()
                .eq(AssetEvent::getAssetId, assetId)
                .eq(AssetEvent::getEventType, eventType)
                .orderByAsc(AssetEvent::getBizTime));
        return es.isEmpty() ? null : es.get(0).getBizTime();
    }

    // ============ 配件树 / 拆解 ============

    private List<AssetDetailResponse.BomNode> buildBomTree(List<AssetBom> boms, boolean seeCost) {
        Map<Long, AssetDetailResponse.BomNode> nodeMap = new HashMap<>();
        for (AssetBom b : boms) {
            AssetDetailResponse.BomNode n = new AssetDetailResponse.BomNode();
            n.setId(b.getId());
            n.setParentId(b.getParentId());
            n.setName(b.getName());
            n.setQty(b.getQty());
            BigDecimal subtotal = subtotal(b);
            n.setUnitCost(seeCost ? b.getUnitCost() : null);
            n.setSubtotal(seeCost ? subtotal : null);
            n.setSupplierName(supplierName(b.getSupplierId()));
            n.setLifeYears(b.getLifeYears());
            n.setWarrantyUntil(b.getWarrantyUntil());
            n.setRepairable(b.getRepairable() != null && b.getRepairable() == 1);
            n.setFaultCount(b.getFaultCount());
            n.setResidualRate(b.getResidualRate());
            n.setChildren(new ArrayList<>());
            nodeMap.put(b.getId(), n);
        }
        List<AssetDetailResponse.BomNode> roots = new ArrayList<>();
        for (AssetBom b : boms) {
            AssetDetailResponse.BomNode n = nodeMap.get(b.getId());
            if (b.getParentId() != null && nodeMap.containsKey(b.getParentId())) {
                nodeMap.get(b.getParentId()).getChildren().add(n);
            } else {
                roots.add(n);
            }
        }
        return roots;
    }

    private AssetDetailResponse.CostBreakdown costBreakdown(Asset a, List<AssetBom> boms) {
        AssetDetailResponse.CostBreakdown cb = new AssetDetailResponse.CostBreakdown();
        List<AssetBom> topLevel = boms.stream()
                .filter(b -> b.getParentId() == null)
                .collect(Collectors.toList());
        BigDecimal total = BigDecimal.ZERO;
        for (AssetBom b : topLevel) {
            total = total.add(subtotal(b));
        }
        List<AssetDetailResponse.CostItem> items = new ArrayList<>();
        for (AssetBom b : topLevel) {
            AssetDetailResponse.CostItem it = new AssetDetailResponse.CostItem();
            it.setName(b.getName());
            BigDecimal amt = subtotal(b);
            it.setAmount(amt);
            it.setRatio(total.signum() > 0
                    ? amt.divide(total, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO);
            items.add(it);
        }
        cb.setItems(items);
        cb.setTotal(total.setScale(2, RoundingMode.HALF_UP));
        cb.setPurchasePrice(a.getPurchasePrice());
        cb.setGapVsPurchase(a.getPurchasePrice() != null
                ? a.getPurchasePrice().subtract(total).setScale(2, RoundingMode.HALF_UP) : null);
        return cb;
    }

    private AssetDetailResponse.ResidualBreakdown residualBreakdown(Asset a, List<AssetBom> boms) {
        AssetDetailResponse.ResidualBreakdown rb = new AssetDetailResponse.ResidualBreakdown();
        List<AssetDetailResponse.CostItem> items = new ArrayList<>();
        BigDecimal bomResidual = BigDecimal.ZERO;
        boolean seeCost = DataScope.canSeeCost(UserContext.getRole());
        for (AssetBom b : boms) {
            if (b.getResidualRate() == null || b.getUnitCost() == null) {
                continue;
            }
            BigDecimal res = subtotal(b).multiply(b.getResidualRate()).setScale(2, RoundingMode.HALF_UP);
            bomResidual = bomResidual.add(res);
            if (seeCost) {
                AssetDetailResponse.CostItem it = new AssetDetailResponse.CostItem();
                it.setName(b.getName());
                it.setAmount(res);
                it.setRatio(b.getResidualRate());
                items.add(it);
            }
        }
        rb.setItems(seeCost ? items : null);
        rb.setBomResidualTotal(seeCost ? bomResidual.setScale(2, RoundingMode.HALF_UP) : null);
        rb.setCategoryResidual(residualValue(a));
        return rb;
    }

    private List<AssetDetailResponse.FaultItem> faultArchive(List<AssetBom> boms) {
        List<AssetDetailResponse.FaultItem> out = new ArrayList<>();
        for (AssetBom b : boms) {
            AssetDetailResponse.FaultItem fi = new AssetDetailResponse.FaultItem();
            fi.setName(b.getName());
            fi.setFaultCount(b.getFaultCount() != null ? b.getFaultCount() : 0);
            fi.setRepairable(b.getRepairable() != null && b.getRepairable() == 1);
            fi.setWarrantyUntil(b.getWarrantyUntil());
            fi.setSupplierName(supplierName(b.getSupplierId()));
            fi.setWarrantyDaysLeft(b.getWarrantyUntil() != null
                    ? ChronoUnit.DAYS.between(LocalDate.now(), b.getWarrantyUntil()) : null);
            out.add(fi);
        }
        out.sort(Comparator.comparingInt((AssetDetailResponse.FaultItem f) ->
                f.getFaultCount() == null ? 0 : f.getFaultCount()).reversed());
        return out;
    }

    private AssetDetailResponse.SingleUnitReturn singleUnitReturn(Asset a, boolean seeCost) {
        AssetDetailResponse.SingleUnitReturn sr = new AssetDetailResponse.SingleUnitReturn();
        // 取本设备在租的 contract_asset(单台分摊)
        ContractAsset ca = contractAssetMapper.selectOne(new LambdaQueryWrapper<ContractAsset>()
                .eq(ContractAsset::getAssetId, a.getId())
                .orderByDesc(ContractAsset::getId)
                .last("limit 1"));
        BigDecimal allocRent = ca != null ? ca.getAllocRent() : null;
        sr.setAllocRent(allocRent);

        int inServiceDays = 0;
        LocalDateTime rentStart = firstEventTime(a.getId(), "在租");
        if (rentStart != null) {
            inServiceDays = (int) Math.max(0, ChronoUnit.DAYS.between(rentStart.toLocalDate(), LocalDate.now()));
        }
        sr.setInServiceDays(inServiceDays);

        // 空置天数:投放但未在租
        int idleDays = 0;
        if ("投放".equals(a.getStatus())) {
            LocalDateTime deploy = firstEventTime(a.getId(), "投放");
            if (deploy != null) {
                idleDays = (int) Math.max(0, ChronoUnit.DAYS.between(deploy.toLocalDate(), LocalDate.now()));
            }
        }
        sr.setIdleDays(idleDays);
        sr.setIdleAlert(idleDays > 30);

        // 累计收租(占位):alloc_rent × 已过期数(rent_schedule.due_date<=今天);M2 以 rent_bill 精确化
        BigDecimal cumulativeRent = null;
        if (allocRent != null && ca != null) {
            long elapsedPeriods = rentScheduleMapper.selectList(new LambdaQueryWrapper<RentSchedule>()
                    .eq(RentSchedule::getContractId, ca.getContractId())
                    .le(RentSchedule::getDueDate, LocalDate.now()))
                    .size();
            cumulativeRent = allocRent.multiply(BigDecimal.valueOf(elapsedPeriods)).setScale(2, RoundingMode.HALF_UP);
        }
        sr.setCumulativeRent(seeCost ? cumulativeRent : null);

        // 单台回报率 =(累计收租 + 残值 - 集采)/ 集采
        if (seeCost && cumulativeRent != null && a.getPurchasePrice() != null
                && a.getPurchasePrice().signum() > 0) {
            BigDecimal residual = residualValue(a);
            BigDecimal gain = cumulativeRent.add(residual != null ? residual : BigDecimal.ZERO)
                    .subtract(a.getPurchasePrice());
            sr.setReturnRate(gain.divide(a.getPurchasePrice(), 4, RoundingMode.HALF_UP));
        } else {
            sr.setReturnRate(null);
        }
        return sr;
    }

    private List<AssetDetailResponse.EventItem> timeline(Long assetId) {
        List<AssetEvent> es = eventMapper.selectList(new LambdaQueryWrapper<AssetEvent>()
                .eq(AssetEvent::getAssetId, assetId)
                .orderByDesc(AssetEvent::getBizTime).orderByDesc(AssetEvent::getId));
        List<AssetDetailResponse.EventItem> out = new ArrayList<>();
        for (AssetEvent e : es) {
            AssetDetailResponse.EventItem it = new AssetDetailResponse.EventItem();
            it.setEventType(e.getEventType());
            it.setBizTime(e.getBizTime());
            it.setRefDocType(e.getRefDocType());
            it.setRefDocId(e.getRefDocId());
            it.setOperatorName(userName(e.getOperatorId()));
            it.setRemark(e.getRemark());
            out.add(it);
        }
        return out;
    }

    // ============ 工具 ============

    private BigDecimal subtotal(AssetBom b) {
        if (b.getUnitCost() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal qty = b.getQty() != null ? b.getQty() : BigDecimal.ONE;
        return b.getUnitCost().multiply(qty).setScale(2, RoundingMode.HALF_UP);
    }

    private Asset load(Long id) {
        Asset a = assetMapper.selectById(id);
        if (a == null || Integer.valueOf(1).equals(a.getIsDeleted())) {
            throw new BizException(404, "设备不存在: id=" + id);
        }
        return a;
    }

    private void writeEvent(Long assetId, String eventType, String refDocType, Long refDocId, String remark) {
        AssetEvent e = new AssetEvent();
        e.setAssetId(assetId);
        e.setEventType(eventType);
        e.setRefDocType(refDocType);
        e.setRefDocId(refDocId);
        e.setBizTime(LocalDateTime.now());
        e.setOperatorId(UserContext.get() != null ? UserContext.get().getUserId() : null);
        e.setRemark(remark);
        eventMapper.insert(e);
    }

    /** 缺配置返回 null(不抛),用于品类可能无对应 rule_config 的即时算。 */
    private BigDecimal safeValue(String ruleKey, String scopeKey) {
        try {
            return rules.getValue(ruleKey, scopeKey, LocalDate.now());
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal safeRate(String ruleKey, String scopeKey) {
        return safeValue(ruleKey, scopeKey);
    }

    private String supplierName(Long supplierId) {
        if (supplierId == null) {
            return null;
        }
        Supplier s = supplierMapper.selectById(supplierId);
        return s != null ? s.getName() : ("供应商#" + supplierId);
    }

    private String customerName(Long customerId) {
        if (customerId == null) {
            return null;
        }
        Customer c = customerMapper.selectById(customerId);
        return c != null ? c.getName() : ("客户#" + customerId);
    }

    private static final Map<Long, String> SEED_NAMES = new HashMap<>();
    static {
        SEED_NAMES.put(1001L, "老板");
        SEED_NAMES.put(1002L, "刘总");
        SEED_NAMES.put(1003L, "小洪");
        SEED_NAMES.put(1004L, "李工");
        SEED_NAMES.put(1005L, "财务");
        SEED_NAMES.put(1006L, "供应链");
        SEED_NAMES.put(1007L, "业务");
    }

    private String userName(Long userId) {
        if (userId == null) {
            return null;
        }
        return SEED_NAMES.getOrDefault(userId, "用户#" + userId);
    }
}
