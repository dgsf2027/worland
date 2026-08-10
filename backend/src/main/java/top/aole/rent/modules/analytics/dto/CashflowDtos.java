package top.aole.rent.modules.analytics.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 现金流驾驶舱 / 账期兑付缺口 / 回报四源 DTO 汇总(M3 Wave B · M3-05/06/09)。
 */
public class CashflowDtos {

    // ---------- M3-05 现金流驾驶舱 ----------

    @Data
    public static class Cashflow {
        private LocalDate asOf;
        /** 应收(rent_schedule 未收)按到期分层 */
        private Bucket receivable;
        /** 应付(payable 待付)按到期分层 */
        private Bucket payable;
        /** 净现金流预测曲线(未来 N 月) */
        private List<MonthFlow> forecast;
        /** 三层杠杆资金占用(自有/供应商账期/融资) */
        private Leverage leverage;
        /** 净头寸 = 应收合计 − 应付合计(粗口径) */
        private BigDecimal netPosition;
    }

    /** 到期分层(1年内 / 1年以上 / 合计)。 */
    @Data
    public static class Bucket {
        private BigDecimal within1Year;
        private BigDecimal beyond1Year;
        private BigDecimal total;
        private int count;
    }

    @Data
    public static class MonthFlow {
        private String period;        // YYYY-MM
        private BigDecimal inflow;     // 当月预计收款(应收到期)
        private BigDecimal outflow;    // 当月预计付款(应付到期)
        private BigDecimal net;        // inflow − outflow
        private BigDecimal cumulative; // 累计净额
    }

    /** 三层杠杆资金占用(§8.4 资金结构·区别于回报四源利润)。 */
    @Data
    public static class Leverage {
        private BigDecimal ownCapital;      // 层级①自有资金(实缴出资合计)
        private BigDecimal supplierCredit;  // 层级②供应商账期(待付 payable·无息)
        private BigDecimal financing;        // 层级③融资(未启用=0)
        private BigDecimal total;
        private String note;
    }

    // ---------- M3-06 账期兑付缺口预警(P0-H) ----------

    @Data
    public static class CoverageGapReport {
        private LocalDate asOf;
        private int tMinusDays;              // 提前预警天数
        private BigDecimal usableReserve;    // 可动用留存(=累计留存 − 留存下限)
        private boolean hasRedAlert;         // 是否存在红灯缺口
        private int redCount;
        private List<GapItem> items;
    }

    @Data
    public static class GapItem {
        private Long payableId;
        private Long purchaseInId;
        private String stage;               // 首付/验收/尾款
        private LocalDate dueDate;
        private long daysToDue;
        private BigDecimal payableAmount;    // 本笔应付到期
        private BigDecimal cumInflow;        // 到期日前累计可用回款
        private BigDecimal cumOutflow;       // 到期日前累计应付(含本笔)
        private BigDecimal projectedCash;    // 可动留存 + 累计回款 − 累计应付
        private BigDecimal gap;              // <0 = 缺口(projectedCash 为负值)
        private boolean red;                // projectedCash < 0 → 红灯
        private String arbiter;             // 裁决人
        private List<String> fundingSources;// 补款来源建议
    }

    // ---------- M3-09 回报四源 ----------

    @Data
    public static class ReturnAttribution {
        private String customerType;        // 目标客户类型(目标 IRR 来源)
        private BigDecimal totalIrr;         // 总税后 IRR
        private List<AttributionSource> sources;
        private BigDecimal sumCheck;         // 四源之和(勾稽=totalIrr)
        private boolean reconciled;          // sumCheck == totalIrr
        private String note;
    }

    @Data
    public static class AttributionSource {
        private String key;                 // procurementSpread/timeValue/valuePricing/residual
        private String label;               // 集采差价/资金时间价值/价值定价/残值回收
        private BigDecimal weight;           // 权重(∑=1)
        private BigDecimal irrContribution;  // 该源贡献 IRR = totalIrr × weight
        private String source;              // 数据来源溯源
    }
}
