package top.aole.rent.modules.supplier.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.domain.SupplierSupply;
import top.aole.rent.modules.supplier.dto.DependencyAlert;
import top.aole.rent.modules.supplier.dto.RetireRequest;
import top.aole.rent.modules.supplier.dto.SupplierDetailResponse;
import top.aole.rent.modules.supplier.dto.SupplierPoolItem;
import top.aole.rent.modules.supplier.dto.SupplierSaveRequest;
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
            item.setStatus(s.getStatus());
            item.setMainCategory(s.getMainCategory());
            if (primary != null) {
                item.setItemDesc(primary.getItemName() + "(" + primary.getItemType() + ")");
                item.setQuotePrice(canSeeCost ? primary.getQuotePrice() : null);
                item.setFirstPayRatio(canSeeCost ? primary.getFirstPayRatio() : null);
            }
            item.setScoreTotal(scoreTotal);
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
        r.setStatus(s.getStatus());
        r.setMainCategory(s.getMainCategory());
        r.setRemark(s.getRemark());
        r.setCostMasked(!canSeeCost);

        // 履约雷达
        if (primary != null && primary.getScoreQuality() != null) {
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
            row.setCanSingleBuy(Integer.valueOf(1).equals(sp.getCanSingleBuy()));
            row.setScoreTotal(sp.getScoreQuality() != null ? weightedTotal(sp) : null);
            matrix.add(row);
        }
        r.setSupplyMatrix(matrix);

        // 价格构成(vs BOM);仅可见成本角色返回
        if (canSeeCost && primary != null && primary.getCostMaterial() != null) {
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
        log.info("供应商淘汰留痕: id={}, by={}, reason={}", id, s.getRetiredBy(), req.getReason());
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

    /** 履约加权总分(即时算):Σ 维度分×权重,权重来自 rule_config。缺任一维度视为该项未评。 */
    private Integer weightedTotal(SupplierSupply sp) {
        if (sp.getScoreQuality() == null) {
            return null;
        }
        JsonNode w = readJson("supplier_score_weights");
        double total = sp.getScoreQuality() * w.path("quality").asDouble()
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
