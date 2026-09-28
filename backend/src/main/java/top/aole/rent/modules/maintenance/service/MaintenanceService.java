package top.aole.rent.modules.maintenance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.domain.AssetBom;
import top.aole.rent.modules.asset.mapper.AssetBomMapper;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.inventory.domain.InvItem;
import top.aole.rent.modules.inventory.dto.InvDtos;
import top.aole.rent.modules.inventory.mapper.InvItemMapper;
import top.aole.rent.modules.inventory.service.InvRules;
import top.aole.rent.modules.inventory.service.InvService;
import top.aole.rent.modules.maintenance.domain.Maintenance;
import top.aole.rent.modules.maintenance.dto.MaintenanceDtos;
import top.aole.rent.modules.maintenance.mapper.MaintenanceMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 维保工单服务(M4-04)。报修→派工→处理→回写闭环;故障回写 asset_bom.fault_count;
 * 质保内转供应商(费用不计我方);高故障配件 → 备件提示。
 *
 * <p><b>单一真相源</b>:{@code asset_bom.fault_count} 由本服务处理回写(@owner=维保处理);
 * 我方成本口径:责任方=我方 且 非质保内 → ourCost=cost,否则 ourCost=0(供应商/质保承担)。
 *
 * <p><b>两类对象</b>(V119,并入资产管理模块后):{@code target_type=asset} 对设备台账开单,
 * {@code inv_item} 对仓库物品开单。仓库物品的库存数量不由本服务写 —— 建单调
 * {@link InvService#adjust} 的「送修」、完工调「修好」或「报废」,
 * {@code stock_qty/repair_qty/scrapped_qty} 的唯一写手仍是资产管理的出入库流转。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceService {

    private final MaintenanceMapper maintenanceMapper;
    private final AssetMapper assetMapper;
    private final AssetBomMapper bomMapper;
    private final SupplierMapper supplierMapper;
    private final RuleConfigService rules;
    private final InvItemMapper invItemMapper;
    private final InvService invService;

    private static final String T_ASSET = "asset";
    private static final String T_INV_ITEM = "inv_item";

    // ============ 报修/建单 ============

    @Transactional
    public Long create(MaintenanceDtos.CreateRequest req) {
        if (req == null) {
            throw new BizException(400, "维保工单需指定报修对象");
        }
        String target = req.getTargetType() == null || req.getTargetType().trim().isEmpty()
                ? T_ASSET : req.getTargetType().trim();
        if (!T_ASSET.equals(target) && !T_INV_ITEM.equals(target)) {
            throw new BizException(400, "非法对象类型: " + target + "(允许 asset/inv_item)");
        }
        String type = req.getType() == null ? "报修" : req.getType().trim();
        if (!"报修".equals(type) && !"预防".equals(type) && !"巡检".equals(type)) {
            throw new BizException(400, "非法工单类型: " + type + "(允许 报修/预防/巡检)");
        }

        Maintenance m = new Maintenance();
        m.setNo(genNo());
        m.setTargetType(target);
        m.setType(type);
        m.setStatus("待派工");
        m.setFaultDesc(req.getFaultDesc());
        m.setInWarranty(0);
        m.setResponsibleParty("我方");
        m.setCost(BigDecimal.ZERO);
        m.setReportedAt(LocalDateTime.now());
        m.setOperatorId(currentUserId());
        m.setRemark(req.getRemark());

        if (T_ASSET.equals(target)) {
            if (req.getAssetId() == null) {
                throw new BizException(400, "设备工单需指定 assetId");
            }
            if (req.getInvItemId() != null) {
                throw new BizException(400, "设备工单不能同时指定仓库物品(两者只能选一个)");
            }
            Asset a = assetMapper.selectById(req.getAssetId());
            if (a == null || Integer.valueOf(1).equals(a.getIsDeleted())) {
                throw new BizException(404, "设备不存在: id=" + req.getAssetId());
            }
            if (req.getBomId() != null) {
                AssetBom b = bomMapper.selectById(req.getBomId());
                if (b == null || !req.getAssetId().equals(b.getAssetId())) {
                    throw new BizException(400, "故障配件不存在或不属于本设备: bomId=" + req.getBomId());
                }
            }
            m.setAssetId(req.getAssetId());
            m.setBomId(req.getBomId());
            m.setQty(1);
            maintenanceMapper.insert(m);
            log.info("[维保] 建单 {} 设备{} 类型{}", m.getNo(), req.getAssetId(), type);
            return m.getId();
        }

        // ---- 仓库物品:先校验,再调资产管理的「送修」流转,库存由那边写 ----
        if (req.getInvItemId() == null) {
            throw new BizException(400, "请选择仓库物品");
        }
        if (req.getAssetId() != null) {
            throw new BizException(400, "仓库物品工单不能同时指定设备(两者只能选一个)");
        }
        if (req.getBomId() != null) {
            throw new BizException(400, "故障配件只能用于设备工单");
        }
        InvItem item = invItemMapper.selectById(req.getInvItemId());
        if (item == null || Integer.valueOf(1).equals(item.getIsDeleted())) {
            throw new BizException(404, "仓库物品不存在: id=" + req.getInvItemId());
        }
        int qty = req.getQty() == null ? 1 : req.getQty();
        if (qty <= 0) {
            throw new BizException(400, "送修数量须大于 0");
        }
        int stock = item.getStockQty() == null ? 0 : item.getStockQty();
        if (qty > stock) {
            throw new BizException(400, "库存不足:「" + item.getName() + "」当前库存 " + stock
                    + " " + (item.getUnit() == null ? "" : item.getUnit()) + ",送修 " + qty);
        }
        m.setInvItemId(req.getInvItemId());
        m.setQty(qty);

        InvDtos.AdjustRequest adj = new InvDtos.AdjustRequest();
        adj.setType(InvRules.M_TO_REPAIR);
        adj.setQty(qty);
        adj.setRemark("维保工单 " + m.getNo() + " 报修送修");
        adj.setConditionDesc(req.getFaultDesc());
        m.setMovementOutId(invService.adjust(req.getInvItemId(), adj));

        maintenanceMapper.insert(m);
        log.info("[维保] 建单 {} 仓库物品{} 数量{} 已送修(movement={})",
                m.getNo(), req.getInvItemId(), qty, m.getMovementOutId());
        return m.getId();
    }

    // ============ 派工 ============

    @Transactional
    public MaintenanceDtos.MaintenanceItem assign(Long id, MaintenanceDtos.AssignRequest req) {
        Maintenance m = load(id);
        if ("已完成".equals(m.getStatus()) || "已关闭".equals(m.getStatus())) {
            throw new BizException(400, "工单已" + m.getStatus() + ",不可派工");
        }
        String party = req != null && req.getResponsibleParty() != null ? req.getResponsibleParty().trim() : "我方";
        if (!"我方".equals(party) && !"供应商".equals(party)) {
            throw new BizException(400, "非法责任方: " + party + "(允许 我方/供应商)");
        }
        m.setStatus("处理中");
        m.setAssigneeId(req != null ? req.getAssigneeId() : null);
        m.setResponsibleParty(party);
        if (req != null && req.getSupplierId() != null) {
            m.setSupplierId(req.getSupplierId());
        }
        m.setAssignedAt(LocalDateTime.now());
        if (req != null && req.getRemark() != null) {
            m.setRemark(appendRemark(m.getRemark(), req.getRemark()));
        }
        maintenanceMapper.updateById(m);
        log.info("[维保] 派工 {} 责任方{}", m.getNo(), party);
        return toItem(load(id));
    }

    // ============ 处理/完工(回写 fault_count·质保内转供应商) ============

    @Transactional
    public MaintenanceDtos.MaintenanceItem handle(Long id, MaintenanceDtos.HandleRequest req) {
        Maintenance m = load(id);
        if ("已完成".equals(m.getStatus()) || "已关闭".equals(m.getStatus())) {
            throw new BizException(400, "工单已" + m.getStatus() + ",不可重复处理(幂等拒)");
        }
        boolean inWarranty = req != null && Boolean.TRUE.equals(req.getInWarranty());
        m.setInWarranty(inWarranty ? 1 : 0);
        // 质保内 → 转供应商(费用不计我方)
        if (inWarranty) {
            m.setResponsibleParty("供应商");
        }
        m.setCost(req != null && req.getCost() != null ? req.getCost() : BigDecimal.ZERO);
        if (req != null && req.getHandleNote() != null) {
            m.setHandleNote(req.getHandleNote());
        }
        m.setStatus("已完成");
        m.setFinishedAt(LocalDateTime.now());

        // 仓库物品:完工把数量从「维修中」转出 —— 修好回库存,修不好直接报废。
        // 数量仍由资产管理的出入库流转写,本服务只记下 movement id。
        if (T_INV_ITEM.equals(m.getTargetType()) && m.getInvItemId() != null) {
            boolean scrapped = req != null && Boolean.TRUE.equals(req.getScrapped());
            InvDtos.AdjustRequest adj = new InvDtos.AdjustRequest();
            adj.setQty(m.getQty() == null ? 1 : m.getQty());
            adj.setRemark("维保工单 " + m.getNo() + (scrapped ? " 修不好转报废" : " 修好回库存"));
            if (scrapped) {
                adj.setType(InvRules.M_SCRAP);
                adj.setFromStatus(InvRules.S_REPAIR);
            } else {
                adj.setType(InvRules.M_REPAIRED);
            }
            m.setMovementBackId(invService.adjust(m.getInvItemId(), adj));
        }
        maintenanceMapper.updateById(m);

        // 故障回写:报修类默认回写 fault_count(除非显式 recordFault=false);预防/巡检默认不回写
        boolean recordFault = req != null && req.getRecordFault() != null
                ? req.getRecordFault() : "报修".equals(m.getType());
        if (recordFault && m.getBomId() != null) {
            AssetBom b = bomMapper.selectById(m.getBomId());
            if (b != null) {
                int fc = (b.getFaultCount() == null ? 0 : b.getFaultCount()) + 1;
                bomMapper.update(null, new LambdaUpdateWrapper<AssetBom>()
                        .eq(AssetBom::getId, m.getBomId())
                        .set(AssetBom::getFaultCount, fc));
                log.info("[维保] 工单 {} 回写配件#{} fault_count={}", m.getNo(), m.getBomId(), fc);
            }
        }
        log.info("[维保] 完工 {} 质保内={} 我方成本={}", m.getNo(), inWarranty, ourCost(m));
        return toItem(load(id));
    }

    // ============ 列表 / 详情 ============

    public PageResult<MaintenanceDtos.MaintenanceItem> list(String status, String type, Long assetId,
                                                            String targetType, Long invItemId, int page, int size) {
        LambdaQueryWrapper<Maintenance> qw = new LambdaQueryWrapper<Maintenance>()
                .eq(status != null && !status.isEmpty(), Maintenance::getStatus, status)
                .eq(type != null && !type.isEmpty(), Maintenance::getType, type)
                .eq(assetId != null, Maintenance::getAssetId, assetId)
                .eq(targetType != null && !targetType.isEmpty(), Maintenance::getTargetType, targetType)
                .eq(invItemId != null, Maintenance::getInvItemId, invItemId)
                .orderByDesc(Maintenance::getId);
        List<Maintenance> all = maintenanceMapper.selectList(qw);
        List<MaintenanceDtos.MaintenanceItem> items = new ArrayList<>();
        for (Maintenance m : all) {
            items.add(toItem(m));
        }
        long total = items.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(items.size(), from + size);
        List<MaintenanceDtos.MaintenanceItem> records = from >= items.size() ? new ArrayList<>() : items.subList(from, to);
        return new PageResult<>(total, page, size, records);
    }

    public MaintenanceDtos.MaintenanceItem detail(Long id) {
        return toItem(load(id));
    }

    // ============ 高故障配件备件提示 ============

    public MaintenanceDtos.SparePartAlertResponse sparePartAlert() {
        int threshold = faultThreshold();
        List<AssetBom> boms = bomMapper.selectList(new LambdaQueryWrapper<AssetBom>()
                .gt(AssetBom::getFaultCount, threshold)
                .orderByDesc(AssetBom::getFaultCount));
        List<MaintenanceDtos.SparePartAlert> items = new ArrayList<>();
        for (AssetBom b : boms) {
            Asset a = assetMapper.selectById(b.getAssetId());
            MaintenanceDtos.SparePartAlert it = new MaintenanceDtos.SparePartAlert();
            it.setAssetId(b.getAssetId());
            it.setSerialNo(a != null ? a.getSerialNo() : ("设备#" + b.getAssetId()));
            it.setBomId(b.getId());
            it.setBomName(b.getName());
            it.setFaultCount(b.getFaultCount());
            boolean repairable = b.getRepairable() != null && b.getRepairable() == 1;
            it.setRepairable(repairable);
            it.setSuggestion(repairable
                    ? "高故障(>" + threshold + "次)·建议常备该配件备件"
                    : "高故障且不可修·建议整机更换评估");
            items.add(it);
        }
        items.sort(Comparator.comparingInt((MaintenanceDtos.SparePartAlert i) ->
                i.getFaultCount() == null ? 0 : i.getFaultCount()).reversed());
        MaintenanceDtos.SparePartAlertResponse r = new MaintenanceDtos.SparePartAlertResponse();
        r.setThreshold(threshold);
        r.setAlertCount(items.size());
        r.setItems(items);
        return r;
    }

    // ============ 工具 ============

    private MaintenanceDtos.MaintenanceItem toItem(Maintenance m) {
        MaintenanceDtos.MaintenanceItem it = new MaintenanceDtos.MaintenanceItem();
        it.setId(m.getId());
        it.setNo(m.getNo());
        it.setTargetType(m.getTargetType() == null ? T_ASSET : m.getTargetType());
        it.setInvItemId(m.getInvItemId());
        it.setQty(m.getQty());
        it.setAssetId(m.getAssetId());
        if (T_INV_ITEM.equals(it.getTargetType())) {
            InvItem item = m.getInvItemId() == null ? null : invItemMapper.selectById(m.getInvItemId());
            it.setTargetLabel(item != null ? (item.getName() + " · " + item.getCode())
                    : ("物品#" + m.getInvItemId()));
        } else {
            Asset a = m.getAssetId() == null ? null : assetMapper.selectById(m.getAssetId());
            it.setSerialNo(a != null ? a.getSerialNo() : null);
            it.setAssetCategory(a != null ? a.getCategory() : null);
            it.setTargetLabel(a != null ? (a.getCategory() + " · " + a.getSerialNo())
                    : ("设备#" + m.getAssetId()));
        }
        it.setBomId(m.getBomId());
        if (m.getBomId() != null) {
            AssetBom b = bomMapper.selectById(m.getBomId());
            it.setBomName(b != null ? b.getName() : null);
        }
        it.setType(m.getType());
        it.setStatus(m.getStatus());
        it.setFaultDesc(m.getFaultDesc());
        it.setInWarranty(Integer.valueOf(1).equals(m.getInWarranty()));
        it.setResponsibleParty(m.getResponsibleParty());
        it.setSupplierId(m.getSupplierId());
        it.setSupplierName(supplierName(m.getSupplierId()));
        it.setCost(m.getCost());
        it.setOurCost(ourCost(m));
        it.setHandleNote(m.getHandleNote());
        it.setReportedAt(m.getReportedAt());
        it.setAssignedAt(m.getAssignedAt());
        it.setFinishedAt(m.getFinishedAt());
        it.setRemark(m.getRemark());
        return it;
    }

    /** 我方成本:责任方=我方 且 非质保内 → cost;否则 0(供应商/质保承担·不计我方)。 */
    private BigDecimal ourCost(Maintenance m) {
        BigDecimal cost = m.getCost() == null ? BigDecimal.ZERO : m.getCost();
        boolean warranty = Integer.valueOf(1).equals(m.getInWarranty());
        boolean supplierParty = "供应商".equals(m.getResponsibleParty());
        return (warranty || supplierParty) ? BigDecimal.ZERO : cost;
    }

    private int faultThreshold() {
        BigDecimal v = safeValue("spare_part_fault_threshold", "");
        return v != null && v.intValue() > 0 ? v.intValue() : 3;
    }

    private String genNo() {
        String base = "MT-" + (System.currentTimeMillis() % 10000000);
        String no = base;
        int n = 1;
        while (maintenanceMapper.selectCount(new LambdaQueryWrapper<Maintenance>().eq(Maintenance::getNo, no)) > 0) {
            no = base + "-" + (++n);
        }
        return no;
    }

    private Maintenance load(Long id) {
        Maintenance m = maintenanceMapper.selectById(id);
        if (m == null || Integer.valueOf(1).equals(m.getIsDeleted())) {
            throw new BizException(404, "维保工单不存在: id=" + id);
        }
        return m;
    }

    private String supplierName(Long supplierId) {
        if (supplierId == null) {
            return null;
        }
        Supplier s = supplierMapper.selectById(supplierId);
        return s != null ? s.getName() : ("供应商#" + supplierId);
    }

    private BigDecimal safeValue(String ruleKey, String scopeKey) {
        try {
            return rules.getValue(ruleKey, scopeKey, LocalDate.now());
        } catch (Exception e) {
            return null;
        }
    }

    private Long currentUserId() {
        return UserContext.get() != null ? UserContext.get().getUserId() : null;
    }

    private String appendRemark(String base, String add) {
        if (add == null) {
            return base;
        }
        return (base == null || base.isEmpty()) ? add : base + " | " + add;
    }
}
