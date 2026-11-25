package top.aole.rent.modules.contract.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 项目全景利润 / 现金流 / 回本进度 DTO（老板视角：采购投入 ↔ 租赁产出 ↔ 资金峰值 ↔ 税后净利）。
 */
public final class ProjectPanoramaDtos {

    private ProjectPanoramaDtos() {
    }

    @Data
    public static class Panorama {
        // ---- 合同基本信息 ----
        private Long contractId;
        private String contractNo;
        private Long customerId;
        private String customerName;
        private String nature;
        private String status;
        private LocalDate signDate;
        private LocalDate startDate;
        private LocalDate endDate;
        private Integer termMonths;
        private BigDecimal monthRent;
        private BigDecimal leaseAnnualRate;
        private BigDecimal endTransferPrice;
        private BigDecimal deposit;
        private BigDecimal depositHeld;
        private BigDecimal invoiceTaxRate;
        private Integer invoiceDeadlineDays;
        private boolean costVisible;

        // ---- 租赁产出侧 ----
        private BigDecimal plannedTotalRent;
        private BigDecimal plannedCustomerTotal;
        private BigDecimal receivedRent;
        private BigDecimal receivablePending;
        private BigDecimal overdueAmount;
        private BigDecimal remainingPrincipal;
        private int totalPeriods;
        private int settledPeriods;
        private int pendingInvoiceCount;
        private int overdueInvoiceCount;
        private BigDecimal invoicedRentAmount;

        // ---- 采购投入侧（GP/LP 脱敏为 null） ----
        private BigDecimal purchaseTotal;
        private BigDecimal purchasePaid;
        private BigDecimal purchaseUnpaid;
        private BigDecimal inputInvoiceTotal;
        private BigDecimal inputInvoiceTax;
        private LocalDate nextPayableDate;
        private BigDecimal nextPayableAmount;
        private String nextPayableStage;
        private LocalDate warrantyEndDate;
        private Long warrantyDaysLeft;

        // ---- 真实利润与回本（GP/LP 脱敏为 null） ----
        private BigDecimal expectedGrossProfit;
        private BigDecimal expectedGrossMargin;
        private BigDecimal estimatedOutputVat;
        private BigDecimal estimatedInputVat;
        private BigDecimal estimatedTaxCost;
        private BigDecimal estimatedAfterTaxProfit;
        private BigDecimal actualNetCashflow;
        private BigDecimal paybackProgress;
        private Integer paybackPeriodNo;
        private BigDecimal peakCapitalOutlay;
        private String peakOutlayMonth;

        // ---- 关联采购明细 & 逐月现金流 ----
        private List<LinkedPurchase> linkedPurchases = new ArrayList<>();
        private List<MonthCashflow> monthlyCashflows = new ArrayList<>();
    }

    @Data
    public static class LinkedPurchase {
        /** 采购合同 / 采购单 */
        private String kind;
        private Long id;
        private String no;
        private String supplierName;
        private BigDecimal totalAmount;
        private BigDecimal paidAmount;
        private BigDecimal unpaidAmount;
        private String status;
        private LocalDate acceptanceDate;
        private LocalDate warrantyEndDate;
    }

    @Data
    public static class MonthCashflow {
        private String month;
        private BigDecimal rentInflow;
        private BigDecimal purchaseOutflow;
        private BigDecimal netFlow;
        private BigDecimal cumulativeNet;
    }

    @Data
    public static class Brief {
        private Long contractId;
        private String contractNo;
        private Long customerId;
        private String customerName;
        private String nature;
        private String status;
        private Integer termMonths;
        private BigDecimal monthRent;
        private BigDecimal plannedCustomerTotal;
        private BigDecimal receivedRent;
        private BigDecimal overdueAmount;
        private int pendingInvoiceCount;
        private int overdueInvoiceCount;

        private BigDecimal purchaseTotal;
        private BigDecimal purchasePaid;
        private BigDecimal purchaseUnpaid;
        private BigDecimal expectedGrossProfit;
        private BigDecimal estimatedAfterTaxProfit;
        private BigDecimal actualNetCashflow;
        private BigDecimal paybackProgress;
        private Integer paybackPeriodNo;
        private BigDecimal peakCapitalOutlay;
        private LocalDate nextPayableDate;
        private BigDecimal nextPayableAmount;
        private LocalDate warrantyEndDate;
    }

    @Data
    public static class Overview {
        private boolean costVisible;
        private int projectCount;
        private BigDecimal totalPlannedCustomer;
        private BigDecimal totalReceivedRent;
        private BigDecimal totalPurchase;
        private BigDecimal totalPurchasePaid;
        private BigDecimal totalPurchaseUnpaid;
        private BigDecimal totalExpectedGrossProfit;
        private BigDecimal totalEstimatedAfterTaxProfit;
        private BigDecimal totalActualNetCashflow;
        private List<Brief> items = new ArrayList<>();
    }
}
