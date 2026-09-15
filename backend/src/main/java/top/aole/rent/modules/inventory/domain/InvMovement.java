package top.aole.rent.modules.inventory.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 资产出入库记录(出库/归还/入库/送修/修好/报废)。现场照片走对象存储 biz_type=inv_movement。
 */
@Data
@TableName("yc_rent_inv_movement")
public class InvMovement {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long itemId;

    private Long rentalId;

    private String type;

    private Integer qty;

    private Integer goodQty;

    private Integer repairQty;

    private Integer scrapQty;

    private String accessories;

    /** 完好/轻微损坏/损坏 */
    private String conditionLevel;

    private String conditionDesc;

    private BigDecimal compensationTotal;

    private Long operatorId;

    private String operatorName;

    private LocalDateTime opTime;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
