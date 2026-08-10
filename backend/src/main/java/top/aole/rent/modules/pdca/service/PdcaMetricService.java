package top.aole.rent.modules.pdca.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.rent.modules.bi.service.BiService;
import top.aole.rent.modules.pdca.domain.ActionItem;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * PDCA 指标注册表(M5-05)。<b>单一真相源(§4.24)</b>:看板红绿灯与到期回查<b>共用同一取数函数</b>,
 * 取值全部复用 {@link BiService}(不重算);红绿灯阈值/方向来自 rule_config(禁硬编码 §4.24)。
 *
 * <p>五指标:在租率 / 加权回报 / 应收账龄 / 资产周转 / 回款率。取不到值(null)= 需人工判定。
 */
@Service
@RequiredArgsConstructor
public class PdcaMetricService {

    private final BiService biService;
    private final RuleConfigService ruleConfigService;

    /** 指标定义:键 → (中文名, 单位, 越高越好?, rule scope, 取数函数) */
    public static class MetricDef {
        public final String key;
        public final String label;
        public final String scene;
        public final String unit;
        public final String compareOp;
        public final String ruleScope;
        public final Supplier<BigDecimal> valueFn;

        MetricDef(String key, String label, String scene, String unit, String compareOp,
                  String ruleScope, Supplier<BigDecimal> valueFn) {
            this.key = key;
            this.label = label;
            this.scene = scene;
            this.unit = unit;
            this.compareOp = compareOp;
            this.ruleScope = ruleScope;
            this.valueFn = valueFn;
        }
    }

    private Map<String, MetricDef> registry() {
        Map<String, MetricDef> m = new LinkedHashMap<>();
        // 值口径与看板阈值统一:比率类用 0-1(阈值 rule 也是 0-1);账龄用天数
        m.put("occupancy_rate", new MetricDef("occupancy_rate", "在租率", "在租率", "%",
                ActionItem.OP_GE, "occupancy_rate", () -> biService.occupancyRate().movePointLeft(2)));
        m.put("weighted_return", new MetricDef("weighted_return", "加权回报", "加权回报", "%",
                ActionItem.OP_GE, "weighted_return", biService::weightedReturnRate));
        m.put("receivable_aging", new MetricDef("receivable_aging", "应收账龄", "应收账龄", "天",
                ActionItem.OP_LE, "receivable_aging", biService::receivableAgingDays));
        m.put("asset_turnover", new MetricDef("asset_turnover", "资产周转(投放率)", "资产周转", "%",
                ActionItem.OP_GE, "asset_turnover", biService::assetTurnoverRate));
        m.put("collect_rate", new MetricDef("collect_rate", "回款率", "回款率", "%",
                ActionItem.OP_GE, "collect_rate", biService::collectRate));
        return m;
    }

    public List<MetricDef> defs() {
        return new ArrayList<>(registry().values());
    }

    public MetricDef def(String key) {
        return registry().get(key);
    }

    /** 指标当前值(null = 取不到 = 需人工判定) */
    public BigDecimal value(String key) {
        MetricDef def = def(key);
        if (def == null) {
            return null;
        }
        try {
            return def.valueFn.get();
        } catch (Exception e) {
            return null;
        }
    }

    /** 阈值(rule_config·禁硬编码);缺失返回 null */
    public BigDecimal threshold(String key) {
        MetricDef def = def(key);
        if (def == null) {
            return null;
        }
        try {
            return ruleConfigService.getValue("pdca_threshold", def.ruleScope, LocalDate.now());
        } catch (Exception e) {
            return null;
        }
    }

    /** 红绿灯判定:green/red/gray(取不到) */
    public String light(String key, BigDecimal value, BigDecimal target) {
        MetricDef def = def(key);
        if (def == null || value == null || target == null) {
            return "gray";
        }
        boolean pass = ActionItem.OP_LE.equals(def.compareOp)
                ? value.compareTo(target) <= 0
                : value.compareTo(target) >= 0;
        return pass ? "green" : "red";
    }
}
