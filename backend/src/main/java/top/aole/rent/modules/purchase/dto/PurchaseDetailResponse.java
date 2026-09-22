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
        /** 设备(设备租赁台账) */
        private Long assetId;
        /** 设备显示名:品类 · 型号 */
        private String assetLabel;
        private String assetStatus;
        /** 供应商(取自设备台账) */
        private String supplierName;
        /** 付款条件摘要:首付30%(下单)/验收60%(入库)… */
        private String paymentTerms;
        /** 预计付款金额合计(= 设备合同价) */
        private BigDecimal expectedAmount;
        /** 已生成应付合计 */
        private BigDecimal payableAmount;
        /** 待付 */
        private BigDecimal payableOutstanding;
        private String remark;
        // —— 以下为历史字段,页面已不再展示(老单据仍保留数据) ——
        private String serialNo;
        private String category;
        private String model;
        private BigDecimal marketPrice;
        /** 集采价(敏感·GP/LP 为 null) */
        private BigDecimal purchasePrice;
    }

    @Data
    public static class PayableLine {
        private Long id;
        /** 逐台应付对应的设备(旧版整单应付为 null) */
        private Long assetId;
        /** 设备显示名:品类 · 型号 */
        private String assetLabel;
        /** 供应商(取自设备台账) */
        private String supplierName;
        private String serialNo;
        private String stage;
        private LocalDate dueDate;
        /** 金额(敏感·GP/LP 为 null) */
        private BigDecimal amount;
        private String status;
        private LocalDate paidDate;
        private String remark;
    }
}
