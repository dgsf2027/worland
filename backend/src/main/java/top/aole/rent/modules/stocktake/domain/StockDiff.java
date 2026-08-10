package top.aole.rent.modules.stocktake.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 盘点差异行(M4-05)。账实差异 → 盘盈亏调整单(走单据不直改台账·§4.24),调整落 asset_event 留痕。
 */
@Data
@TableName("yc_rent_stock_diff")
public class StockDiff {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long stocktakeId;

    private Long assetId;

    /** 账面状态(盘点时快照) */
    private String bookStatus;

    /** 实盘状态(扫码录入;丢失=盘亏) */
    private String actualStatus;

    /** 差异类型:盘盈/盘亏/状态不符 */
    private String diffType;

    /** 是否已生成调整单闭合 */
    private Integer adjusted;

    /** 调整落库的 asset_event.id */
    private Long adjustEventId;

    private String remark;

    private LocalDateTime createTime;

    private Integer isDeleted;
}
