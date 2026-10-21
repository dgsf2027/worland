package top.aole.rent.modules.pcontract.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 供应商付款冲销应付行的记录(V125):撤销付款时据此精确还原应付。 */
@Data
@TableName("yc_rent_supplier_payment_payable")
public class SupplierPaymentPayable {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long paymentId;
    private Long payableId;
    private BigDecimal paidAmount;
    /** 冲销前该应付行的金额 */
    private BigDecimal origAmount;
    /** 部分冲销时拆出的差额待付行 */
    private Long remainderId;
}
