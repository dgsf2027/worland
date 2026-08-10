package top.aole.rent.modules.transfer.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 转让/处置单(头 · M4-01 · P0-A 拆头+行)。到期转让/收回/二手/报废。
 *
 * <p><b>逐台损益</b>:头只存聚合(total_price/total_gain/asset_count),逐台明细在 {@link TransferOrderLine}
 * (book_value 快照不回写)。<b>名义价守卫(P1-19)</b>:transfer_price&lt;book_value 或 &lt;市场价×下限 → need_approval=1
 * + status=待审批,财务/老板审批通过才过账(非仅亮灯)。
 */
@Data
@TableName("yc_rent_transfer_order")
public class TransferOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String no;

    private Long contractId;

    /** 类型:转让/收回/二手/报废 */
    private String type;

    private Integer assetCount;

    private BigDecimal totalPrice;

    /** Σ gain(处置损益) */
    private BigDecimal totalGain;

    /** 状态:待审批/待过账/已完成/已作废 */
    private String status;

    /** 名义价守卫触发强制升级审批(P1-19) */
    private Integer needApproval;

    private String approvalReason;

    private Long approvedBy;

    private LocalDateTime approvedAt;

    private Long operatorId;

    private LocalDateTime bizTime;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
