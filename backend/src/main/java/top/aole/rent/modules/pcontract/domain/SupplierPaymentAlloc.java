package top.aole.rent.modules.pcontract.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 付款对合同节点的核销。 */
@Data
@TableName("yc_rent_supplier_payment_alloc")
public class SupplierPaymentAlloc {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long paymentId;
    private Long termId;
    private BigDecimal amount;
}
