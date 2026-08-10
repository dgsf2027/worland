package top.aole.rent.modules.task.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务中心 DTO 容器(M5-01)。派单/转派/完成/列表。
 */
public class TaskDtos {

    /** 派单请求(手动派单)。 */
    @Data
    public static class DispatchRequest {
        @NotBlank(message = "任务标题必填")
        private String title;
        private String type;
        /** 承接角色(角色绑定·必填其一:角色或人) */
        private String assigneeRole;
        private Long assigneeUserId;
        private String assigneeUserName;
        private String priority;
        private String bizType;
        private Long bizId;
        private LocalDate dueDate;
        /** 完成是否需校验证据 */
        private Boolean verifyRequired;
        private String remark;
    }

    /** 转派请求。 */
    @Data
    public static class TransferRequest {
        /** 转派到角色 */
        private String toRole;
        private Long toUserId;
        private String toUserName;
        @NotBlank(message = "转派理由必填(留痕)")
        private String reason;
    }

    /** 完成请求(verify_required=1 时 evidence 必填)。 */
    @Data
    public static class CompleteRequest {
        private String evidence;
    }

    /** 转派留痕行。 */
    @Data
    public static class TransferLogItem {
        private String from;
        private String to;
        private String at;
        private String by;
        private String reason;
    }

    @Data
    public static class TaskItem {
        private Long id;
        private String no;
        private String title;
        private String type;
        private String assigneeRole;
        private Long assigneeUserId;
        private String assigneeUserName;
        private String source;
        private String status;
        private String priority;
        private String bizType;
        private Long bizId;
        private LocalDate dueDate;
        private Boolean overdue;
        private Boolean verifyRequired;
        private String verifyEvidence;
        private String creatorName;
        private LocalDateTime finishedAt;
        private String remark;
        private List<TransferLogItem> transferLog;
        private LocalDateTime createTime;
    }
}
