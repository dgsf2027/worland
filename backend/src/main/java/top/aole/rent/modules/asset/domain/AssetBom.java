package top.aole.rent.modules.asset.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 配件树 BOM(自引用多级 · M1-07)。设备→总成/模块→部件→元器件。
 *
 * <p>成本拆解=Σ(qty×unit_cost);残值构成=Σ 部件残值(residual_rate×unit_cost);故障档案=fault_count 按配件。
 */
@Data
@TableName("yc_rent_asset_bom")
public class AssetBom {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;

    /** 父节点(自引用);NULL=一级总成 */
    private Long parentId;

    private String name;

    private BigDecimal qty;

    private BigDecimal unitCost;

    private Long supplierId;

    private BigDecimal lifeYears;

    private LocalDate warrantyUntil;

    private Integer repairable;

    /** 累计故障次数(高故障配件预警) */
    private Integer faultCount;

    /** 部件残值率(0-1);空=随品类 */
    private BigDecimal residualRate;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
