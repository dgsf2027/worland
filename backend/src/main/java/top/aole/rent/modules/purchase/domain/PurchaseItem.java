package top.aole.rent.modules.purchase.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 采购明细(逐件 · M1-12)。入库时逐件生成 {@code yc_rent_asset},{@code asset_id} 回填。
 */
@Data
@TableName("yc_rent_purchase_item")
public class PurchaseItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long purchaseInId;

    /** 序列号(逐件唯一·生成 asset.serial_no) */
    private String serialNo;

    private String category;

    private String model;

    private BigDecimal marketPrice;

    private BigDecimal purchasePrice;

    private Long supplierId;

    private BigDecimal monthlyLaborValue;

    private BigDecimal replaceHeadcount;

    /** 【回填】入库生成的 yc_rent_asset.id */
    private Long assetId;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
