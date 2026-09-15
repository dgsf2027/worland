package top.aole.rent.modules.customer.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import java.math.BigDecimal;

/**
 * 客户详情分块编辑:信用画像五维 / 客户价值手工项。
 */
public final class CustomerEditDtos {

    private CustomerEditDtos() {
    }

    /** 信用画像五维(0-100,可空=该维未评) */
    @Data
    public static class CreditRequest {
        @Min(0) @Max(100)
        private Integer profit;
        @Min(0) @Max(100)
        private Integer cashflow;
        @Min(0) @Max(100)
        private Integer stability;
        @Min(0) @Max(100)
        private Integer history;
        @Min(0) @Max(100)
        private Integer industry;
    }

    /** 客户价值手工项(其余指标按合同/收租单实时算) */
    @Data
    public static class ValueRequest {
        @Digits(integer = 16, fraction = 2)
        private BigDecimal cumulativeProfit;
        @DecimalMin("0") @DecimalMax("1")
        private BigDecimal renewRate;
    }
}
