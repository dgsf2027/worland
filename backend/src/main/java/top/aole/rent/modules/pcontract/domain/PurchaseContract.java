package top.aole.rent.modules.pcontract.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 采购合同(与供应商签;付款节点、付款、进项发票都挂在它下面)。 */
@Data
@TableName("yc_rent_purchase_contract")
public class PurchaseContract {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String contractNo;
    private Long supplierId;
    private Long rentContractId;
    private BigDecimal totalAmount;
    private BigDecimal taxRate;
    private LocalDate signedDate;
    /** 执行中/已结清/作废 */
    private String status;
    private String remark;
    private String createByName;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    @TableLogic
    private Integer isDeleted;
}
