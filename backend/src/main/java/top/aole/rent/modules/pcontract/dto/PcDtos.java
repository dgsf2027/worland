package top.aole.rent.modules.pcontract.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class PcDtos {

    private PcDtos() {
    }

    @Data
    public static class TermInput {
        private String stageName;
        /** 比例(0~1,可空;空则必须给 amount) */
        private BigDecimal ratio;
        /** 金额(可空;空则按 ratio×合同总价) */
        private BigDecimal amount;
        private String conditionText;
    }

    @Data
    public static class CreateRequest {
        private String contractNo;
        private Long supplierId;
        private Long rentContractId;
        private BigDecimal totalAmount;
        /** 税率,如 0.13;缺省 0.13 */
        private BigDecimal taxRate;
        private LocalDate signedDate;
        private String remark;
        private List<TermInput> terms;
        /** 合同原件(照片/PDF)的文件 id,可选;经回单上传接口先上传 */
        private List<Long> proofFileIds;
    }

    @Data
    public static class AllocInput {
        private Long termId;
        private BigDecimal amount;
    }

    @Data
    public static class PaymentRequest {
        private BigDecimal amount;
        private LocalDate paidDate;
        private String bankSerial;
        private String remark;
        /** 核销到哪些节点;为空则按节点顺序自动核销 */
        private List<AllocInput> allocs;
        /** 回单(必传) */
        private List<Long> proofFileIds;
    }

    @Data
    public static class InvoiceRequest {
        private String invoiceNo;
        /** 专票/普通 */
        private String invoiceType;
        private LocalDate issueDate;
        private String sellerName;
        private BigDecimal amountExTax;
        private BigDecimal taxAmount;
        private BigDecimal totalAmount;
        private BigDecimal taxRate;
        private String remark;
        /** 发票原件(照片/PDF)的文件 id,可选 */
        private List<Long> proofFileIds;
    }

    @Data
    public static class VoidRequest {
        private String reason;
    }

    @Data
    public static class TermView {
        private Long id;
        private Integer sortNo;
        private String stageName;
        private BigDecimal ratio;
        private BigDecimal amount;
        private BigDecimal paid;
        private BigDecimal unpaid;
        private String conditionText;
    }

    /** 三单对账汇总:合同额 / 已付 / 已收票。 */
    @Data
    public static class Summary {
        private BigDecimal total;
        private BigDecimal paid;
        private BigDecimal invoiced;
        private BigDecimal unpaid;
        private BigDecimal uninvoiced;
        /** 已付但未收票 */
        private BigDecimal paidNotInvoiced;
    }

    @Data
    public static class ContractView {
        private Long id;
        private String contractNo;
        private Long supplierId;
        private String supplierName;
        private Long rentContractId;
        /** 主合同(客户租赁合同)信息 */
        private String rentContractNo;
        private String customerName;
        private Integer rentTermMonths;
        private BigDecimal rentMonthRent;
        private LocalDate rentStartDate;
        private String rentStatus;
        private BigDecimal totalAmount;
        private BigDecimal taxRate;
        private LocalDate signedDate;
        private String status;
        private String remark;
        private Summary summary;
    }

    @Data
    public static class PaymentView {
        private Long id;
        private BigDecimal amount;
        private LocalDate paidDate;
        private String bankSerial;
        private Long voucherId;
        private String status;
        private String remark;
        private List<AllocInput> allocs;
        private Integer proofCount;
    }

    @Data
    public static class LinkRequest {
        private List<Long> purchaseInIds;
    }

    @Data
    public static class LinkedPurchase {
        private Long id;
        private String no;
        private String status;
        private BigDecimal totalAmount;
        /** 该采购单仍待付的应付合计 */
        private BigDecimal pendingPayable;
    }

    @Data
    public static class Detail {
        private List<LinkedPurchase> purchases;
        /** 合同原件张数 */
        private Integer contractProofCount;
        /** 发票 id → 原件张数 */
        private java.util.Map<Long, Integer> invoiceProofCounts;
        private ContractView contract;
        private List<TermView> terms;
        private List<PaymentView> payments;
        private List<top.aole.rent.modules.pcontract.domain.PurchaseInvoice> invoices;
    }
}
