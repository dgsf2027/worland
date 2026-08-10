package top.aole.rent.modules.distribution.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 结账分配头(M3-03/04)。一 period 一条 active:
 * 提取前净利 → 管理费阶梯 → 可分配 → 50%现金/50%滚存 → 留存下限校验。
 *
 * <p>§4.24 单一真相源:阶梯/分配比/留存下限走 rule_config;每人份额按快照×investor.ratio 即时算。
 * <p>§十一 D 幂等:同 period 已有 active → 拒;{@code reverses_id} 唯一约束 = 冲销幂等键(force 重算先冲销)。
 */
@Data
@TableName("yc_rent_rent_distribution")
public class Distribution {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String distributionNo;

    /** 分配期 YYYY-MM */
    private String period;

    private LocalDate bizDate;

    /** 实缴出资合计(=Σ investor.amount·回报率分母) */
    private BigDecimal totalCapital;

    /** 提取前净利(经营账收入−成本·管理费计提基数) */
    private BigDecimal profitBefore;

    /** 公司回报率(=profit_before/total_capital·选档依据) */
    private BigDecimal returnRate;

    /** 管理费率(阶梯档) */
    private BigDecimal mgmtFeeRate;

    /** 管理费(=profit_before×mgmt_fee_rate) */
    private BigDecimal mgmtFee;

    /** 可分配利润(=profit_before−mgmt_fee) */
    private BigDecimal distributable;

    /** 现金分配额(默认可分配×50%·留存不足压减) */
    @TableField("cash_50")
    private BigDecimal cash50;

    /** 滚存额(=distributable−cash_50) */
    @TableField("roll_50")
    private BigDecimal roll50;

    /** 留存下限(=max(20万,未来3月供应商净应付)) */
    private BigDecimal reserveFloor;

    /** 本次分配后留存(=roll_50;须≥reserve_floor) */
    private BigDecimal reserveAfter;

    /** 留存是否达标:1达标 0压减后仍不足 */
    private Integer reserveSufficient;

    /** active 生效 / reversed 已冲销 */
    private String status;

    /** 0 原始 / 1 冲销 */
    private Integer isReversal;

    /** 冲销指向的原分配 id(幂等键·唯一约束) */
    private Long reversesId;

    private Long operatorId;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
