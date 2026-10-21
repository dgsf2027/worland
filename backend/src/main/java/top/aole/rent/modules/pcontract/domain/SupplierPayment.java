package top.aole.rent.modules.pcontract.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 供应商付款(一笔银行流水);通过核销行落到合同节点。 */
@Data
@TableName("yc_rent_supplier_payment")
public class SupplierPayment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pcId;
    private Long supplierId;
    private BigDecimal amount;
    private LocalDate paidDate;
    private String bankSerial;
    private Long voucherId;
    /** 有效/撤销 */
    private String status;
    private String remark;
    private String createByName;
    private LocalDateTime createTime;
}
