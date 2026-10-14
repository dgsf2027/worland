package top.aole.rent.modules.quote.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

/**
 * 报价测算请求(POST /api/rent/quote/calc),按「曜石 货架/播种墙测算表(简版)」口径。
 * 不填的参数取 rule_config 默认(quote_* 与既有 term_months/transfer_rate)。
 * 基准价、框架折扣、采购价、货位数等商业数据不入库,由调用方每次输入。
 */
@Data
public class QuoteRequest {

    @ApiModelProperty(value = "品类:播种墙 / 货架", example = "播种墙", required = true)
    @NotBlank(message = "品类必填")
    private String category;

    @ApiModelProperty(value = "客户类型:云山快仓 / 其他·标准 / 其他·高(决定征收率与默认目标 IRR)",
            example = "其他·标准", required = true)
    @NotBlank(message = "客户类型必填")
    private String customerType;

    @ApiModelProperty(value = "客户自购价 / 市场价(含运安,元)", example = "300000", required = true)
    @NotNull(message = "市场价必填")
    @Positive(message = "市场价须为正")
    private BigDecimal marketPrice;

    // ---- 采购成本:直接给,或由 折扣基数×(1−折扣率)+运安费 算出 ----
    @ApiModelProperty(value = "采购成本(含税,元);不填则用 折扣基数、折扣率、运安费 计算")
    @Positive(message = "采购成本须为正")
    private BigDecimal purchasePrice;

    @ApiModelProperty(value = "折扣基数(不含运安,元),与折扣率配合", example = "290000")
    @Positive(message = "折扣基数须为正")
    private BigDecimal discountBase;

    @ApiModelProperty(value = "框架折扣率(0-1)", example = "0.2")
    @PositiveOrZero(message = "折扣率不能为负")
    private BigDecimal discountRate;

    @ApiModelProperty(value = "运费 + 安装(元),默认 0", example = "7000")
    @PositiveOrZero(message = "运安费不能为负")
    private BigDecimal freightInstall;

    // ---- 合同口径(不填取默认) ----
    @ApiModelProperty(value = "租期(月),默认按品类(播种墙 36/货架 60)", example = "36")
    @Positive(message = "租期须为正")
    private Integer termMonths;

    @ApiModelProperty(value = "起租预付款比例(占市场价),默认按品类", example = "0.1")
    @PositiveOrZero(message = "预付比例不能为负")
    private BigDecimal prepayRatio;

    @ApiModelProperty(value = "期末转让比例(占市场价),默认按品类", example = "0.1")
    @PositiveOrZero(message = "转让比例不能为负")
    private BigDecimal transferRate;

    @ApiModelProperty(value = "目标税后 IRR,默认按客户类型(25%/30%/35%)", example = "0.3")
    @Positive(message = "目标 IRR 须为正")
    private BigDecimal targetIrr;

    @ApiModelProperty(value = "月租(元);不填则按目标 IRR 反解并向上取整到整元", example = "7830")
    @Positive(message = "月租须为正")
    private BigDecimal monthlyRent;

    // ---- 成本视角 ----
    @ApiModelProperty(value = "保外维保(元/年),默认 0", example = "3000")
    @PositiveOrZero(message = "保外维保不能为负")
    private BigDecimal maintenancePerYear;

    @ApiModelProperty(value = "供应商质保期(月),默认 0", example = "12")
    @PositiveOrZero(message = "质保期不能为负")
    private Integer warrantyMonths;

    @ApiModelProperty(value = "资金成本(年),默认 rule_config", example = "0.06")
    @PositiveOrZero(message = "资金成本不能为负")
    private BigDecimal capitalCostRate;

    @ApiModelProperty(value = "管理费率,默认 rule_config", example = "0.05")
    @PositiveOrZero(message = "管理费率不能为负")
    private BigDecimal mgmtFeeRate;

    // ---- 客户视角 ----
    @ApiModelProperty(value = "客户是否一般纳税人(决定能否抵进项),默认 true")
    private Boolean customerGeneralTaxpayer;

    @ApiModelProperty(value = "播种墙每台替代人工(人)", example = "2")
    @PositiveOrZero(message = "替代人数不能为负")
    private BigDecimal replaceHeadcount;

    @ApiModelProperty(value = "人工月成本(元/人·月),默认 rule_config", example = "4150")
    @Positive(message = "人工月成本须为正")
    private BigDecimal laborCostMonthly;

    @ApiModelProperty(value = "货架货位数(算单位月租)", example = "2000")
    @Positive(message = "货位数须为正")
    private Integer slots;
}
