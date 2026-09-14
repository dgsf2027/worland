package top.aole.rent.modules.asset.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 工程量清单行内改价入参:只改数量与单价,合价恢复按 数量×单价 自动计算。
 */
@Data
public class BomPricingRequest {

    @NotNull(message = "数量必填")
    @DecimalMin("0.01")
    @Digits(integer = 7, fraction = 2)
    private BigDecimal qty;

    /** 单价(元);null = 未计价 */
    @DecimalMin("0")
    @Digits(integer = 16, fraction = 2)
    private BigDecimal unitCost;
}
