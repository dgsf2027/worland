package top.aole.rent.modules.roster.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 提成(M5-03)。供应链降本贡献 / 业务成交贡献,从管理费列支(非成本)。
 *
 * <p><b>口径(§4.24)</b>:commission_amount = base_amount × rate(rule commission_rate);
 * base_amount 溯源:集采降本=Σ(市场价-集采价)、成交贡献=合同成交额;
 * 幂等键 uk(period,user_id,type,source_ref)避免重复计提。
 */
@Data
@TableName("yc_rent_commission")
public class Commission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String period;

    private Long userId;

    private String userName;

    private String role;

    /** 集采降本/成交贡献 */
    private String type;

    private BigDecimal baseAmount;

    private BigDecimal rate;

    private BigDecimal commissionAmount;

    private String fundedFrom;

    /** purchase:{id}/contract:{id} */
    private String sourceRef;

    private Integer orderCount;

    private String remark;

    private LocalDateTime createTime;
}
