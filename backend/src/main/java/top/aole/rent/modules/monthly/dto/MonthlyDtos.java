package top.aole.rent.modules.monthly.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 月度报表包 DTO(M3-07,DESIGN_DOC §4.3 六件套 package_json + 七节 analysis_json + 财务日历)。
 *
 * 只读聚合:所有数字来自已建成的 billing/finance/analytics/asset/distribution 服务,
 * 一律聚合不重算(§4.24 单一真相源)。分配表(⑥)按角色可见(P0-E)。
 */
public class MonthlyDtos {

    // ==================== 报表包总览(六件套)====================

    @Data
    public static class PackageResp {
        private String period;
        /** 有数据的账期列表(月份切换器) */
        private List<String> periods = new ArrayList<>();
        private LocalDateTime dataAsOf;
        /** 该期 ops 账是否已锁账 */
        private boolean locked;

        /** ① 收租台账(billing:本期收租单 + 应收/已收/待收/逾期) */
        private RentLedger rentLedger;
        /** ② 利润表(凭证 ops:租赁收入−折旧−残值损益=经营利润) */
        private ProfitStatement profit;
        /** ③ 现金流水(ops 银行存款收/支/净 + 现金头寸/杠杆) */
        private CashflowSheet cashflow;
        /** ④ 往来(应收未收 + 应付待付,按到期分层) */
        private DueSheet dueSheet;
        /** ⑤ 资产快照(在租/闲置/待处置计数 + 家底金额) */
        private AssetSnapshot asset;
        /** ⑥ 分配表(🔒 按角色可见:财务/老板/GP 见全表,LP 仅本人,其他角色不可见→null) */
        private DistributionSheet distribution;
        /** ⑥ 分配表对当前角色是否可见(false=已按 RBAC 屏蔽) */
        private boolean distributionVisible;
        /** 屏蔽说明(不可见时给出原因) */
        private String distributionMaskNote;
    }

    // ==================== ① 收租台账 ====================

    @Data
    public static class RentLedger {
        private String period;
        private List<RentLedgerRow> rows = new ArrayList<>();
        private BigDecimal totalReceivable = BigDecimal.ZERO; // 本期应收(单据金额合计)
        private BigDecimal totalReceived = BigDecimal.ZERO;   // 本期已收(已核销)
        private BigDecimal totalOutstanding = BigDecimal.ZERO;// 待收(应收−已收)
        private int billCount;
        private int matchedCount;
        private int overdueCount;
        private BigDecimal collectionRate = BigDecimal.ZERO;  // 回款率 = 已收/应收
    }

    @Data
    public static class RentLedgerRow {
        private Long billId;
        private String billNo;
        private String contractNo;
        private Integer periodNo;
        private LocalDate dueDate;
        private BigDecimal amount;
        private BigDecimal receivedAmount;
        private String status;
        private String billKind;
        private boolean overdue;
    }

    // ==================== ② 利润表(ops) ====================

    @Data
    public static class ProfitStatement {
        private String period;
        private boolean locked;
        private List<PlRow> rows = new ArrayList<>();
        private BigDecimal operatingProfit = BigDecimal.ZERO; // 经营利润(合计行)
    }

    @Data
    public static class PlRow {
        private String key;      // leaseRevenue/depreciation/residual/operatingProfit
        private String label;
        private BigDecimal amount; // 对经营利润的贡献(收入正、费用负)
        private boolean subtotal;
        private String note;

        public PlRow() {
        }

        public PlRow(String key, String label, BigDecimal amount, boolean subtotal, String note) {
            this.key = key;
            this.label = label;
            this.amount = amount;
            this.subtotal = subtotal;
            this.note = note;
        }
    }

    // ==================== ③ 现金流水 ====================

    @Data
    public static class CashflowSheet {
        private String period;
        private BigDecimal inflow = BigDecimal.ZERO;   // 本期银行存款收(dr 1002)
        private BigDecimal outflow = BigDecimal.ZERO;  // 本期银行存款支(cr 1002)
        private BigDecimal net = BigDecimal.ZERO;      // 收−支
        private int inCount;
        private int outCount;
        // 现金头寸(取 CashflowService,不重算)
        private BigDecimal netPosition = BigDecimal.ZERO;       // 应收 − 应付
        private BigDecimal ownCapital = BigDecimal.ZERO;        // 层①自有
        private BigDecimal supplierCredit = BigDecimal.ZERO;    // 层②供应商账期
        private BigDecimal financing = BigDecimal.ZERO;         // 层③融资
    }

    // ==================== ④ 往来(应收/应付到期分层)====================

    @Data
    public static class DueSheet {
        private LocalDate asOf;
        // 应收未收
        private BigDecimal receivableWithin1Y = BigDecimal.ZERO;
        private BigDecimal receivableBeyond1Y = BigDecimal.ZERO;
        private BigDecimal receivableTotal = BigDecimal.ZERO;
        private int receivableCount;
        // 应付待付
        private BigDecimal payableWithin1Y = BigDecimal.ZERO;
        private BigDecimal payableBeyond1Y = BigDecimal.ZERO;
        private BigDecimal payableTotal = BigDecimal.ZERO;
        private int payableCount;
        private List<PayableRow> payables = new ArrayList<>();
    }

    @Data
    public static class PayableRow {
        private Long payableId;
        private Long purchaseInId;
        private String stage;
        private LocalDate dueDate;
        private BigDecimal amount;
        private boolean overdue;
    }

    // ==================== ⑤ 资产快照 ====================

    @Data
    public static class AssetSnapshot {
        private String period;
        private int total;
        private int rentedCount;      // 在租
        private int idleCount;        // 闲置(投放但未在租)
        private int pendingDisposal;  // 待处置(收回待处置)
        private BigDecimal rentedRatio = BigDecimal.ZERO; // 在租率
        private BigDecimal marketPriceTotal = BigDecimal.ZERO;  // 市场价家底
        private BigDecimal bookValueTotal = BigDecimal.ZERO;    // 账面净值(ops·折旧后即时算)
        private List<StatusCount> byStatus = new ArrayList<>();
    }

    @Data
    public static class StatusCount {
        private String status;
        private int count;

        public StatusCount() {
        }

        public StatusCount(String status, int count) {
            this.status = status;
            this.count = count;
        }
    }

    // ==================== ⑥ 分配表(🔒 角色可见)====================

    @Data
    public static class DistributionSheet {
        private String period;
        private boolean present;              // 该期是否已有 active 分配
        private BigDecimal distributable = BigDecimal.ZERO;
        private BigDecimal mgmtFee = BigDecimal.ZERO;
        private BigDecimal cash50 = BigDecimal.ZERO;
        private BigDecimal roll50 = BigDecimal.ZERO;
        private BigDecimal reserveAfter = BigDecimal.ZERO;
        private List<ShareRow> shares = new ArrayList<>();
        /** 本表可见范围说明(全表 / 仅本人 LP) */
        private String scopeNote;
    }

    @Data
    public static class ShareRow {
        private String name;      // LP 仅本人时其他行 name 脱敏为「***」
        private String role;
        private BigDecimal ratio;
        private BigDecimal cashShare;
        private BigDecimal rollShare;
        private BigDecimal mgmtFee;
        private BigDecimal totalGain;
        private boolean self;
    }

    // ==================== ⑦ 经营分析报告(固定七节)====================

    @Data
    public static class AnalysisReport {
        private String period;
        private List<String> periods = new ArrayList<>();
        private boolean locked;
        /** AI 起草综述(mock 带 [MOCK];🔬 徽标看四件套) */
        private String aiText;
        private Long llmCallId;
        private boolean cacheHit;
        private String model;
        private boolean mock;
        /** 固定七节 */
        private List<AnalysisSection> sections = new ArrayList<>();
    }

    @Data
    public static class AnalysisSection {
        /** overview/collection/profit/asset/money/risk/improvement */
        private String key;
        private String title;
        /** 规则引擎起草的本节叙述(数字全部来自六件套,LLM 不算数) */
        private String narrative;
        private List<DataPoint> dataPoints = new ArrayList<>();
    }

    @Data
    public static class DataPoint {
        private String label;
        private String value;
        /** 数据溯源:该数字来自六件套哪一件 */
        private String source;

        public DataPoint() {
        }

        public DataPoint(String label, String value, String source) {
            this.label = label;
            this.value = value;
            this.source = source;
        }
    }

    // ==================== 财务月度工作日历 ====================

    @Data
    public static class CalendarBoard {
        private String period;
        private String status; // PENDING / PACKAGE_READY / REVIEWED / REPORTED
        private List<CalendarDay> days = new ArrayList<>();
        private int pendingCount;
    }

    @Data
    public static class CalendarDay {
        private int day;         // 1/2/3/5
        private String financeAction;
        private String systemAuto;
        private String state;    // AUTO_DONE / PENDING_MANUAL
        private String note;
    }
}
