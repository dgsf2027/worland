package top.aole.rent.modules.contract.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 押金台账(收/退/期末抵转让价 · M1-18)。独立保证金科目,不进客户总付净额。
 */
@Data
@TableName("yc_rent_deposit_ledger")
public class DepositLedger {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long contractId;

    /** 方向:收/退/期末抵 */
    private String direction;

    private BigDecimal amount;

    private LocalDateTime bizTime;

    private Long operatorId;

    private String remark;

    private LocalDateTime createTime;

    private Integer isDeleted;
}
