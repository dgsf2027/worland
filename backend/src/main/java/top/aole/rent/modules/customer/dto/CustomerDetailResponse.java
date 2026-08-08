package top.aole.rent.modules.customer.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 客户详情:信用画像(多维+评级) + 价值LTV & 风险敞口 + 跟进时间线 + 风控准入建议。业务流§8.3。
 */
@Data
public class CustomerDetailResponse {

    private Long id;
    private String name;
    private String contact;
    private String phone;
    private String industry;
    private String phase;
    private String valueTier;
    private String ownerName;
    private Boolean sensitiveMasked;

    private CreditProfile creditProfile;
    private ValueExposure valueExposure;
    private List<FollowupItem> timeline;
    /** 风控准入结论(建议 + 已落定) */
    private Admission admission;

    @Data
    public static class CreditProfile {
        private Integer profit;
        private Integer cashflow;
        private Integer stability;
        private Integer history;
        private Integer industry;
        /** 加权信用分(即时算) */
        private Integer compositeScore;
        /** 评级 A/B/C(即时算) */
        private String rating;
    }

    @Data
    public static class ValueExposure {
        private Integer contractCount;
        private BigDecimal cumulativeRent;
        /** 累计利润 LTV(敏感) */
        private BigDecimal cumulativeProfit;
        private BigDecimal renewRate;
        private BigDecimal exposureAmount;
        private BigDecimal receivableOverdue;
        /** 占总应收集中度(即时算 = 本客户在租敞口/全量在租敞口) */
        private BigDecimal concentration;
    }

    @Data
    public static class FollowupItem {
        private String method;
        private String content;
        private String result;
        private String userName;
        private LocalDateTime followTime;
        private LocalDate nextFollowDate;
    }

    @Data
    public static class Admission {
        /** 由评级即时算的建议值 */
        private BigDecimal suggestCreditLimit;
        private BigDecimal suggestDepositMonths;
        private BigDecimal suggestTargetIrr;
        /** 已落定值(风控准入接口写入;敏感) */
        private BigDecimal approvedCreditLimit;
        private BigDecimal approvedDepositMonths;
        private BigDecimal approvedTargetIrr;
        private String note;
    }
}
