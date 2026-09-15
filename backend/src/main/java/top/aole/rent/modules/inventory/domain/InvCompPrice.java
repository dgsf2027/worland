package top.aole.rent.modules.inventory.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 损坏缺件赔偿价目。 */
@Data
@TableName("yc_rent_inv_comp_price")
public class InvCompPrice {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String partName;

    private String unit;

    private BigDecimal damagePrice;

    private BigDecimal missingPrice;

    private Integer sortNo;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
