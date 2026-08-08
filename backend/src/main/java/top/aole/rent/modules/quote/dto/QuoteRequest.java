package top.aole.rent.modules.quote.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 报价测算请求(POST /api/rent/quote/calc)。
 */
@Data
public class QuoteRequest {

    @ApiModelProperty(value = "设备市场价(元)", example = "200000", required = true)
    @NotNull(message = "市场价必填")
    @Positive(message = "市场价须为正")
    private BigDecimal marketPrice;

    @ApiModelProperty(value = "品类:播种墙 / 货架", example = "播种墙", required = true)
    @NotBlank(message = "品类必填")
    private String category;

    @ApiModelProperty(value = "客户类型:云山快仓 / 其他", example = "云山快仓", required = true)
    @NotBlank(message = "客户类型必填")
    private String customerType;

    @ApiModelProperty(value = "月替代人工价值/月节省(元) —— 价值定价必填(P0-G)", example = "8300", required = true)
    @NotNull(message = "月替代人工价值(monthlyLaborValue)必填 —— 价值定价校验依据")
    @Positive(message = "月节省须为正")
    private BigDecimal monthlyLaborValue;

    @ApiModelProperty(value = "首付比例(0-1),用于单台杠杆测算;不填=只出层级一全自有", example = "0.30")
    private BigDecimal firstPayRatio;

    @ApiModelProperty(value = "租期(月),不填按品类默认(播种墙36/货架60)", example = "36")
    private Integer termMonths;

    @ApiModelProperty(value = "真实集采成本(元),不填=市场价×默认集采比(0.9)", example = "180000")
    private BigDecimal purchasePrice;

    @ApiModelProperty(value = "目标税后IRR 覆盖(如其他客户取 0.35);不填按客户类型默认", example = "0.25")
    private BigDecimal targetIrr;
}
