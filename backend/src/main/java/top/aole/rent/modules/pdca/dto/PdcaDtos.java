package top.aole.rent.modules.pdca.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * PDCA 改进循环返回体(M5-05 · §流程11)。看板(每指标红绿灯)+ 改进项 CRUD + 到期回查 + AI 综述。
 */
public class PdcaDtos {

    /** 六指标看板:每指标当前值 vs 目标 → 红绿灯 */
    @Data
    public static class BoardResp {
        private List<Indicator> indicators = new ArrayList<>();
        private int redCount;
        private int greenCount;
        /** AI 综述(脱敏·只说趋势与动作·不出数字·LlmGateway mock) */
        private String aiText;
        private Long llmCallId;
        private String model;
        private boolean mock;
        private boolean cacheHit;
    }

    @Data
    public static class Indicator {
        private String metricKey;
        private String label;
        private BigDecimal value;
        private BigDecimal target;
        private String compareOp;
        private String unit;
        /** green(达标)/ red(未达)/ gray(取不到) */
        private String light;
        private String note;
        private String source;
    }

    /** 改进项行 */
    @Data
    public static class ItemRow {
        private Long id;
        private String no;
        private String metricScene;
        private String issue;
        private String action;
        private String metricKey;
        private String metricParam;
        private BigDecimal targetValue;
        private String compareOp;
        private BigDecimal baselineValue;
        private BigDecimal verifyValue;
        private String verifyResult;
        private String verifyNote;
        private LocalDate recheckDate;
        private boolean dueOrOverdue;
        private String ownerRole;
        private String ownerUserName;
        private String status;
        private Long taskId;
        private Integer aiDraft;
        private String creatorName;
        private LocalDateTime createTime;
    }

    /** 登记/编辑改进项入参 */
    @Data
    public static class ItemSaveReq {
        @NotBlank(message = "来源指标环节必填")
        private String metricScene;
        @NotBlank(message = "问题描述必填")
        private String issue;
        @NotBlank(message = "改进措施必填")
        private String action;
        /** 验证指标键(留空=需人工判定) */
        private String metricKey;
        private String metricParam;
        private BigDecimal targetValue;
        /** >= / <=(留空默认按指标注册表方向) */
        private String compareOp;
        private LocalDate recheckDate;
        private String ownerRole;
        private Long ownerUserId;
        private String ownerUserName;
        private String remark;
    }

    /** 单条回查结果 */
    @Data
    public static class RecheckResp {
        private Long id;
        private BigDecimal targetValue;
        private BigDecimal verifyValue;
        private String verifyResult;
        private String newStatus;
        private String note;
    }

    /** 批量到期回查汇总 */
    @Data
    public static class RecheckBatchResp {
        private int total;
        private int passed;
        private int failed;
        private int manual;
        private List<RecheckResp> results = new ArrayList<>();
    }

    /** 指标注册表行(新建下拉) */
    @Data
    public static class MetricDefRow {
        private String key;
        private String label;
        private String unit;
        private String compareOp;
        private BigDecimal defaultTarget;
        private BigDecimal currentValue;
        private String note;
    }
}
