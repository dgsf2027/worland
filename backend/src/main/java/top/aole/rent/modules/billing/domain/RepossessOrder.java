package top.aole.rent.modules.billing.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收回单(逾期收回/提前收回 · M2-06)。收回时生成,联动 asset→收回待处置(接 M1 设备状态机)。
 */
@Data
@TableName("yc_rent_repossess_order")
public class RepossessOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String no;

    private Long contractId;

    private Long overdueCaseId;

    private Integer assetCount;

    /** 待处置(再投放/二手/报废 M4 细化) */
    private String disposalStatus;

    private Long operatorId;

    private String reason;

    private LocalDateTime bizTime;

    private LocalDateTime createTime;

    private Integer isDeleted;
}
