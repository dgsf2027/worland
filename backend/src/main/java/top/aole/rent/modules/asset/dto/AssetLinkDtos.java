package top.aole.rent.modules.asset.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.Min;
import java.math.BigDecimal;

/**
 * 设备:单台收益手工覆盖 / 意向承接客户。
 */
public final class AssetLinkDtos {

    private AssetLinkDtos() {
    }

    /** 单台收益手工覆盖;某项为 null 表示恢复自动计算 */
    @Data
    public static class SingleUnitReturnRequest {
        @DecimalMin("0") @Digits(integer = 16, fraction = 2)
        private BigDecimal allocRent;
        @DecimalMin("0") @Digits(integer = 16, fraction = 2)
        private BigDecimal cumulativeRent;
        /** 回报率(小数,0.12 = 12%) */
        @Digits(integer = 9, fraction = 8)
        private BigDecimal returnRate;
        @Min(0)
        private Integer inServiceDays;
        @Min(0)
        private Integer idleDays;
    }

    /** 意向承接客户;customerId 为 null 表示清除 */
    @Data
    public static class IntendedCustomerRequest {
        private Long customerId;
    }
}
