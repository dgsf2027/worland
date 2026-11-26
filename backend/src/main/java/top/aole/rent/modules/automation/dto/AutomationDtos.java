package top.aole.rent.modules.automation.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动化中心与银行流水智能核销 DTO（V132）。
 */
public final class AutomationDtos {

    private AutomationDtos() {
    }

    @Data
    public static class RuleUpdateRequest {
        private Boolean enabled;
        private Integer leadDays;
        private BigDecimal thresholdAmount;
        private String targetRole;
    }

    @Data
    public static class ChannelSaveRequest {
        private Long id;
        @NotBlank(message = "通道名称必填")
        private String name;
        @NotBlank(message = "通道类型必填(wecom/dingtalk/feishu/generic)")
        private String channelType;
        @NotBlank(message = "Webhook地址必填")
        private String webhookUrl;
        private String secret;
        private String targetRole;
        private Boolean enabled;
    }

    @Data
    public static class RunResult {
        private String ruleKey;
        private String ruleName;
        private String status;
        private int affectedCount;
        private int pushedCount;
        private String summary;
        private String previewText;
        private long durationMs;
    }

    @Data
    public static class CollectionNotice {
        private Long billId;
        private String billNo;
        private Long contractId;
        private String contractNo;
        private String customerName;
        private Integer periodNo;
        private LocalDate dueDate;
        /** 负数=距到期天数(预提醒), 正数=已逾期天数 */
        private long overdueDays;
        /** T-3预提醒 / T+1温和催收 / T+7罚息升级 / T+15锁机收回预警 */
        private String level;
        private BigDecimal unpaidAmount;
        private BigDecimal estimatedPenalty;
        private BigDecimal totalDue;
        private String noticeText;
    }

    @Data
    public static class ManualStatementInput {
        private LocalDate txDate;
        private String counterpartyName;
        private BigDecimal amount;
        /** IN / OUT */
        private String direction;
        private String bankSerial;
        private String memo;
    }

    @Data
    public static class ImportResult {
        private String batchNo;
        private int totalRows;
        private int importedRows;
        private int duplicateRows;
        private int matchedRows;
        private int needConfirmRows;
    }

    @Data
    public static class ReconcileRequest {
        private List<Long> statementIds = new ArrayList<>();
    }

    @Data
    public static class ReassignRequest {
        private Long billId;
    }

    @Data
    public static class ReconcileResult {
        private int requested;
        private int succeeded;
        private int failed;
        private List<String> messages = new ArrayList<>();
    }

    @Data
    public static class UnpaidBillOption {
        private Long billId;
        private String billNo;
        private String contractNo;
        private String customerName;
        private Integer periodNo;
        private LocalDate dueDate;
        private BigDecimal remainingAmount;
        private String status;
    }
}
