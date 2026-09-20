package top.aole.rent.modules.asset.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合同清单行(对齐《工程量清单计价表》:序号/名称/型号/规格/单位/数量/单价/金额/备注)。
 * 合计(含税) = Σ 金额 → 回写设备合同价;赠送行金额为空、优惠行金额为负(amountManual=1)。
 */
@Data
@TableName("yc_rent_asset_boq")
public class AssetBoq {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;

    private Integer seq;

    private String name;

    private String model;

    private String spec;

    private String unit;

    private BigDecimal qty;

    /** 单价(含税) */
    private BigDecimal unitPrice;

    /** 金额(含税);null = 表格里的「-」 */
    private BigDecimal amount;

    /** 1=金额手填(赠送/优惠行),0=数量×单价自动算 */
    private Integer amountManual;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
