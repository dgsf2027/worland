package top.aole.rent.modules.approval.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 审批(M5-02)。投放审批:本金回报率(层级①)≥ 目标方可发起;金额 ≤ 自主额度(300万)→自主,超额→协商。
 *
 * <p><b>口径(§4.24)</b>:审批以 principal_return_rate 为准(不达标不允许进审批);
 * decision_mode 由 amount 与 self_limit(rule approval_self_limit)自动判定,不硬编码;
 * 通过/驳回为敏感操作,由 {@link top.aole.rent.common.auth.RequireRole} 统一切面卡「老板」(P0-D)。
 */
@Data
@TableName("yc_rent_approval")
public class Approval {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String no;

    /** 投放审批 */
    private String type;

    /** purchase_in/contract */
    private String bizType;

    private Long bizId;

    private String subject;

    private BigDecimal amount;

    /** 本金回报率(层级①·审批以此为准) */
    private BigDecimal principalReturnRate;

    /** 目标本金回报率(达标线快照) */
    private BigDecimal targetRate;

    /** 自主额度上限快照(元) */
    private BigDecimal selfLimit;

    /** 自主(≤300万)/协商(超额) */
    private String decisionMode;

    /** 待审批/已通过/已驳回 */
    private String status;

    private Long applicantId;

    private String applicantName;

    private Long approverId;

    private String approverName;

    private LocalDateTime approvedAt;

    private String decisionReason;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
