package top.aole.rent.modules.approval.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 审批 DTO 容器(M5-02)。发起/裁决/列表。
 */
public class ApprovalDtos {

    /** 发起投放审批。 */
    @Data
    public static class InitiateRequest {
        private String bizType;
        private Long bizId;
        private String subject;
        @NotNull(message = "投放金额必填")
        private BigDecimal amount;
        @NotNull(message = "本金回报率必填(审批以此为准)")
        private BigDecimal principalReturnRate;
        /** 目标本金回报率(空=取 rule target_irr[其他]) */
        private BigDecimal targetRate;
        private String remark;
    }

    /** 裁决(通过/驳回)。 */
    @Data
    public static class DecisionRequest {
        private String reason;
    }

    @Data
    public static class ApprovalItem {
        private Long id;
        private String no;
        private String type;
        private String bizType;
        private Long bizId;
        private String subject;
        private BigDecimal amount;
        private BigDecimal principalReturnRate;
        private BigDecimal targetRate;
        private BigDecimal selfLimit;
        private String decisionMode;
        private String status;
        private String applicantName;
        private String approverName;
        private LocalDateTime approvedAt;
        private String decisionReason;
        private LocalDateTime createTime;
    }
}
