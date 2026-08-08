package top.aole.rent.modules.purchase.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 采购单详情(M1-12/13)。头 + 逐件明细(含生成设备) + 应付计划(首付/验收/尾款) + 合同/客户。
 * 敏感成本字段(集采价/应付金额)对 GP/LP 打码。
 */
@Data
public class PurchaseDetailResponse {

    private Long id;

    private String no;

    private String status;

    private Long contractId;

    private String contractNo;

    private String customerName;

    private Long supplierId;

    private String supplierName;

    private BigDecimal totalAmount;

    private LocalDate orderDate;

    private LocalDate receiveDate;

    private String remark;

    private Boolean sensitiveMasked;

    /** 待付应付合计(负债口径) */
    private BigDecimal payableOutstanding;

    private List<ItemLine> items;

    private List<PayableLine> payables;

    @Data
    public static class ItemLine {
        private Long id;
        private String serialNo;
        private String category;
        private String model;
        private BigDecimal marketPrice;
        /** 集采价(敏感·GP/LP 为 null) */
        private BigDecimal purchasePrice;
        private String supplierName;
        /** 【回填】入库生成的设备 id */
        private Long assetId;
        private String assetStatus;
    }

    @Data
    public static class PayableLine {
        private Long id;
        private String stage;
        private LocalDate dueDate;
        /** 金额(敏感·GP/LP 为 null) */
        private BigDecimal amount;
        private String status;
        private LocalDate paidDate;
        private String remark;
    }
}
