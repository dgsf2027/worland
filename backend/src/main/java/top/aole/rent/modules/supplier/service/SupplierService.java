package top.aole.rent.modules.supplier.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.domain.AssetBom;
import top.aole.rent.modules.asset.mapper.AssetBomMapper;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.maintenance.domain.Maintenance;
import top.aole.rent.modules.maintenance.mapper.MaintenanceMapper;
import top.aole.rent.modules.purchase.domain.PurchaseIn;
import top.aole.rent.modules.purchase.domain.PurchaseItem;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseItemMapper;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.domain.SupplierInspection;
import top.aole.rent.modules.supplier.domain.SupplierSupply;
import top.aole.rent.modules.supplier.dto.DependencyAlert;
import top.aole.rent.modules.supplier.dto.RetireRequest;
import top.aole.rent.modules.supplier.dto.SupplierDetailResponse;
import top.aole.rent.modules.supplier.dto.SupplierEditDtos;
import top.aole.rent.modules.supplier.dto.SupplierPoolItem;
import top.aole.rent.modules.supplier.dto.SupplierSaveRequest;
import top.aole.rent.modules.supplier.mapper.SupplierInspectionMapper;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;
import top.aole.rent.modules.supplier.mapper.SupplierSupplyMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 供应商模块服务(M1-01/02)。供应商池/详情/CRUD/淘汰留痕/单一依赖预警。
 *
 * <p><b>派生字段口径(§4.24)</b>:履约加权总分不落库,由 rule_config[supplier_score_weights] 即时算。
 * <b>字段级隔离(P0-E)</b>:集采价/首付/价格构成对不可见成本的角色(GP/LP)打码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierService {

    private final SupplierMapper supplierMapper;
    private final SupplierSupplyMapper supplyMapper;
    private final RuleConfigService rules;
    private final AuditLogService auditLogService;
    private final SupplierInspectionService inspectionService;
    private final SupplierInspectionMapper inspectionMapper;
    private final AssetMapper assetMapper;
    private final AssetBomMapper assetBomMapper;
    private final PurchaseInMapper purchaseInMapper;
    private final PurchaseItemMapper purchaseItemMapper;
    private final MaintenanceMapper maintenanceMapper;
    private final CustomerMapper customerMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 可用状态(计入单一依赖统计) */
    private static final Set<String> ACTIVE_STATUS = new HashSet<>(Arrays.asList("入库", "主供", "备供"));
    private static final Set<String> VALID_STATUS =
            new HashSet<>(Arrays.asList("接触", "试样", "入库", "主供", "备供", "淘汰"));

    // ============ 供应商池列表 ============

    public PageResult<SupplierPoolItem> pool(String keyword, String category, String status,
                                             Integer minScore, int page, int size) {
        boolean canSeeCost = DataScope.canSeeCost(currentRole());

        List<Supplier> suppliers = supplierMapper.selectList(new LambdaQueryWrapper<Supplier>()
                .eq(status != null && !status.isEmpty(), Supplier::getStatus, status)
                .orderByAsc(Supplier::getId));

        List<SupplierSupply> allSupplies = supplyMapper.selectList(new LambdaQueryWrapper<SupplierSupply>());
        Map<Long, List<SupplierSupply>> bySupplier = allSupplies.stream()
                .collect(Collectors.groupingBy(SupplierSupply::getSupplierId));
        Map<Long, Long> inspectionBySupplier = inspectionService.passedInspectionIdBySupplier();

        List<SupplierPoolItem> items = new ArrayList<>();
        for (Supplier s : suppliers) {
            List<SupplierSupply> supplies = bySupplier.getOrDefault(s.getId(), new ArrayList<>());
            SupplierSupply primary = pickPrimary(supplies);

            // 关键词:命中供应商名 或 任一供货项名
            if (keyword != null && !keyword.trim().isEmpty()) {
                String kw = keyword.trim();
                boolean hit = contains(s.getName(), kw)
                        || supplies.stream().anyMatch(sp -> contains(sp.getItemName(), kw));
                if (!hit) {
                    continue;
                }
            }
            // 品类:供货项任一命中
            if (category != null && !category.trim().isEmpty()) {
                boolean hit = supplies.stream().anyMatch(sp -> category.trim().equals(sp.getCategory()));
                if (!hit) {
                    continue;
                }
            }

            Integer scoreTotal = primary == null ? null : weightedTotal(primary);
            if (minScore != null && (scoreTotal == null || scoreTotal < minScore)) {
                continue;
            }

            SupplierPoolItem item = new SupplierPoolItem();
            item.setId(s.getId());
            item.setName(s.getName());
            item.setContact(s.getContact());
            item.setCompanyAccount(s.getCompanyAccount());
            item.setOpeningBank(s.getOpeningBank());
            item.setStatus(s.getStatus());
            item.setMainCategory(s.getMainCategory());
            if (primary != null) {
                item.setItemDesc(primary.getItemName() + "(" + primary.getItemType() + ")");
                item.setQuotePrice(canSeeCost ? primary.getQuotePrice() : null);
                item.setFirstPayRatio(canSeeCost ? primary.getFirstPayRatio() : null);
            }
            item.setScoreTotal(scoreTotal);
            item.setInspectionId(inspectionBySupplier.get(s.getId()));
            items.add(item);
        }

        long total = items.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(items.size(), from + size);
        List<SupplierPoolItem> pageRecords = from >= items.size() ? new ArrayList<>() : items.subList(from, to);
        return new PageResult<>(total, page, size, pageRecords);
    }

    // ============ 供应商详情 ============

    public SupplierDetailResponse detail(Long id) {
        Supplier s = supplierMapper.selectById(id);
        if (s == null || Integer.valueOf(1).equals(s.getIsDeleted())) {
            throw new BizException(404, "供应商不存在: id=" + id);
        }
        boolean canSeeCost = DataScope.canSeeCost(currentRole());

        List<SupplierSupply> supplies = supplyMapper.selectList(new LambdaQueryWrapper<SupplierSupply>()
                .eq(SupplierSupply::getSupplierId, id)
                .orderByDesc(SupplierSupply::getIsPrimary).orderByAsc(SupplierSupply::getId));
        SupplierSupply primary = pickPrimary(supplies);

        SupplierDetailResponse r = new SupplierDetailResponse();
        r.setId(s.getId());
        r.setName(s.getName());
        r.setContact(s.getContact());
        r.setPhone(s.getPhone());
        r.setCompanyAccount(s.getCompanyAccount());
        r.setOpeningBank(s.getOpeningBank());
        r.setStatus(s.getStatus());
        r.setMainCategory(s.getMainCategory());
        r.setRemark(s.getRemark());
        r.setCostMasked(!canSeeCost);
        r.setInspection(inspectionService.latestPassedFor(id));
        r.setPrimarySupplyId(primary == null ? null : primary.getId());
        r.setScoreWeights(scoreWeights());
        r.setLinkedAssets(linkedAssets(id, canSeeCost));

        // 履约雷达(任一维已评即展示)
        if (primary != null && hasAnyScore(primary)) {
            SupplierDetailResponse.ScoreRadar radar = new SupplierDetailResponse.ScoreRadar();
            radar.setQuality(primary.getScoreQuality());
            radar.setDelivery(primary.getScoreDelivery());
            radar.setService(primary.getScoreService());
            radar.setPrice(primary.getScorePrice());
            radar.setTerm(primary.getScoreTerm());
            radar.setTotal(weightedTotal(primary));
            r.setScoreRadar(radar);
        }

        // 供货矩阵
        List<SupplierDetailResponse.SupplyRow> matrix = new ArrayList<>();
        for (SupplierSupply sp : supplies) {
            SupplierDetailResponse.SupplyRow row = new SupplierDetailResponse.SupplyRow();
            row.setId(sp.getId());
            row.setItemType(sp.getItemType());
            row.setItemName(sp.getItemName());
            row.setCategory(sp.getCategory());
            row.setQuotePrice(canSeeCost ? sp.getQuotePrice() : null);
            row.setFirstPayRatio(canSeeCost ? sp.getFirstPayRatio() : null);
            row.setAccountDays(canSeeCost ? sp.getAccountDays() : null);
            row.setNoInterest(Integer.valueOf(1).equals(sp.getNoInterest()));
            row.setCanSingleBuy(Integer.valueOf(1).equals(sp.getCanSingleBuy()));
            row.setPrimary(primary != null && primary.getId().equals(sp.getId()));
            row.setRemark(sp.getRemark());
            row.setScoreTotal(weightedTotal(sp));
            matrix.add(row);
        }
        r.setSupplyMatrix(matrix);

        // 价格构成(vs BOM);仅可见成本角色返回,任一项已录即展示
        if (canSeeCost && primary != null && (primary.getCostMaterial() != null || primary.getCostProcessing() != null
                || primary.getProfitAmount() != null || primary.getBomEstimate() != null)) {
            SupplierDetailResponse.PriceComposition pc = new SupplierDetailResponse.PriceComposition();
            pc.setMaterial(primary.getCostMaterial());
            pc.setProcessing(primary.getCostProcessing());
            pc.setProfit(primary.getProfitAmount());
            pc.setQuote(primary.getQuotePrice());
            pc.setBomEstimate(primary.getBomEstimate());
            if (primary.getQuotePrice() != null && primary.getBomEstimate() != null) {
                boolean reasonable = primary.getQuotePrice().compareTo(
                        primary.getBomEstimate().multiply(new BigDecimal("1.10"))) <= 0;
                pc.setVerdict(reasonable ? "合理" : "偏高(疑虚高)");
            }
            r.setPriceComposition(pc);
        }
        return r;
    }

    // ============ 新增 / 编辑 ============

    @Transactional
    public Long create(SupplierSaveRequest req) {
        Supplier s = new Supplier();
        s.setName(req.getName().trim());
        s.setContact(req.getContact());
        s.setPhone(req.getPhone());
        s.setCompanyAccount(req.getCompanyAccount());
        s.setOpeningBank(req.getOpeningBank());
        s.setMainCategory(req.getMainCategory());
        s.setStatus(normalizeStatus(req.getStatus(), "接触"));
        s.setRemark(req.getRemark());
        supplierMapper.insert(s);
        saveSupplies(s.getId(), req.getSupplies(), false);
        return s.getId();
    }

    @Transactional
    public void update(Long id, SupplierSaveRequest req) {
        Supplier s = supplierMapper.selectById(id);
        if (s == null || Integer.valueOf(1).equals(s.getIsDeleted())) {
            throw new BizException(404, "供应商不存在: id=" + id);
        }
        s.setName(req.getName().trim());
        s.setContact(req.getContact());
        s.setPhone(req.getPhone());
        s.setCompanyAccount(req.getCompanyAccount());
        s.setOpeningBank(req.getOpeningBank());
        s.setMainCategory(req.getMainCategory());
        if (req.getStatus() != null && !req.getStatus().isEmpty()) {
            s.setStatus(normalizeStatus(req.getStatus(), s.getStatus()));
        }
        s.setRemark(req.getRemark());
        supplierMapper.updateById(s);
        if (req.getSupplies() != null) {
            // 全量覆盖供货矩阵
            supplyMapper.delete(new LambdaQueryWrapper<SupplierSupply>().eq(SupplierSupply::getSupplierId, id));
            saveSupplies(id, req.getSupplies(), true);
        }
    }

    private void saveSupplies(Long supplierId, List<SupplierSaveRequest.SupplyItem> supplies, boolean isUpdate) {
        if (supplies == null || supplies.isEmpty()) {
            return;
        }
        boolean anyPrimary = supplies.stream().anyMatch(x -> Integer.valueOf(1).equals(x.getIsPrimary()));
        for (int i = 0; i < supplies.size(); i++) {
            SupplierSaveRequest.SupplyItem it = supplies.get(i);
            SupplierSupply sp = new SupplierSupply();
            sp.setSupplierId(supplierId);
            sp.setItemType(it.getItemType() == null ? "整机" : it.getItemType());
            sp.setItemName(it.getItemName());
            sp.setCategory(it.getCategory());
            sp.setQuotePrice(it.getQuotePrice());
            sp.setFirstPayRatio(it.getFirstPayRatio());
            sp.setAccountDays(it.getAccountDays());
            sp.setNoInterest(it.getNoInterest() == null ? 1 : it.getNoInterest());
            sp.setCanSingleBuy(it.getCanSingleBuy() == null ? 1 : it.getCanSingleBuy());
            sp.setScoreQuality(it.getScoreQuality());
            sp.setScoreDelivery(it.getScoreDelivery());
            sp.setScoreService(it.getScoreService());
            sp.setScorePrice(it.getScorePrice());
            sp.setScoreTerm(it.getScoreTerm());
            sp.setCostMaterial(it.getCostMaterial());
            sp.setCostProcessing(it.getCostProcessing());
            sp.setProfitAmount(it.getProfitAmount());
            sp.setBomEstimate(it.getBomEstimate());
            // 无显式代表项则首行兜底为代表
            sp.setIsPrimary(Integer.valueOf(1).equals(it.getIsPrimary()) || (!anyPrimary && i == 0) ? 1 : 0);
            sp.setRemark(it.getRemark());
            supplyMapper.insert(sp);
        }
    }

    // ============ 淘汰留痕 ============

    @Transactional
    public void retire(Long id, RetireRequest req) {
        Supplier s = supplierMapper.selectById(id);
        if (s == null || Integer.valueOf(1).equals(s.getIsDeleted())) {
            throw new BizException(404, "供应商不存在: id=" + id);
        }
        if ("淘汰".equals(s.getStatus())) {
            throw new BizException(400, "供应商已是淘汰状态");
        }
        s.setStatus("淘汰");
        s.setRetireReason(req.getReason());
        s.setRetiredBy(UserContext.getUserId());
        s.setRetiredAt(LocalDateTime.now());
        supplierMapper.updateById(s);
        auditLogService.record("供应商淘汰", "supplier", id, AuditLogService.EXECUTED, req.getReason());
        log.info("供应商淘汰留痕: id={}, by={}, reason={}", id, s.getRetiredBy(), req.getReason());
    }

    // ============ 详情分块编辑 ============

    /** 履约评分(五维)写回代表供货项;无供货项时自动建一行代表项。 */
    @Transactional
    public void updateScores(Long supplierId, SupplierEditDtos.ScoreRequest req) {
        requireCostRole("编辑履约评分");
        SupplierSupply sp = ensurePrimary(load(supplierId));
        supplyMapper.update(null, new LambdaUpdateWrapper<SupplierSupply>()
                .eq(SupplierSupply::getId, sp.getId())
                .set(SupplierSupply::getScoreQuality, req.getQuality())
                .set(SupplierSupply::getScoreDelivery, req.getDelivery())
                .set(SupplierSupply::getScoreService, req.getService())
                .set(SupplierSupply::getScorePrice, req.getPrice())
                .set(SupplierSupply::getScoreTerm, req.getTerm()));
        auditLogService.record("供应商履约评分", "supplier", supplierId, AuditLogService.EXECUTED,
                "品质" + req.getQuality() + "/交期" + req.getDelivery() + "/服务" + req.getService()
                        + "/价格" + req.getPrice() + "/账期" + req.getTerm());
    }

    /** 价格构成(材料/加工/利润/报价/BOM 估算)写回代表供货项;无供货项时自动建一行代表项。 */
    @Transactional
    public void updatePriceComposition(Long supplierId, SupplierEditDtos.PriceRequest req) {
        requireCostRole("编辑价格构成");
        SupplierSupply sp = ensurePrimary(load(supplierId));
        supplyMapper.update(null, new LambdaUpdateWrapper<SupplierSupply>()
                .eq(SupplierSupply::getId, sp.getId())
                .set(SupplierSupply::getCostMaterial, req.getMaterial())
                .set(SupplierSupply::getCostProcessing, req.getProcessing())
                .set(SupplierSupply::getProfitAmount, req.getProfit())
                .set(SupplierSupply::getQuotePrice, req.getQuote())
                .set(SupplierSupply::getBomEstimate, req.getBomEstimate()));
    }

    @Transactional
    public Long addSupply(Long supplierId, SupplierEditDtos.SupplyRequest req) {
        requireCostRole("新增供货项");
        load(supplierId);
        SupplierSupply sp = new SupplierSupply();
        sp.setSupplierId(supplierId);
        applySupply(sp, req);
        boolean first = supplyMapper.selectCount(new LambdaQueryWrapper<SupplierSupply>()
                .eq(SupplierSupply::getSupplierId, supplierId)) == 0;
        sp.setIsPrimary(first || Boolean.TRUE.equals(req.getPrimary()) ? 1 : 0);
        supplyMapper.insert(sp);
        if (Integer.valueOf(1).equals(sp.getIsPrimary())) {
            clearOtherPrimary(supplierId, sp.getId());
        }
        return sp.getId();
    }

    @Transactional
    public void updateSupply(Long supplyId, SupplierEditDtos.SupplyRequest req) {
        requireCostRole("编辑供货项");
        SupplierSupply sp = loadSupply(supplyId);
        applySupply(sp, req);
        boolean makePrimary = Boolean.TRUE.equals(req.getPrimary());
        supplyMapper.update(null, new LambdaUpdateWrapper<SupplierSupply>()
                .eq(SupplierSupply::getId, supplyId)
                .set(SupplierSupply::getItemType, sp.getItemType())
                .set(SupplierSupply::getItemName, sp.getItemName())
                .set(SupplierSupply::getCategory, sp.getCategory())
                .set(SupplierSupply::getQuotePrice, sp.getQuotePrice())
                .set(SupplierSupply::getFirstPayRatio, sp.getFirstPayRatio())
                .set(SupplierSupply::getAccountDays, sp.getAccountDays())
                .set(SupplierSupply::getNoInterest, sp.getNoInterest())
                .set(SupplierSupply::getCanSingleBuy, sp.getCanSingleBuy())
                .set(SupplierSupply::getRemark, sp.getRemark())
                .set(makePrimary, SupplierSupply::getIsPrimary, 1));
        if (makePrimary) {
            clearOtherPrimary(sp.getSupplierId(), supplyId);
        }
    }

    @Transactional
    public void deleteSupply(Long supplyId) {
        requireCostRole("删除供货项");
        SupplierSupply sp = loadSupply(supplyId);
        supplyMapper.deleteById(supplyId);
        if (Integer.valueOf(1).equals(sp.getIsPrimary())) {
            // 删掉代表项后,剩余首行接任代表项
            supplyMapper.selectList(new LambdaQueryWrapper<SupplierSupply>()
                            .eq(SupplierSupply::getSupplierId, sp.getSupplierId())
                            .orderByAsc(SupplierSupply::getId)).stream().findFirst()
                    .ifPresent(next -> supplyMapper.update(null, new LambdaUpdateWrapper<SupplierSupply>()
                            .eq(SupplierSupply::getId, next.getId()).set(SupplierSupply::getIsPrimary, 1)));
        }
    }

    // ============ 删除供应商 ============

    /**
     * 逻辑删除供应商(连同供货矩阵)。被设备/清单项/采购/维保引用,或被合格考察关联时拒绝删除,
     * 避免产生悬空引用;这类供应商应走「淘汰」。
     */
    @Transactional
    public void delete(Long id) {
        Supplier s = load(id);
        List<String> refs = new ArrayList<>();
        count(refs, "设备", assetMapper.selectCount(new LambdaQueryWrapper<Asset>().eq(Asset::getSupplierId, id)));
        count(refs, "工程量清单项", assetBomMapper.selectCount(new LambdaQueryWrapper<AssetBom>().eq(AssetBom::getSupplierId, id)));
        count(refs, "采购入库单", purchaseInMapper.selectCount(new LambdaQueryWrapper<PurchaseIn>().eq(PurchaseIn::getSupplierId, id)));
        count(refs, "采购明细", purchaseItemMapper.selectCount(new LambdaQueryWrapper<PurchaseItem>().eq(PurchaseItem::getSupplierId, id)));
        count(refs, "维保工单", maintenanceMapper.selectCount(new LambdaQueryWrapper<Maintenance>().eq(Maintenance::getSupplierId, id)));
        count(refs, "合格考察记录", inspectionMapper.selectCount(new LambdaQueryWrapper<SupplierInspection>().eq(SupplierInspection::getSupplierId, id)));
        if (!refs.isEmpty()) {
            throw new BizException(400, "供应商「" + s.getName() + "」已被 " + String.join("、", refs)
                    + " 引用,不能删除;如不再合作请使用「淘汰」");
        }
        supplyMapper.delete(new LambdaQueryWrapper<SupplierSupply>().eq(SupplierSupply::getSupplierId, id));
        supplierMapper.deleteById(id);
        auditLogService.record("供应商删除", "supplier", id, AuditLogService.EXECUTED, s.getName());
        log.info("供应商删除: id={}, name={}, by={}", id, s.getName(), UserContext.getUserId());
    }

    private void count(List<String> refs, String label, Long n) {
        if (n != null && n > 0) {
            refs.add(n + " 条" + label);
        }
    }

    // ============ Excel 导入落库 ============

    /** 供应商表一行(null 字段 = 表内无此列,保留原值)。 */
    @lombok.Data
    public static class SupplierSheetRow {
        private int rowNum;
        private String name;
        private java.util.Set<String> present = new java.util.HashSet<>();
        private String contact;
        private String phone;
        private String companyAccount;
        private String openingBank;
        private String mainCategory;
        private String status;
        private String remark;
        private Integer quality;
        private Integer delivery;
        private Integer service;
        private Integer price;
        private Integer term;
        private List<String> warnings = new ArrayList<>();
    }

    /** 供货矩阵表一行。 */
    @lombok.Data
    public static class SupplySheetRow {
        private int rowNum;
        private String supplierName;
        private java.util.Set<String> present = new java.util.HashSet<>();
        private String itemType;
        private String itemName;
        private String category;
        private BigDecimal quotePrice;
        private BigDecimal firstPayRatio;
        private Integer accountDays;
        private Integer noInterest;
        private Integer canSingleBuy;
        private Integer primary;
        private BigDecimal material;
        private BigDecimal processing;
        private BigDecimal profit;
        private BigDecimal bomEstimate;
        private String remark;
        private List<String> warnings = new ArrayList<>();
    }

    /**
     * 按供应商名称(规范化)新增或更新;供货项按 供应商+供何物 新增或更新。表里没有的供应商/供货项不删除。
     * 某列在表头里不存在则保留原值;列存在但单元格为空则清空该字段。
     */
    @Transactional
    public SupplierEditDtos.ImportResult importSheets(List<SupplierSheetRow> suppliers, List<SupplySheetRow> supplies) {
        SupplierEditDtos.ImportResult res = new SupplierEditDtos.ImportResult();
        Map<String, Supplier> byName = new java.util.HashMap<>();
        for (Supplier s : supplierMapper.selectList(new LambdaQueryWrapper<Supplier>().orderByAsc(Supplier::getId))) {
            byName.putIfAbsent(SupplierInspectionService.normalizeName(s.getName()), s);
        }

        Set<String> seen = new HashSet<>();
        for (SupplierSheetRow sr : suppliers) {
            res.setSupplierRows(res.getSupplierRows() + 1);
            sr.getWarnings().forEach(w -> res.getMessages().add(new SupplierEditDtos.RowMessage("供应商", sr.getRowNum(), sr.getName(), "warn", w)));
            if (sr.getName() == null || sr.getName().trim().isEmpty()) {
                res.setSkipped(res.getSkipped() + 1);
                res.getMessages().add(new SupplierEditDtos.RowMessage("供应商", sr.getRowNum(), null, "warn", "供应商名称为空,已跳过"));
                continue;
            }
            String key = SupplierInspectionService.normalizeName(sr.getName());
            if (!seen.add(key)) {
                res.setSkipped(res.getSkipped() + 1);
                res.getMessages().add(new SupplierEditDtos.RowMessage("供应商", sr.getRowNum(), sr.getName(), "warn", "供应商名称在表内重复,已跳过"));
                continue;
            }
            try {
                Supplier s = byName.get(key);
                boolean isNew = s == null;
                if (isNew) {
                    s = new Supplier();
                    s.setName(sr.getName().trim());
                    s.setStatus("接触");
                    s.setProjectId(UserContext.getProjectId());
                }
                String beforeStatus = s.getStatus();
                if (sr.getPresent().contains("contact")) s.setContact(sr.getContact());
                if (sr.getPresent().contains("phone")) s.setPhone(sr.getPhone());
                if (sr.getPresent().contains("companyAccount")) s.setCompanyAccount(sr.getCompanyAccount());
                if (sr.getPresent().contains("openingBank")) s.setOpeningBank(sr.getOpeningBank());
                if (sr.getPresent().contains("mainCategory")) s.setMainCategory(sr.getMainCategory());
                if (sr.getPresent().contains("remark")) s.setRemark(sr.getRemark());
                if (sr.getPresent().contains("status") && sr.getStatus() != null) {
                    s.setStatus(normalizeStatus(sr.getStatus(), s.getStatus()));
                }
                boolean retiredNow = "淘汰".equals(s.getStatus()) && !"淘汰".equals(beforeStatus);
                if (retiredNow) {
                    s.setRetireReason("Excel导入置为淘汰");
                    s.setRetiredBy(UserContext.getUserId());
                    s.setRetiredAt(LocalDateTime.now());
                }
                if (isNew) {
                    supplierMapper.insert(s);
                    byName.put(key, s);
                    res.setSuppliersCreated(res.getSuppliersCreated() + 1);
                } else {
                    supplierMapper.update(null, new LambdaUpdateWrapper<Supplier>()
                            .eq(Supplier::getId, s.getId())
                            .set(Supplier::getContact, s.getContact())
                            .set(Supplier::getPhone, s.getPhone())
                            .set(Supplier::getCompanyAccount, s.getCompanyAccount())
                            .set(Supplier::getOpeningBank, s.getOpeningBank())
                            .set(Supplier::getMainCategory, s.getMainCategory())
                            .set(Supplier::getRemark, s.getRemark())
                            .set(Supplier::getStatus, s.getStatus())
                            .set(retiredNow, Supplier::getRetireReason, s.getRetireReason())
                            .set(retiredNow, Supplier::getRetiredBy, s.getRetiredBy())
                            .set(retiredNow, Supplier::getRetiredAt, s.getRetiredAt()));
                    res.setSuppliersUpdated(res.getSuppliersUpdated() + 1);
                }
                if (retiredNow) {
                    auditLogService.record("供应商淘汰", "supplier", s.getId(), AuditLogService.EXECUTED, "Excel导入置为淘汰");
                }
                if (sr.getPresent().contains("scores")) {
                    SupplierSupply sp = ensurePrimary(s);
                    supplyMapper.update(null, new LambdaUpdateWrapper<SupplierSupply>()
                            .eq(SupplierSupply::getId, sp.getId())
                            .set(SupplierSupply::getScoreQuality, sr.getQuality())
                            .set(SupplierSupply::getScoreDelivery, sr.getDelivery())
                            .set(SupplierSupply::getScoreService, sr.getService())
                            .set(SupplierSupply::getScorePrice, sr.getPrice())
                            .set(SupplierSupply::getScoreTerm, sr.getTerm()));
                }
            } catch (BizException e) {
                res.setSkipped(res.getSkipped() + 1);
                res.getMessages().add(new SupplierEditDtos.RowMessage("供应商", sr.getRowNum(), sr.getName(), "warn", "未导入:" + e.getMessage()));
            }
        }

        for (SupplySheetRow sr : supplies) {
            res.setSupplyRows(res.getSupplyRows() + 1);
            sr.getWarnings().forEach(w -> res.getMessages().add(new SupplierEditDtos.RowMessage("供货矩阵", sr.getRowNum(), sr.getSupplierName(), "warn", w)));
            if (sr.getSupplierName() == null || sr.getItemName() == null) {
                res.setSkipped(res.getSkipped() + 1);
                res.getMessages().add(new SupplierEditDtos.RowMessage("供货矩阵", sr.getRowNum(), sr.getSupplierName(), "warn", "供应商名称或供何物为空,已跳过"));
                continue;
            }
            Supplier s = byName.get(SupplierInspectionService.normalizeName(sr.getSupplierName()));
            if (s == null) {
                res.setSkipped(res.getSkipped() + 1);
                res.getMessages().add(new SupplierEditDtos.RowMessage("供货矩阵", sr.getRowNum(), sr.getSupplierName(), "warn",
                        "系统和「供应商」表里都没有这家供应商,已跳过"));
                continue;
            }
            String itemKey = SupplierInspectionService.normalizeName(sr.getItemName());
            List<SupplierSupply> existing = supplyMapper.selectList(new LambdaQueryWrapper<SupplierSupply>()
                    .eq(SupplierSupply::getSupplierId, s.getId()).orderByAsc(SupplierSupply::getId));
            SupplierSupply sp = existing.stream()
                    .filter(x -> itemKey.equals(SupplierInspectionService.normalizeName(x.getItemName())))
                    .findFirst().orElse(null);
            boolean isNew = sp == null;
            if (isNew) {
                sp = new SupplierSupply();
                sp.setSupplierId(s.getId());
                sp.setItemName(sr.getItemName().trim());
                sp.setItemType("整机");
                sp.setNoInterest(1);
                sp.setCanSingleBuy(1);
                sp.setIsPrimary(existing.isEmpty() ? 1 : 0);
            }
            if (sr.getPresent().contains("itemType") && sr.getItemType() != null) sp.setItemType(sr.getItemType());
            if (sr.getPresent().contains("category")) sp.setCategory(sr.getCategory());
            if (sr.getPresent().contains("quotePrice")) sp.setQuotePrice(sr.getQuotePrice());
            if (sr.getPresent().contains("firstPayRatio")) sp.setFirstPayRatio(sr.getFirstPayRatio());
            if (sr.getPresent().contains("accountDays")) sp.setAccountDays(sr.getAccountDays());
            if (sr.getPresent().contains("noInterest") && sr.getNoInterest() != null) sp.setNoInterest(sr.getNoInterest());
            if (sr.getPresent().contains("canSingleBuy") && sr.getCanSingleBuy() != null) sp.setCanSingleBuy(sr.getCanSingleBuy());
            if (sr.getPresent().contains("material")) sp.setCostMaterial(sr.getMaterial());
            if (sr.getPresent().contains("processing")) sp.setCostProcessing(sr.getProcessing());
            if (sr.getPresent().contains("profit")) sp.setProfitAmount(sr.getProfit());
            if (sr.getPresent().contains("bomEstimate")) sp.setBomEstimate(sr.getBomEstimate());
            if (sr.getPresent().contains("remark")) sp.setRemark(sr.getRemark());
            boolean makePrimary = Integer.valueOf(1).equals(sr.getPrimary());
            if (makePrimary) {
                sp.setIsPrimary(1);
            }
            if (isNew) {
                supplyMapper.insert(sp);
                res.setSuppliesCreated(res.getSuppliesCreated() + 1);
            } else {
                supplyMapper.update(null, new LambdaUpdateWrapper<SupplierSupply>()
                        .eq(SupplierSupply::getId, sp.getId())
                        .set(SupplierSupply::getItemType, sp.getItemType())
                        .set(SupplierSupply::getCategory, sp.getCategory())
                        .set(SupplierSupply::getQuotePrice, sp.getQuotePrice())
                        .set(SupplierSupply::getFirstPayRatio, sp.getFirstPayRatio())
                        .set(SupplierSupply::getAccountDays, sp.getAccountDays())
                        .set(SupplierSupply::getNoInterest, sp.getNoInterest())
                        .set(SupplierSupply::getCanSingleBuy, sp.getCanSingleBuy())
                        .set(SupplierSupply::getCostMaterial, sp.getCostMaterial())
                        .set(SupplierSupply::getCostProcessing, sp.getCostProcessing())
                        .set(SupplierSupply::getProfitAmount, sp.getProfitAmount())
                        .set(SupplierSupply::getBomEstimate, sp.getBomEstimate())
                        .set(SupplierSupply::getRemark, sp.getRemark())
                        .set(SupplierSupply::getIsPrimary, sp.getIsPrimary()));
                res.setSuppliesUpdated(res.getSuppliesUpdated() + 1);
            }
            if (makePrimary) {
                clearOtherPrimary(s.getId(), sp.getId());
            }
        }
        auditLogService.record("供应商导入", "supplier", null, AuditLogService.EXECUTED,
                "供应商" + res.getSupplierRows() + "行(新增" + res.getSuppliersCreated() + "/更新" + res.getSuppliersUpdated()
                        + ") 供货矩阵" + res.getSupplyRows() + "行(新增" + res.getSuppliesCreated() + "/更新" + res.getSuppliesUpdated()
                        + ") 跳过" + res.getSkipped());
        return res;
    }

    // ============ 导出数据 ============

    /** 导出用:全部供应商(按 id)及其供货项。 */
    public List<Supplier> allSuppliers() {
        return supplierMapper.selectList(new LambdaQueryWrapper<Supplier>().orderByAsc(Supplier::getId));
    }

    public Map<Long, List<SupplierSupply>> suppliesBySupplier() {
        return supplyMapper.selectList(new LambdaQueryWrapper<SupplierSupply>()
                        .orderByDesc(SupplierSupply::getIsPrimary).orderByAsc(SupplierSupply::getId))
                .stream().collect(Collectors.groupingBy(SupplierSupply::getSupplierId, LinkedHashMap::new, Collectors.toList()));
    }

    public SupplierSupply primaryOf(List<SupplierSupply> supplies) {
        return pickPrimary(supplies);
    }

    public boolean canSeeCostNow() {
        return DataScope.canSeeCost(currentRole());
    }

    // ============ 单一依赖预警 ============

    public DependencyAlert dependencyAlert() {
        int min = rules.getValue("supplier_min_per_category", LocalDate.now()).intValue();

        List<Supplier> suppliers = supplierMapper.selectList(new LambdaQueryWrapper<Supplier>()
                .in(Supplier::getStatus, ACTIVE_STATUS));
        Set<Long> activeIds = suppliers.stream().map(Supplier::getId).collect(Collectors.toSet());
        List<SupplierSupply> supplies = supplyMapper.selectList(new LambdaQueryWrapper<SupplierSupply>());

        // 品类 → 可用供应商去重集
        Map<String, Set<Long>> catToSuppliers = new LinkedHashMap<>();
        for (SupplierSupply sp : supplies) {
            if (sp.getCategory() == null || !activeIds.contains(sp.getSupplierId())) {
                continue;
            }
            catToSuppliers.computeIfAbsent(sp.getCategory(), k -> new HashSet<>()).add(sp.getSupplierId());
        }
        // 也纳入 main_category 有值但暂无 active 供货项的品类(暴露 0 家)
        for (Supplier s : supplierMapper.selectList(new LambdaQueryWrapper<>())) {
            if (s.getMainCategory() != null && !catToSuppliers.containsKey(s.getMainCategory())) {
                catToSuppliers.putIfAbsent(s.getMainCategory(), new HashSet<>());
            }
        }

        List<DependencyAlert.CategoryRisk> risks = new ArrayList<>();
        for (Map.Entry<String, Set<Long>> e : catToSuppliers.entrySet()) {
            int cnt = e.getValue().size();
            if (cnt < min) {
                DependencyAlert.CategoryRisk risk = new DependencyAlert.CategoryRisk();
                risk.setCategory(e.getKey());
                risk.setAvailable(cnt);
                risk.setMessage("品类「" + e.getKey() + "」当前仅 " + cnt + " 家可用(入库/主供/备供),需再开发 ≥" + (min - cnt) + " 家防单一依赖");
                risks.add(risk);
            }
        }
        DependencyAlert alert = new DependencyAlert();
        alert.setMinPerCategory(min);
        alert.setRisks(risks);
        return alert;
    }

    // ============ 内部工具 ============

    private Supplier load(Long id) {
        Supplier s = id == null ? null : supplierMapper.selectById(id);
        if (s == null || Integer.valueOf(1).equals(s.getIsDeleted())) {
            throw new BizException(404, "供应商不存在: id=" + id);
        }
        return s;
    }

    private SupplierSupply loadSupply(Long supplyId) {
        SupplierSupply sp = supplyId == null ? null : supplyMapper.selectById(supplyId);
        if (sp == null || Integer.valueOf(1).equals(sp.getIsDeleted())) {
            throw new BizException(404, "供货项不存在: id=" + supplyId);
        }
        return sp;
    }

    /** 取代表供货项;供应商还没有任何供货项时,按主营品类建一行代表项。 */
    private SupplierSupply ensurePrimary(Supplier s) {
        SupplierSupply primary = pickPrimary(supplyMapper.selectList(new LambdaQueryWrapper<SupplierSupply>()
                .eq(SupplierSupply::getSupplierId, s.getId())
                .orderByDesc(SupplierSupply::getIsPrimary).orderByAsc(SupplierSupply::getId)));
        if (primary != null) {
            return primary;
        }
        SupplierSupply sp = new SupplierSupply();
        sp.setSupplierId(s.getId());
        boolean part = "配件".equals(s.getMainCategory());
        sp.setItemType(part ? "配件" : "整机");
        sp.setItemName(s.getMainCategory() == null ? "代表供货项" : s.getMainCategory() + (part ? "" : " 整机"));
        sp.setCategory(s.getMainCategory());
        sp.setNoInterest(1);
        sp.setCanSingleBuy(1);
        sp.setIsPrimary(1);
        supplyMapper.insert(sp);
        return sp;
    }

    private void clearOtherPrimary(Long supplierId, Long keepId) {
        supplyMapper.update(null, new LambdaUpdateWrapper<SupplierSupply>()
                .eq(SupplierSupply::getSupplierId, supplierId)
                .ne(SupplierSupply::getId, keepId)
                .set(SupplierSupply::getIsPrimary, 0));
    }

    private void applySupply(SupplierSupply sp, SupplierEditDtos.SupplyRequest req) {
        String type = req.getItemType() == null ? "整机" : req.getItemType().trim();
        if (!"整机".equals(type) && !"配件".equals(type)) {
            throw new BizException(400, "供货类型应为 整机 或 配件");
        }
        sp.setItemType(type);
        sp.setItemName(req.getItemName().trim());
        sp.setCategory(req.getCategory() == null || req.getCategory().trim().isEmpty() ? null : req.getCategory().trim());
        sp.setQuotePrice(req.getQuotePrice());
        sp.setFirstPayRatio(req.getFirstPayRatio());
        sp.setAccountDays(req.getAccountDays());
        sp.setNoInterest(req.getNoInterest() == null || req.getNoInterest() ? 1 : 0);
        sp.setCanSingleBuy(req.getCanSingleBuy() == null || req.getCanSingleBuy() ? 1 : 0);
        sp.setRemark(req.getRemark());
    }

    private void requireCostRole(String action) {
        if (!DataScope.canSeeCost(currentRole())) {
            throw new BizException(403, "当前角色无权" + action);
        }
    }

    private boolean hasAnyScore(SupplierSupply sp) {
        return sp.getScoreQuality() != null || sp.getScoreDelivery() != null || sp.getScoreService() != null
                || sp.getScorePrice() != null || sp.getScoreTerm() != null;
    }

    public Map<String, Double> scoreWeights() {
        JsonNode w = readJson("supplier_score_weights");
        Map<String, Double> out = new LinkedHashMap<>();
        for (String k : Arrays.asList("quality", "delivery", "service", "price", "term")) {
            out.put(k, w.path(k).asDouble());
        }
        return out;
    }

    /** 设备租赁台账里供应商为本供应商的设备(集采价按角色打码)。 */
    private List<SupplierDetailResponse.LinkedAsset> linkedAssets(Long supplierId, boolean canSeeCost) {
        List<Asset> assets = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getSupplierId, supplierId).orderByAsc(Asset::getId));
        Set<Long> customerIds = assets.stream().map(Asset::getCurrentHolderCustomerId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> customerNames = customerIds.isEmpty() ? new java.util.HashMap<>()
                : customerMapper.selectBatchIds(customerIds).stream()
                .collect(Collectors.toMap(Customer::getId, Customer::getName));
        List<SupplierDetailResponse.LinkedAsset> out = new ArrayList<>();
        for (Asset a : assets) {
            SupplierDetailResponse.LinkedAsset la = new SupplierDetailResponse.LinkedAsset();
            la.setId(a.getId());
            la.setSerialNo(a.getSerialNo());
            la.setCategory(a.getCategory());
            la.setModel(a.getModel());
            la.setStatus(a.getStatus());
            la.setPurchasePrice(canSeeCost ? a.getPurchasePrice() : null);
            la.setCurrentHolderName(a.getCurrentHolderCustomerId() == null ? null : customerNames.get(a.getCurrentHolderCustomerId()));
            out.add(la);
        }
        return out;
    }

    /** 履约加权总分(即时算):Σ 维度分×权重,权重来自 rule_config。五维全未评 → null;未评的维按 0 计。 */
    public Integer weightedTotal(SupplierSupply sp) {
        if (!hasAnyScore(sp)) {
            return null;
        }
        JsonNode w = readJson("supplier_score_weights");
        double total = nz(sp.getScoreQuality()) * w.path("quality").asDouble()
                + nz(sp.getScoreDelivery()) * w.path("delivery").asDouble()
                + nz(sp.getScoreService()) * w.path("service").asDouble()
                + nz(sp.getScorePrice()) * w.path("price").asDouble()
                + nz(sp.getScoreTerm()) * w.path("term").asDouble();
        return (int) Math.round(total);
    }

    private JsonNode readJson(String ruleKey) {
        try {
            return objectMapper.readTree(rules.getJson(ruleKey, "", LocalDate.now()));
        } catch (Exception e) {
            throw new BizException(500, "规则 JSON 解析失败: " + ruleKey + " · " + e.getMessage());
        }
    }

    private SupplierSupply pickPrimary(List<SupplierSupply> supplies) {
        if (supplies == null || supplies.isEmpty()) {
            return null;
        }
        return supplies.stream()
                .filter(x -> Integer.valueOf(1).equals(x.getIsPrimary()))
                .findFirst().orElse(supplies.get(0));
    }

    private String normalizeStatus(String status, String fallback) {
        if (status == null || status.trim().isEmpty()) {
            return fallback;
        }
        String t = status.trim();
        if (!VALID_STATUS.contains(t)) {
            throw new BizException(400, "非法状态: " + t + ",应为 接触/试样/入库/主供/备供/淘汰");
        }
        return t;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private boolean contains(String s, String kw) {
        return s != null && s.contains(kw);
    }

    private String currentRole() {
        return UserContext.get() == null ? null : UserContext.get().getRole();
    }
}
