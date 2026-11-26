package top.aole.rent.modules.project.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.dto.AssetSaveRequest;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.dto.ProjectPanoramaDtos;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.contract.service.PaymentDueDateCalculator;
import top.aole.rent.modules.contract.service.ProjectPanoramaService;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.pcontract.domain.PurchaseContract;
import top.aole.rent.modules.pcontract.domain.PurchaseContractTerm;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractMapper;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractTermMapper;
import top.aole.rent.modules.project.domain.Project;
import top.aole.rent.modules.project.dto.ProjectDtos;
import top.aole.rent.modules.project.mapper.ProjectMapper;
import top.aole.rent.modules.purchase.domain.PurchaseIn;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 租赁项目（一单到底）核心服务（V131）：
 * <ul>
 *   <li>一个项目串联 7 步全生命周期：报价测算 → 合同落定 → 采购排产 → 过磅安装验收 → 收租与开票 → 质保维保 → 留购结项</li>
 *   <li>支持「工程套装」按套建档（记录总货位数/吨数/库区，不再强迫拆单台）</li>
 *   <li>现场验收合格日 & 资料审核通过日保存时，自动联动采购合同月结账期、5 年质保到期日与主资产台账</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    private static final BigDecimal WEIGH_TOLERANCE_PCT = new BigDecimal("3.0");

    private final ProjectMapper projectMapper;
    private final CustomerMapper customerMapper;
    private final SupplierMapper supplierMapper;
    private final ContractMapper contractMapper;
    private final PurchaseContractMapper pcMapper;
    private final PurchaseContractTermMapper pcTermMapper;
    private final PurchaseInMapper purchaseInMapper;
    private final AssetMapper assetMapper;
    private final AssetService assetService;
    private final ProjectPanoramaService panoramaService;
    private final AuditLogService auditLogService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private top.aole.rent.modules.automation.service.AutomationEventService automationEventService;

    public List<ProjectDtos.ListItem> list(String stage, String keyword) {
        String role = UserContext.getRole();
        boolean seeCost = DataScope.canSeeCost(role);
        boolean ownerScoped = DataScope.isOwnerScoped(role);
        Long uid = UserContext.getUserId();

        String kw = keyword == null || keyword.trim().isEmpty() ? null : keyword.trim();
        List<Project> all = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getIsDeleted, 0)
                .eq(stage != null && !stage.isEmpty(), Project::getStage, stage)
                .and(kw != null, w -> w.like(Project::getProjectNo, kw)
                        .or().like(Project::getName, kw)
                        .or().like(Project::getWarehouseZone, kw))
                .orderByDesc(Project::getId));

        List<ProjectDtos.ListItem> out = new ArrayList<>();
        for (Project p : all) {
            Customer cust = p.getCustomerId() == null ? null : customerMapper.selectById(p.getCustomerId());
            if (ownerScoped && cust != null && cust.getOwnerUser() != null && !Objects.equals(cust.getOwnerUser(), uid)) {
                continue;
            }
            out.add(toListItem(p, cust, seeCost));
        }
        return out;
    }

    public ProjectDtos.Detail detail(Long id) {
        Project p = load(id);
        String role = UserContext.getRole();
        boolean seeCost = DataScope.canSeeCost(role);
        Customer cust = p.getCustomerId() == null ? null : customerMapper.selectById(p.getCustomerId());
        if (DataScope.isOwnerScoped(role) && cust != null && cust.getOwnerUser() != null
                && !Objects.equals(cust.getOwnerUser(), UserContext.getUserId())) {
            throw new BizException(403, "仅可查看本人负责或公海客户的项目");
        }
        return toDetail(p, cust, seeCost);
    }

    @Transactional
    public Long create(ProjectDtos.SaveRequest req) {
        CurrentUser u = UserContext.require();
        String no = req.getProjectNo() == null || req.getProjectNo().trim().isEmpty()
                ? nextProjectNo() : req.getProjectNo().trim();
        if (projectMapper.selectCount(new LambdaQueryWrapper<Project>()
                .eq(Project::getProjectNo, no).eq(Project::getIsDeleted, 0)) > 0) {
            throw new BizException(400, "项目编号已存在: " + no);
        }
        Project p = new Project();
        p.setProjectNo(no);
        applySave(p, req);
        p.setStage(deriveStage(p));
        p.setOwnerUserId(u.getUserId());
        p.setCreateByName(u.getUserName());
        p.setIsDeleted(0);
        projectMapper.insert(p);
        syncBidirectionalLinks(p);
        auditLogService.record("新建租赁项目", "project", p.getId(), AuditLogService.EXECUTED,
                p.getProjectNo() + " · " + p.getName());
        return p.getId();
    }

    @Transactional
    public ProjectDtos.Detail update(Long id, ProjectDtos.SaveRequest req) {
        Project p = load(id);
        applySave(p, req);
        p.setStage(deriveStage(p));
        projectMapper.updateById(p);
        syncBidirectionalLinks(p);
        auditLogService.record("更新租赁项目", "project", p.getId(), AuditLogService.EXECUTED,
                p.getProjectNo() + " · 阶段=" + p.getStage());
        return detail(id);
    }

    /**
     * 登记/更新现场交付进度、过磅吨数、三方竣工验收日与结算审核通过日。
     * <p>一旦录入验收日/审核通过日，自动联动：
     * ① 计算 5 年质保到期日；
     * ② 同步更新关联采购合同的交付字段并重算各月结付款节点到期日；
     * ③ 同步更新关联工程套装/主设备台账的质保期、过磅吨数、货位数与库区。
     */
    @Transactional
    public ProjectDtos.Detail updateDelivery(Long id, ProjectDtos.DeliveryRequest req) {
        Project p = load(id);
        if (req.getProductionStartDate() != null) p.setProductionStartDate(req.getProductionStartDate());
        if (req.getShippedDate() != null) p.setShippedDate(req.getShippedDate());
        if (req.getInstallDoneDate() != null) p.setInstallDoneDate(req.getInstallDoneDate());
        if (req.getLoadTestDate() != null) p.setLoadTestDate(req.getLoadTestDate());
        if (req.getActualWeighTons() != null) p.setActualWeighTons(req.getActualWeighTons());
        if (req.getAcceptanceDate() != null) p.setAcceptanceDate(req.getAcceptanceDate());
        if (req.getAuditPassDate() != null) p.setAuditPassDate(req.getAuditPassDate());
        if (req.getWarrantyYears() != null) p.setWarrantyYears(req.getWarrantyYears());
        if (req.getPalletSlots() != null) p.setPalletSlots(req.getPalletSlots());
        if (req.getWarehouseZone() != null) p.setWarehouseZone(req.getWarehouseZone());
        if (req.getDeliveryAddress() != null) p.setDeliveryAddress(req.getDeliveryAddress());
        if (req.getDeliveryContact() != null) p.setDeliveryContact(req.getDeliveryContact());

        int wYears = p.getWarrantyYears() != null ? p.getWarrantyYears() : 5;
        if (p.getAcceptanceDate() != null && wYears > 0) {
            p.setWarrantyEndDate(p.getAcceptanceDate().plusYears(wYears));
        }
        p.setStage(deriveStage(p));
        projectMapper.updateById(p);

        // 1. 联动关联采购合同（直发交付字段 + 自动重算月结付款节点到期日）
        if (p.getPurchaseContractId() != null) {
            PurchaseContract pc = pcMapper.selectById(p.getPurchaseContractId());
            if (pc != null && !"作废".equals(pc.getStatus())) {
                if (p.getShippedDate() != null) pc.setShippedDate(p.getShippedDate());
                if (p.getInstallDoneDate() != null) pc.setInstallDoneDate(p.getInstallDoneDate());
                if (p.getLoadTestDate() != null) pc.setLoadTestDate(p.getLoadTestDate());
                if (p.getActualWeighTons() != null) pc.setWeighTons(p.getActualWeighTons());
                if (p.getPalletSlots() != null) pc.setPalletSlots(p.getPalletSlots());
                if (p.getAcceptanceDate() != null) pc.setAcceptanceDate(p.getAcceptanceDate());
                if (p.getAuditPassDate() != null) pc.setAuditPassDate(p.getAuditPassDate());
                if (p.getWarrantyYears() != null) pc.setWarrantyYears(p.getWarrantyYears());
                if (p.getDeliveryAddress() != null) pc.setDeliveryAddress(p.getDeliveryAddress());
                if (p.getDeliveryContact() != null) pc.setDeliveryContact(p.getDeliveryContact());
                pcMapper.updateById(pc);

                // 重算采购合同各付款节点到期日（支持月结+30天/次月/次次月）
                List<PurchaseContractTerm> terms = pcTermMapper.selectList(new LambdaQueryWrapper<PurchaseContractTerm>()
                        .eq(PurchaseContractTerm::getPcId, pc.getId()));
                for (PurchaseContractTerm t : terms) {
                    LocalDate base = null;
                    if (PaymentDueDateCalculator.TRIGGER_AUDIT.equals(t.getTriggerPoint())) {
                        base = pc.getAuditPassDate();
                    } else if (PaymentDueDateCalculator.TRIGGER_ACCEPT.equals(t.getTriggerPoint())) {
                        base = pc.getAcceptanceDate();
                    } else if (PaymentDueDateCalculator.TRIGGER_ORDER.equals(t.getTriggerPoint())) {
                        base = pc.getSignedDate();
                    }
                    if (base != null) {
                        LocalDate due = PaymentDueDateCalculator.computeDueDate(
                                base, t.getDueRule(), t.getDueDays(), t.getDueDayOfMonth());
                        t.setDueDate(due);
                        pcTermMapper.updateById(t);
                    }
                }

                // 联动挂在该采购合同下的采购单
                for (PurchaseIn po : purchaseInMapper.selectList(new LambdaQueryWrapper<PurchaseIn>()
                        .eq(PurchaseIn::getPcId, pc.getId()).eq(PurchaseIn::getIsDeleted, 0))) {
                    if (p.getAcceptanceDate() != null) po.setAcceptanceDate(p.getAcceptanceDate());
                    if (p.getAuditPassDate() != null) po.setAuditPassDate(p.getAuditPassDate());
                    if (p.getWarrantyYears() != null) po.setWarrantyYears(p.getWarrantyYears());
                    if (p.getWarrantyEndDate() != null) po.setWarrantyEndDate(p.getWarrantyEndDate());
                    if (p.getActualWeighTons() != null) po.setWeighTons(p.getActualWeighTons());
                    purchaseInMapper.updateById(po);
                }
            }
        }

        // 2. 联动主工程套装 / 主设备台账
        if (p.getPrimaryAssetId() != null) {
            Asset a = assetMapper.selectById(p.getPrimaryAssetId());
            if (a != null && !Integer.valueOf(1).equals(a.getIsDeleted())) {
                if (p.getAcceptanceDate() != null) a.setWarrantyStartDate(p.getAcceptanceDate());
                if (p.getWarrantyYears() != null) a.setWarrantyYears(p.getWarrantyYears());
                if (p.getWarrantyEndDate() != null) a.setWarrantyEndDate(p.getWarrantyEndDate());
                if (p.getActualWeighTons() != null) a.setWeighTons(p.getActualWeighTons());
                if (p.getPalletSlots() != null) a.setPalletSlots(p.getPalletSlots());
                if (p.getWarehouseZone() != null) a.setWarehouseZone(p.getWarehouseZone());
                if (p.getDeliveryAddress() != null) a.setDeliveryAddress(p.getDeliveryAddress());
                if (p.getDeliveryContact() != null) a.setDeliveryContact(p.getDeliveryContact());
                assetMapper.updateById(a);
            }
        }

        auditLogService.record("登记项目交付与验收", "project", p.getId(), AuditLogService.EXECUTED,
                p.getProjectNo() + " 过磅=" + p.getActualWeighTons() + "t 验收=" + p.getAcceptanceDate()
                        + " 审核=" + p.getAuditPassDate() + " 质保至=" + p.getWarrantyEndDate());
        if (automationEventService != null) {
            automationEventService.onProjectAccepted(p);
        }
        return detail(id);
    }

    /** 一键按「工程套装」为本项目建一套资产台账（例如整套 2279 货位 45 吨重型货架只建 1 套，不拆单件）。 */
    @Transactional
    public Long createKitAsset(Long projectId, ProjectDtos.CreateKitAssetRequest req) {
        Project p = load(projectId);
        AssetSaveRequest ar = new AssetSaveRequest();
        ar.setCategory(req != null && req.getCategory() != null && !req.getCategory().trim().isEmpty()
                ? req.getCategory().trim() : "货架");
        ar.setAssetMode("工程套装");
        ar.setModel(req != null && req.getModel() != null && !req.getModel().trim().isEmpty()
                ? req.getModel().trim() : p.getName());
        BigDecimal price = req != null && req.getPurchasePrice() != null ? req.getPurchasePrice() : p.getQuotePrincipal();
        if (price == null && p.getPurchaseContractId() != null) {
            PurchaseContract pc = pcMapper.selectById(p.getPurchaseContractId());
            if (pc != null) price = pc.getTotalAmount();
        }
        ar.setPurchasePrice(price);
        ar.setMarketPrice(req != null && req.getMarketPrice() != null ? req.getMarketPrice() : price);
        ar.setSupplierId(p.getSupplierId());
        ar.setWarrantyYears(p.getWarrantyYears() != null ? p.getWarrantyYears() : 5);
        ar.setWarrantyStartDate(p.getAcceptanceDate());
        ar.setWarrantyEndDate(p.getWarrantyEndDate());
        ar.setDeliveryAddress(p.getDeliveryAddress());
        ar.setDeliveryContact(p.getDeliveryContact());
        ar.setWeighTons(req != null && req.getWeighTons() != null ? req.getWeighTons()
                : (p.getActualWeighTons() != null ? p.getActualWeighTons() : p.getPlannedTons()));
        ar.setPalletSlots(req != null && req.getPalletSlots() != null ? req.getPalletSlots() : p.getPalletSlots());
        ar.setWarehouseZone(req != null && req.getWarehouseZone() != null ? req.getWarehouseZone() : p.getWarehouseZone());
        ar.setRemark(req != null && req.getRemark() != null ? req.getRemark() : ("项目 " + p.getProjectNo() + " 工程套装"));

        Long assetId = assetService.create(ar);
        // 回填意向客户与项目归属
        assetMapper.update(null, new LambdaUpdateWrapper<Asset>()
                .eq(Asset::getId, assetId)
                .set(Asset::getProjectId, p.getId())
                .set(p.getCustomerId() != null, Asset::getIntendedCustomerId, p.getCustomerId()));

        p.setPrimaryAssetId(assetId);
        p.setAssetMode("工程套装");
        projectMapper.updateById(p);
        auditLogService.record("项目一键建工程套装", "project", p.getId(), AuditLogService.EXECUTED,
                p.getProjectNo() + " → 资产#" + assetId);
        return assetId;
    }

    /** 将现有尚未纳入项目中心的生效/存续租赁合同，一键同步生成项目卡片。 */
    @Transactional
    public ProjectDtos.SyncResult syncFromExistingContracts() {
        CurrentUser u = UserContext.require();
        List<Contract> contracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getIsDeleted, 0)
                .ne(Contract::getStatus, "已作废")
                .orderByAsc(Contract::getId));
        ProjectDtos.SyncResult res = new ProjectDtos.SyncResult();
        for (Contract c : contracts) {
            long exists = projectMapper.selectCount(new LambdaQueryWrapper<Project>()
                    .eq(Project::getRentContractId, c.getId())
                    .eq(Project::getIsDeleted, 0));
            if (exists > 0) {
                continue;
            }
            Customer cust = c.getCustomerId() == null ? null : customerMapper.selectById(c.getCustomerId());
            PurchaseContract pc = pcMapper.selectOne(new LambdaQueryWrapper<PurchaseContract>()
                    .eq(PurchaseContract::getRentContractId, c.getId())
                    .ne(PurchaseContract::getStatus, "作废")
                    .last("limit 1"));

            Project p = new Project();
            p.setProjectNo("PRJ-" + c.getNo());
            p.setName((cust != null ? cust.getName() + " · " : "") + c.getNo());
            p.setAssetMode("工程套装");
            p.setCustomerId(c.getCustomerId());
            p.setRentContractId(c.getId());
            if (pc != null) {
                p.setPurchaseContractId(pc.getId());
                p.setSupplierId(pc.getSupplierId());
                p.setWarrantyYears(pc.getWarrantyYears() != null ? pc.getWarrantyYears() : 5);
                p.setDeliveryAddress(pc.getDeliveryAddress());
                p.setDeliveryContact(pc.getDeliveryContact());
                p.setAcceptanceDate(pc.getAcceptanceDate());
                p.setAuditPassDate(pc.getAuditPassDate());
                p.setShippedDate(pc.getShippedDate());
                p.setInstallDoneDate(pc.getInstallDoneDate());
                p.setLoadTestDate(pc.getLoadTestDate());
                p.setActualWeighTons(pc.getWeighTons());
                p.setPalletSlots(pc.getPalletSlots());
                if (pc.getAcceptanceDate() != null && p.getWarrantyYears() != null) {
                    p.setWarrantyEndDate(pc.getAcceptanceDate().plusYears(p.getWarrantyYears()));
                }
            }
            p.setQuotePrincipal(c.getFinalDealPrice() != null ? c.getFinalDealPrice() : c.getEquipmentTotal());
            p.setQuoteAnnualRate(c.getLeaseAnnualRate());
            p.setQuoteTermMonths(c.getTermMonths());
            p.setQuoteMonthRent(c.getMonthRent());
            p.setQuoteBuyoutPrice(c.getEndTransferPrice());
            p.setStage(deriveStage(p));
            p.setOwnerUserId(u.getUserId());
            p.setCreateByName(u.getUserName());
            p.setIsDeleted(0);
            projectMapper.insert(p);
            res.getCreatedNos().add(p.getProjectNo());
        }
        res.setCreatedCount(res.getCreatedNos().size());
        return res;
    }

    // ============ 内部转换与 7 步进度推导 ============

    private void applySave(Project p, ProjectDtos.SaveRequest req) {
        p.setName(req.getName().trim());
        p.setAssetMode(req.getAssetMode() == null || req.getAssetMode().trim().isEmpty() ? "工程套装" : req.getAssetMode().trim());
        p.setCustomerId(req.getCustomerId());
        p.setSupplierId(req.getSupplierId());
        p.setRentContractId(req.getRentContractId());
        p.setPurchaseContractId(req.getPurchaseContractId());
        p.setPrimaryAssetId(req.getPrimaryAssetId());
        p.setPalletSlots(req.getPalletSlots());
        p.setPlannedTons(req.getPlannedTons());
        p.setWarehouseZone(req.getWarehouseZone());
        p.setDeliveryAddress(req.getDeliveryAddress());
        p.setDeliveryContact(req.getDeliveryContact());
        p.setWarrantyYears(req.getWarrantyYears() != null ? req.getWarrantyYears() : 5);
        p.setQuotePrincipal(req.getQuotePrincipal());
        p.setQuoteAnnualRate(req.getQuoteAnnualRate());
        p.setQuoteTermMonths(req.getQuoteTermMonths());
        p.setQuoteMonthRent(req.getQuoteMonthRent());
        p.setQuoteBuyoutPrice(req.getQuoteBuyoutPrice());
        p.setRemark(req.getRemark());
    }

    /** 双向绑定：项目绑了租赁合同 + 采购合同时，自动把采购合同的 rent_contract_id 对齐，防止漏绑导致利润统计不到。 */
    private void syncBidirectionalLinks(Project p) {
        if (p.getRentContractId() != null) {
            contractMapper.update(null, new LambdaUpdateWrapper<Contract>()
                    .eq(Contract::getId, p.getRentContractId())
                    .set(Contract::getProjectId, p.getId()));
        }
        if (p.getPurchaseContractId() != null && p.getRentContractId() != null) {
            PurchaseContract pc = pcMapper.selectById(p.getPurchaseContractId());
            if (pc != null && pc.getRentContractId() == null) {
                pc.setRentContractId(p.getRentContractId());
                pcMapper.updateById(pc);
            }
        }
    }

    private ProjectDtos.ListItem toListItem(Project p, Customer cust, boolean seeCost) {
        Contract rc = p.getRentContractId() == null ? null : contractMapper.selectById(p.getRentContractId());
        PurchaseContract pc = p.getPurchaseContractId() == null ? null : pcMapper.selectById(p.getPurchaseContractId());
        Supplier sup = p.getSupplierId() == null ? null : supplierMapper.selectById(p.getSupplierId());
        Asset asset = p.getPrimaryAssetId() == null ? null : assetMapper.selectById(p.getPrimaryAssetId());
        ProjectPanoramaDtos.Panorama pano = safePanorama(rc);

        List<ProjectDtos.StepInfo> steps = buildSteps(p, rc, pc, pano);
        int curStep = currentStepNo(steps);

        ProjectDtos.ListItem it = new ProjectDtos.ListItem();
        it.setId(p.getId());
        it.setProjectNo(p.getProjectNo());
        it.setName(p.getName());
        it.setAssetMode(p.getAssetMode());
        it.setStage(deriveStageFromSteps(steps));
        it.setCurrentStepNo(curStep);
        it.setCustomerId(p.getCustomerId());
        it.setCustomerName(cust != null ? cust.getName() : null);
        it.setSupplierId(p.getSupplierId());
        it.setSupplierName(sup != null ? sup.getName() : null);
        it.setRentContractId(p.getRentContractId());
        it.setRentContractNo(rc != null ? rc.getNo() : null);
        it.setPurchaseContractId(p.getPurchaseContractId());
        it.setPurchaseContractNo(pc != null ? pc.getContractNo() : null);
        it.setPrimaryAssetId(p.getPrimaryAssetId());
        it.setPrimaryAssetNo(asset != null ? asset.getSerialNo() : null);
        it.setPalletSlots(p.getPalletSlots());
        it.setPlannedTons(p.getPlannedTons());
        it.setActualWeighTons(p.getActualWeighTons());
        it.setWarehouseZone(p.getWarehouseZone());
        it.setAcceptanceDate(p.getAcceptanceDate());
        it.setWarrantyEndDate(p.getWarrantyEndDate());
        it.setCostVisible(seeCost);
        it.setSteps(steps);

        if (pano != null) {
            it.setReceivedRent(pano.getReceivedRent());
            if (seeCost) {
                it.setPurchaseTotal(pano.getPurchaseTotal());
                it.setActualNetCashflow(pano.getActualNetCashflow());
                it.setExpectedGrossProfit(pano.getExpectedGrossProfit());
                it.setPaybackProgress(pano.getPaybackProgress());
                it.setPaybackPeriodNo(pano.getPaybackPeriodNo());
            }
        } else if (seeCost && pc != null) {
            it.setPurchaseTotal(pc.getTotalAmount());
        } else if (seeCost && p.getQuotePrincipal() != null) {
            it.setPurchaseTotal(p.getQuotePrincipal());
        }
        return it;
    }

    private ProjectDtos.Detail toDetail(Project p, Customer cust, boolean seeCost) {
        Contract rc = p.getRentContractId() == null ? null : contractMapper.selectById(p.getRentContractId());
        PurchaseContract pc = p.getPurchaseContractId() == null ? null : pcMapper.selectById(p.getPurchaseContractId());
        Supplier sup = p.getSupplierId() == null ? null : supplierMapper.selectById(p.getSupplierId());
        Asset asset = p.getPrimaryAssetId() == null ? null : assetMapper.selectById(p.getPrimaryAssetId());
        ProjectPanoramaDtos.Panorama pano = safePanorama(rc);
        List<ProjectDtos.StepInfo> steps = buildSteps(p, rc, pc, pano);

        ProjectDtos.Detail d = new ProjectDtos.Detail();
        d.setId(p.getId());
        d.setProjectNo(p.getProjectNo());
        d.setName(p.getName());
        d.setAssetMode(p.getAssetMode());
        d.setStage(deriveStageFromSteps(steps));
        d.setCurrentStepNo(currentStepNo(steps));
        d.setCustomerId(p.getCustomerId());
        d.setCustomerName(cust != null ? cust.getName() : null);
        d.setSupplierId(p.getSupplierId());
        d.setSupplierName(sup != null ? sup.getName() : null);
        d.setRentContractId(p.getRentContractId());
        d.setRentContractNo(rc != null ? rc.getNo() : null);
        d.setRentContractStatus(rc != null ? rc.getStatus() : null);
        d.setPurchaseContractId(p.getPurchaseContractId());
        d.setPurchaseContractNo(pc != null ? pc.getContractNo() : null);
        d.setPurchaseContractStatus(pc != null ? pc.getStatus() : null);
        d.setPrimaryAssetId(p.getPrimaryAssetId());
        if (asset != null) {
            d.setPrimaryAssetNo(asset.getSerialNo());
            d.setPrimaryAssetLabel((asset.getAssetMode() != null ? "[" + asset.getAssetMode() + "] " : "")
                    + asset.getCategory() + (asset.getModel() != null ? " · " + asset.getModel() : ""));
        }
        d.setPalletSlots(p.getPalletSlots());
        d.setPlannedTons(p.getPlannedTons());
        d.setActualWeighTons(p.getActualWeighTons());
        if (p.getPlannedTons() != null && p.getPlannedTons().signum() > 0 && p.getActualWeighTons() != null) {
            BigDecimal diffPct = p.getActualWeighTons().subtract(p.getPlannedTons())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(p.getPlannedTons(), 2, RoundingMode.HALF_UP);
            d.setWeighDiffPct(diffPct);
            d.setWeighWithinTolerance(diffPct.abs().compareTo(WEIGH_TOLERANCE_PCT) <= 0);
        }
        d.setWarehouseZone(p.getWarehouseZone());
        d.setDeliveryAddress(p.getDeliveryAddress());
        d.setDeliveryContact(p.getDeliveryContact());
        d.setProductionStartDate(p.getProductionStartDate());
        d.setShippedDate(p.getShippedDate());
        d.setInstallDoneDate(p.getInstallDoneDate());
        d.setLoadTestDate(p.getLoadTestDate());
        d.setAcceptanceDate(p.getAcceptanceDate());
        d.setAuditPassDate(p.getAuditPassDate());
        d.setWarrantyYears(p.getWarrantyYears());
        d.setWarrantyEndDate(p.getWarrantyEndDate());
        d.setQuotePrincipal(seeCost ? p.getQuotePrincipal() : null);
        d.setQuoteAnnualRate(p.getQuoteAnnualRate());
        d.setQuoteTermMonths(p.getQuoteTermMonths());
        d.setQuoteMonthRent(p.getQuoteMonthRent());
        d.setQuoteBuyoutPrice(p.getQuoteBuyoutPrice());
        d.setRemark(p.getRemark());
        d.setCostVisible(seeCost);
        d.setSteps(steps);
        d.setPanorama(pano);
        return d;
    }

    private List<ProjectDtos.StepInfo> buildSteps(Project p, Contract rc, PurchaseContract pc,
                                                  ProjectPanoramaDtos.Panorama pano) {
        boolean s1Done = p.getQuotePrincipal() != null || rc != null || pc != null;
        boolean s2Done = rc != null && pc != null;
        boolean s2Active = !s2Done && (rc != null || pc != null);
        boolean s3Done = p.getShippedDate() != null || p.getInstallDoneDate() != null || p.getAcceptanceDate() != null;
        boolean s3Active = !s3Done && (pc != null || p.getProductionStartDate() != null);
        boolean s4Done = p.getAcceptanceDate() != null && (p.getAuditPassDate() != null || pc == null);
        boolean s4Active = !s4Done && (s3Done || p.getAcceptanceDate() != null || p.getLoadTestDate() != null);
        boolean closed = rc != null && ("到期转让".equals(rc.getStatus()) || "关闭".equals(rc.getStatus()));
        boolean s5Done = closed || (pano != null && pano.getTotalPeriods() > 0 && pano.getSettledPeriods() >= pano.getTotalPeriods());
        boolean s5Active = !s5Done && rc != null && "生效".equals(rc.getStatus());
        boolean s6Done = p.getWarrantyEndDate() != null && p.getWarrantyEndDate().isBefore(LocalDate.now());
        boolean s6Active = !s6Done && p.getWarrantyEndDate() != null;
        boolean s7Done = closed;

        List<ProjectDtos.StepInfo> out = new ArrayList<>();
        out.add(step(1, "quote", "① 报价测算", s1Done ? "done" : "active",
                p.getQuoteMonthRent() != null ? ("月租 ¥" + p.getQuoteMonthRent() + " × " + p.getQuoteTermMonths() + "期") : "录入测算方案",
                null));
        out.add(step(2, "contracts", "② 双合同落定", s2Done ? "done" : (s2Active ? "active" : "pending"),
                (rc != null ? "租:" + rc.getNo() : "待签租赁") + " / " + (pc != null ? "采:" + pc.getContractNo() : "待签采购"),
                rc != null && rc.getSignDate() != null ? rc.getSignDate().toString() : null));
        out.add(step(3, "production", "③ 排产与发货", s3Done ? "done" : (s3Active ? "active" : "pending"),
                p.getShippedDate() != null ? "已发货至客户仓" : (p.getProductionStartDate() != null ? "排产中" : "待排产"),
                p.getShippedDate() != null ? p.getShippedDate().toString()
                        : (p.getProductionStartDate() != null ? p.getProductionStartDate().toString() : null)));
        out.add(step(4, "acceptance", "④ 过磅安装验收", s4Done ? "done" : (s4Active ? "active" : "pending"),
                p.getAcceptanceDate() != null
                        ? ("已验收" + (p.getActualWeighTons() != null ? " · " + p.getActualWeighTons() + "吨" : ""))
                        : "待过磅/安装/三方验收",
                p.getAcceptanceDate() != null ? p.getAcceptanceDate().toString() : null));
        out.add(step(5, "rent", "⑤ 收租与开票", s5Done ? "done" : (s5Active ? "active" : "pending"),
                pano != null ? ("已结清 " + pano.getSettledPeriods() + "/" + pano.getTotalPeriods() + " 期") : "待起租收款",
                rc != null && rc.getStartDate() != null ? rc.getStartDate().toString() : null));
        out.add(step(6, "warranty", "⑥ 质保维保", s6Done ? "done" : (s6Active ? "active" : "pending"),
                p.getWarrantyEndDate() != null ? ("质保至 " + p.getWarrantyEndDate()) : "验收后起算质保",
                p.getWarrantyEndDate() != null ? p.getWarrantyEndDate().toString() : null));
        out.add(step(7, "close", "⑦ 留购结项", s7Done ? "done" : "pending",
                s7Done ? ("已" + rc.getStatus()) : "到期1元留购或提前买断",
                null));
        return out;
    }

    private ProjectDtos.StepInfo step(int no, String key, String title, String status, String summary, String dateText) {
        ProjectDtos.StepInfo s = new ProjectDtos.StepInfo();
        s.setStepNo(no);
        s.setKey(key);
        s.setTitle(title);
        s.setStatus(status);
        s.setSummary(summary);
        s.setDateText(dateText);
        return s;
    }

    private int currentStepNo(List<ProjectDtos.StepInfo> steps) {
        for (ProjectDtos.StepInfo s : steps) {
            if ("active".equals(s.getStatus())) {
                return s.getStepNo();
            }
        }
        int lastDone = 1;
        for (ProjectDtos.StepInfo s : steps) {
            if ("done".equals(s.getStatus())) {
                lastDone = s.getStepNo();
            }
        }
        return lastDone;
    }

    private String deriveStage(Project p) {
        Contract rc = p.getRentContractId() == null ? null : contractMapper.selectById(p.getRentContractId());
        PurchaseContract pc = p.getPurchaseContractId() == null ? null : pcMapper.selectById(p.getPurchaseContractId());
        return deriveStageFromSteps(buildSteps(p, rc, pc, null));
    }

    private String deriveStageFromSteps(List<ProjectDtos.StepInfo> steps) {
        if ("done".equals(steps.get(6).getStatus())) return "已结项";
        if ("active".equals(steps.get(4).getStatus()) || "done".equals(steps.get(4).getStatus())) return "收租运营";
        if ("active".equals(steps.get(3).getStatus()) || "done".equals(steps.get(3).getStatus())) return "交付验收";
        if ("active".equals(steps.get(2).getStatus()) || "done".equals(steps.get(2).getStatus())) return "采购排产";
        if ("active".equals(steps.get(1).getStatus()) || "done".equals(steps.get(1).getStatus())) return "合同落定";
        return "报价测算";
    }

    private ProjectPanoramaDtos.Panorama safePanorama(Contract rc) {
        if (rc == null) {
            return null;
        }
        try {
            return panoramaService.panorama(rc.getId());
        } catch (Exception e) {
            return null;
        }
    }

    private Project load(Long id) {
        Project p = projectMapper.selectById(id);
        if (p == null || Integer.valueOf(1).equals(p.getIsDeleted())) {
            throw new BizException(404, "项目不存在: id=" + id);
        }
        return p;
    }

    private String nextProjectNo() {
        int year = Year.now().getValue();
        Long cnt = projectMapper.selectCount(new LambdaQueryWrapper<Project>());
        long seq = (cnt == null ? 0L : cnt) + 1;
        return String.format("PRJ-%d-%04d", year, seq);
    }
}
