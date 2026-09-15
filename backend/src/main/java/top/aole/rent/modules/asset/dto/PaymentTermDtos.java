package top.aole.rent.modules.asset.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 设备合同付款条件(自定义多段)与逐台应付视图。
 */
public final class PaymentTermDtos {

    private PaymentTermDtos() {
    }

    /** 一段付款条件 */
    @Data
    public static class TermInput {
        @NotBlank(message = "阶段名称必填")
        @Size(max = 16, message = "阶段名称最长 16 字")
        private String stageName;

        /** 比例(0-1) */
        @DecimalMin(value = "0", inclusive = false, message = "比例须大于 0")
        @DecimalMax(value = "1", message = "比例不能超过 100%")
        private BigDecimal ratio;

        /** 触发时点:下单/入库 */
        private String triggerPoint;

        @Min(0)
        private Integer dueDays;

        public TermInput() {
        }

        public TermInput(String stageName, BigDecimal ratio, String triggerPoint, Integer dueDays) {
            this.stageName = stageName;
            this.ratio = ratio;
            this.triggerPoint = triggerPoint;
            this.dueDays = dueDays;
        }
    }

    @Data
    public static class SaveRequest {
        @NotEmpty(message = "至少一段付款条件")
        @Valid
        private List<TermInput> terms;
    }

    /** 设备详情:付款条件 + 预计付款 + 对应应付 */
    @Data
    public static class PaymentPlan {
        /** 采购单(非采购建档为 null,不生成应付) */
        private Long purchaseInId;
        private String purchaseNo;
        private String purchaseStatus;
        /** 预计付款基数 = 集采价(敏感) */
        private BigDecimal basePrice;
        private List<TermLine> terms = new ArrayList<>();
        /** 比例合计(0-1) */
        private BigDecimal ratioTotal;
        /** 预计付款合计(敏感) */
        private BigDecimal expectedTotal;
        /** 本设备应付:待付合计 / 已付合计(敏感) */
        private BigDecimal pendingTotal;
        private BigDecimal paidTotal;
        /** 未设置条件时的默认模板(首付/验收/尾款) */
        private List<TermInput> defaultTemplate = new ArrayList<>();
        /** 说明(如未关联采购单、旧版整单应付) */
        private String note;
    }

    @Data
    public static class TermLine {
        private Long id;
        private Integer seq;
        private String stageName;
        private BigDecimal ratio;
        private String triggerPoint;
        private Integer dueDays;
        /** 预计付款金额 = 集采价 × 比例(末段补差;敏感) */
        private BigDecimal expectedAmount;
        /** 对应应付(未生成为 null) */
        private Long payableId;
        private BigDecimal payableAmount;
        private LocalDate payableDueDate;
        private String payableStatus;
    }
}
