package top.aole.rent.modules.pcontract.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;

/** 采购合同付款节点。 */
@Data
@TableName("yc_rent_purchase_contract_term")
public class PurchaseContractTerm {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long pcId;
    private Integer sortNo;
    private String stageName;
    private BigDecimal ratio;
    private BigDecimal amount;
    private String conditionText;
}
