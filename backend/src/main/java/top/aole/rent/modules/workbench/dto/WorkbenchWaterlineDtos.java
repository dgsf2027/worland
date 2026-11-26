package top.aole.rent.modules.workbench.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作台资金水位线预测 & 数据体检清理 DTO（Phase 3）。
 */
public final class WorkbenchWaterlineDtos {

    private WorkbenchWaterlineDtos() {
    }

    @Data
    public static class CashBalanceRequest {
        /** 当前银行可用余额（元，>=0） */
        private BigDecimal balance;
        private String remark;
    }

    @Data
    public static class WaterlinePoint {
        private String month;
        private BigDecimal plannedInflow;
        private BigDecimal plannedOutflow;
        private BigDecimal netFlow;
        private BigDecimal endingBalance;
        /** 若期末余额 < 0，则为当月资金缺口正值；否则 0 */
        private BigDecimal shortfall;
    }

    @Data
    public static class CashWaterline {
        private boolean costVisible;
        private LocalDate asOf;
        private int months;
        /** 系统已保存的银行可用余额 */
        private BigDecimal savedCashBalance;
        /** 本次测算使用的期初可用余额（支持前端临时试算覆盖） */
        private BigDecimal openingCashBalance;
        /** 历史已逾期但尚未收回的租金（单独提示，不混入未来月） */
        private BigDecimal overdueReceivable;
        /** 已逾期但尚未支付的供应商应付（会直接扣减首月可用资金） */
        private BigDecimal overduePayable;
        /** 未来 30 天内到期应付笔数与金额 */
        private int duePayable30dCount;
        private BigDecimal duePayable30dAmount;
        /** 窗口内累计计划收款 / 付款 / 净流 */
        private BigDecimal totalInflow;
        private BigDecimal totalOutflow;
        private BigDecimal totalNetFlow;
        /** 窗口内最低可用余额及其月份 */
        private BigDecimal minBalance;
        private String minBalanceMonth;
        /** 首次资金见底（余额 < 0）的月份；无缺口为 null */
        private String firstShortfallMonth;
        /** 最大资金缺口（需补充流动资金额，>=0） */
        private BigDecimal maxShortfall;
        private List<WaterlinePoint> points = new ArrayList<>();
    }

    @Data
    public static class HealthItem {
        private String key;
        private String label;
        private int count;
        /** ok / warn / critical */
        private String level;
        private String suggestion;
        private String link;
    }

    @Data
    public static class DataHealth {
        private int staleTaskCount;
        private int realOverdueBillCount;
        private BigDecimal realOverdueBillAmount;
        private int pendingInvoiceCount;
        private int overdueInvoiceCount;
        private int unlinkedPurchaseContractCount;
        private int unlinkedPurchaseInCount;
        private boolean healthy;
        private List<HealthItem> items = new ArrayList<>();
    }

    @Data
    public static class CleanupResult {
        private int cleanedCount;
        private List<String> details = new ArrayList<>();
    }
}
