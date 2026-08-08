package top.aole.rent.modules.asset.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 资产事件流(状态机驱动源 · S0-07 建表 · M1 起写)。
 *
 * <p>所有写单据 → 事件流 → 凭证 的留痕底座;{@code asset.status} 只由事件流推进(§4.24 单一真相源)。
 */
@Data
@TableName("yc_rent_asset_event")
public class AssetEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;

    /** 事件类型:采购/投放/在租/维修/待转让/转让/收回待处置/再投放/二手/报废 */
    private String eventType;

    private String refDocType;

    private Long refDocId;

    private LocalDateTime bizTime;

    private Long projectId;

    private Long operatorId;

    private String remark;

    private LocalDateTime createTime;

    private Integer isDeleted;
}
