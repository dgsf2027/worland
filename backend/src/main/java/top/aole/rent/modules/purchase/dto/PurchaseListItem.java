package top.aole.rent.modules.purchase.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 采购单列表行(M1-12)。
 */
@Data
public class PurchaseListItem {

    private Long id;

    private String no;

    private String status;

    private Long contractId;

    private String contractNo;

    private String customerName;

    private Long supplierId;

    private String supplierName;

    private BigDecimal totalAmount;

    private Integer itemCount;

    private LocalDate orderDate;

    private LocalDate receiveDate;

    /** 待付应付合计(负债口径) */
    private BigDecimal payableOutstanding;

    /** 敏感成本字段是否被打码(GP/LP) */
    private Boolean sensitiveMasked;
}
