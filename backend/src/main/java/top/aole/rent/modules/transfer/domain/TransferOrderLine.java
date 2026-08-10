package top.aole.rent.modules.transfer.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 转让/处置单逐台行(M4-01/02)。account_value 快照不回写 · gain 逐台损益 · voucher_id 回填残值凭证。
 *
 * <p><b>单一真相源(ADR-004)</b>:{@code bookValue} = 处置时账面价快照(ops 口径·由折旧真相源算),
 * 一经落库不再回写——处置后设备折旧停止,快照即定论。{@code gain = transferPrice - bookValue}。
 */
@Data
@TableName("yc_rent_transfer_order_line")
public class TransferOrderLine {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long transferOrderId;

    private Long assetId;

    /** 处置时账面价快照(不回写) */
    private BigDecimal bookValue;

    private BigDecimal transferPrice;

    /** = transferPrice - bookValue */
    private BigDecimal gain;

    /** 本行触发名义价守卫 */
    private Integer nominalFlag;

    /** 残值/处置凭证(税务账·postResidual 回填) */
    private Long voucherId;

    private String remark;

    private LocalDateTime createTime;

    private Integer isDeleted;
}
