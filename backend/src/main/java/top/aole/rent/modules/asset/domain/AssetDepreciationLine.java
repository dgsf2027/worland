package top.aole.rent.modules.asset.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 折旧计划真相源(经营口径 · M3-02 · ADR-004 P0-B)。逐月计提一行。
 *
 * <p><b>book_value 唯一写手</b>:{@code asset.book_value} 由本表最新 {@code book_value_after} 即时算/回填
 * (§4.17 stale 红线解除:折旧按月发生,事件流覆盖不了,本表是唯一写手)。
 * <p>幂等:unique(asset_id,book,period_no) —— 同设备同账套同期次仅一行,月度计提可重复安全触发。
 */
@Data
@TableName("yc_rent_asset_depreciation_line")
public class AssetDepreciationLine {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;

    /** ops 经营口径(折旧只落经营账) */
    private String book;

    /** 折旧期次(第 N 个月·从 1 起) */
    private Integer periodNo;

    /** 记账期 YYYY-MM */
    private String period;

    private BigDecimal deprAmount;

    /** 本期折旧后账面净值(递减·book_value 真相源) */
    private BigDecimal bookValueAfter;

    private Long voucherId;

    private LocalDate bizDate;

    private String remark;

    private LocalDateTime createTime;

    private Integer isDeleted;
}
