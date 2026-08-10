package top.aole.rent.modules.analytics.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.analytics.dto.CashflowDtos;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 回报四源归因(M3-09 · P1-6)。把总税后 IRR 拆成四源:
 * 集采差价 X% + 资金时间价值 Y% + 价值定价 Z% + 残值回收 W%(四者之和 = 总 IRR,可勾稽)。
 *
 * <p><b>术语区分</b>:此处「四源」= 利润来源分解(方案书§8.4);区别于「三层杠杆」=资金结构
 * (本金/供应商账期/融资,见现金流驾驶舱 leverage)。<b>四源之和 = 总 IRR</b>(权重 ∑=1,末源兜尾差精确勾稽)。
 * <p>权重走 rule_config {@code return_attribution_weights}(§4.24 禁硬编码·可按真实业务校准);
 * 总 IRR 缺省取 rule_config {@code target_irr}(按客户类型),亦可入参覆盖。
 */
@Service
@RequiredArgsConstructor
public class ReturnAttributionService {

    private final RuleConfigService rules;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 四源标签(展示名·顺序固定)。 */
    private static final Map<String, String> LABELS = new LinkedHashMap<String, String>() {{
        put("procurementSpread", "集采差价");
        put("timeValue", "资金时间价值");
        put("valuePricing", "价值定价");
        put("residual", "残值回收");
    }};
    private static final Map<String, String> SOURCES = new LinkedHashMap<String, String>() {{
        put("procurementSpread", "集采成本比 purchase_cost_ratio(集采较自购低约10%·方案书§8.4)");
        put("timeValue", "三层杠杆 financing_cost/supplier_leverage(账期无息+融资放大·§4.2)");
        put("valuePricing", "价值定价溢价(替代人工价值·payback≤18月准入·§三)");
        put("residual", "期末转让 transfer_rate(播种墙10%/货架30%·§十)");
    }};

    public CashflowDtos.ReturnAttribution attribution(BigDecimal totalIrrParam, String customerType) {
        LocalDate now = LocalDate.now();
        String custType = customerType != null && !customerType.isEmpty() ? customerType : "其他";
        BigDecimal totalIrr = totalIrrParam != null ? totalIrrParam : targetIrr(custType, now);

        JsonNode weights = weights(now);
        // 归一化保护:权重和若非 1,按比例归一(仍保证四源之和 = 总 IRR)
        BigDecimal weightSum = BigDecimal.ZERO;
        for (String k : LABELS.keySet()) {
            weightSum = weightSum.add(weightOf(weights, k));
        }
        if (weightSum.signum() <= 0) {
            throw new BizException(500, "回报四源权重和非法(≤0)");
        }

        List<CashflowDtos.AttributionSource> sources = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        List<String> keys = new ArrayList<>(LABELS.keySet());
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            BigDecimal w = weightOf(weights, k).divide(weightSum, 8, RoundingMode.HALF_UP);
            BigDecimal contribution;
            if (i == keys.size() - 1) {
                contribution = totalIrr.subtract(allocated);   // 末源兜尾差 → 精确勾稽 ∑=totalIrr
            } else {
                contribution = totalIrr.multiply(w).setScale(6, RoundingMode.HALF_UP);
                allocated = allocated.add(contribution);
            }
            CashflowDtos.AttributionSource s = new CashflowDtos.AttributionSource();
            s.setKey(k);
            s.setLabel(LABELS.get(k));
            s.setWeight(w.setScale(4, RoundingMode.HALF_UP));
            s.setIrrContribution(contribution.setScale(6, RoundingMode.HALF_UP));
            s.setSource(SOURCES.get(k));
            sources.add(s);
        }

        BigDecimal sumCheck = BigDecimal.ZERO;
        for (CashflowDtos.AttributionSource s : sources) {
            sumCheck = sumCheck.add(s.getIrrContribution());
        }

        CashflowDtos.ReturnAttribution ra = new CashflowDtos.ReturnAttribution();
        ra.setCustomerType(custType);
        ra.setTotalIrr(totalIrr.setScale(6, RoundingMode.HALF_UP));
        ra.setSources(sources);
        ra.setSumCheck(sumCheck.setScale(6, RoundingMode.HALF_UP));
        ra.setReconciled(sumCheck.compareTo(totalIrr.setScale(6, RoundingMode.HALF_UP)) == 0);
        ra.setNote("四源之和 = 总税后 IRR(权重∑=1·末源兜尾差勾稽);四源=利润来源,区别于三层杠杆=资金结构");
        return ra;
    }

    private BigDecimal targetIrr(String customerType, LocalDate now) {
        try {
            return rules.getValue("target_irr", customerType, now);
        } catch (Exception e) {
            return rules.getValue("target_irr", "其他", now);   // 兜底默认客户类型
        }
    }

    private JsonNode weights(LocalDate now) {
        try {
            return JSON.readTree(rules.getJson("return_attribution_weights", "", now));
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            throw new BizException(500, "回报四源权重 return_attribution_weights 解析失败: " + e.getMessage());
        }
    }

    private BigDecimal weightOf(JsonNode weights, String key) {
        JsonNode n = weights.get(key);
        return n != null ? n.decimalValue() : BigDecimal.ZERO;
    }
}
