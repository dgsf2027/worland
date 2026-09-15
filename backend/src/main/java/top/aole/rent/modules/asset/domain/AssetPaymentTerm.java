package top.aole.rent.modules.asset.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 设备合同付款条件(自定义多段)。预计付款金额 = 设备集采价 × ratio(即时算,末段补差)。
 * 采购下单时先挂在采购明细上(purchaseItemId),入库生成设备后回填 assetId。
 */
@Data
@TableName("yc_rent_asset_payment_term")
public class AssetPaymentTerm {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;

    private Long purchaseItemId;

    private Integer seq;

    private String stageName;

    /** 付款比例(0-1),同一设备各段合计=1 */
    private BigDecimal ratio;

    /** 触发时点:下单/入库 */
    private String triggerPoint;

    /** 到期天数(触发日 + N 天) */
    private Integer dueDays;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
