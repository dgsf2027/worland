package top.aole.rent.modules.inventory.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 归还时登记的损坏/缺件行。金额 = 损坏数×损坏单价 + 缺失数×缺失单价(单价为登记时价目快照)。
 */
@Data
@TableName("yc_rent_inv_damage")
public class InvDamage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long movementId;

    private Long rentalId;

    private Long itemId;

    private String partName;

    private Integer damagedQty;

    private Integer missingQty;

    private BigDecimal damagePrice;

    private BigDecimal missingPrice;

    private BigDecimal amount;

    /** 待收取/已收取/已减免 */
    private String settleStatus;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
