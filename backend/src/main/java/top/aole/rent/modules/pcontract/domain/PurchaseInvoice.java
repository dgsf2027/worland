package top.aole.rent.modules.pcontract.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 进项发票(供应商开给我方)。 */
@Data
@TableName("yc_rent_purchase_invoice")
public class PurchaseInvoice {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pcId;
    private String invoiceNo;
    /** 专票/普通 */
    private String invoiceType;
    private LocalDate issueDate;
    private String sellerName;
    private BigDecimal amountExTax;
    private BigDecimal taxAmount;
    private BigDecimal totalAmount;
    private BigDecimal taxRate;
    /** 有效/作废 */
    private String status;
    private String remark;
    private String createByName;
    private LocalDateTime createTime;
}
