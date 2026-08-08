package top.aole.rent.modules.supplier.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 供货矩阵(M1-02)。一家供应商供哪些整机/配件 + 报价/账期/履约五维/价格构成。
 *
 * <p>履约五维(quality/delivery/service/price/term)为录入输入;<b>加权总分不落列</b>,
 * 由 rule_config[supplier_score_weights] 即时算(§4.24 单一真相源,防 stale)。
 */
@Data
@TableName("yc_rent_supplier_supply")
public class SupplierSupply {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long supplierId;

    /** 整机/配件 */
    private String itemType;

    private String itemName;

    private String category;

    /** 集采报价(元);按图报价时为空 */
    private BigDecimal quotePrice;

    /** 首付比例(0-1) */
    private BigDecimal firstPayRatio;

    private Integer accountDays;

    private Integer noInterest;

    private Integer canSingleBuy;

    // ---- 履约五维(0-100 录入输入) ----
    private Integer scoreQuality;
    private Integer scoreDelivery;
    private Integer scoreService;
    private Integer scorePrice;
    private Integer scoreTerm;

    // ---- 价格构成(可空) ----
    private BigDecimal costMaterial;
    private BigDecimal costProcessing;
    private BigDecimal profitAmount;
    private BigDecimal bomEstimate;

    /** 是否该供应商代表供货项(池列表取此行报价/评分) */
    private Integer isPrimary;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
