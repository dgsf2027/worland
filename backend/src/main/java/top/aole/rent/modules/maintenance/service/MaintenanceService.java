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

    // ============ 报修/建单 ============

    @Transactional
    public Long create(MaintenanceDtos.CreateRequest req) {
        if (req == null || req.getAssetId() == null) {
            throw new BizException(400, "维保工单需指定 assetId");
        }
        Asset a = assetMapper.selectById(req.getAssetId());
        if (a == null || Integer.valueOf(1).equals(a.getIsDeleted())) {
            throw new BizException(404, "设备不存在: id=" + req.getAssetId());
        }
        String type = req.getType() == null ? "报修" : req.getType().trim();
        if (!"报修".equals(type) && !"预防".equals(type) && !"巡检".equals(type)) {
            throw new BizException(400, "非法工单类型: " + type + "(允许 报修/预防/巡检)");
        }
        if (req.getBomId() != null) {
            AssetBom b = bomMapper.selectById(req.getBomId());
            if (b == null || !req.getAssetId().equals(b.getAssetId())) {
                throw new BizException(400, "故障配件不存在或不属于本设备: bomId=" + req.getBomId());
            }
        }
        Maintenance m = new Maintenance();
        m.setNo(genNo());
        m.setAssetId(req.getAssetId());
        m.setBomId(req.getBomId());
        m.setType(type);
        m.setStatus("待派工");
        m.setFaultDesc(req.getFaultDesc());
        m.setInWarranty(0);
        m.setResponsibleParty("我方");
        m.setCost(BigDecimal.ZERO);
        m.setReportedAt(LocalDateTime.now());
        m.setOperatorId(currentUserId());
        m.setRemark(req.getRemark());
        maintenanceMapper.insert(m);
        log.info("[维保] 建单 {} 设备{} 类型{}", m.getNo(), req.getAssetId(), type);
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

    public PageResult<MaintenanceDtos.MaintenanceItem> list(String status, String type, Long assetId, int page, int size) {
        LambdaQueryWrapper<Maintenance> qw = new LambdaQueryWrapper<Maintenance>()
                .eq(status != null && !status.isEmpty(), Maintenance::getStatus, status)
                .eq(type != null && !type.isEmpty(), Maintenance::getType, type)
                .eq(assetId != null, Maintenance::getAssetId, assetId)
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
        it.setAssetId(m.getAssetId());
        Asset a = assetMapper.selectById(m.getAssetId());
        it.setSerialNo(a != null ? a.getSerialNo() : null);
        it.setAssetCategory(a != null ? a.getCategory() : null);
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
