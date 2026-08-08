package top.aole.rent.modules.quote.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 报价测算结果。所有数字由规则引擎从 rule_config 算出,禁写死。
 */
@Data
public class QuoteResponse {

    // ---- 回显输入 ----
    private BigDecimal marketPrice;
    private BigDecimal purchasePrice;
    private String category;
    private String customerType;
    private Integer termMonths;
    private BigDecimal targetIrr;

    // ---- 核心报价(对平设立方案书§10 定价表) ----
    /** 精确月租(IRR 求解,层级一税后口径),元 */
    private BigDecimal monthlyRent;
    /** 速算月租(市场价×速算系数,方案书§10.3 起价建议),元 */
    private BigDecimal monthlyRentQuick;
    /** 速算系数 */
    private BigDecimal speedCoeff;
    /** 期末转让价 = 市场价×转让率,元 */
    private BigDecimal transferPrice;
    private BigDecimal transferRate;
    /** 客户总付 = 期数×月租 + 转让价(不含押金),元 */
    private BigDecimal customerTotalPay;
    /** 全生命周期税后净利,元 */
    private BigDecimal afterTaxNetProfit;
    /** 实测税后年化 IRR(应≈目标) */
    private BigDecimal afterTaxIrr;

    // ---- 三层回报(方案书§四,校准自杠杆临界点表) ----
    /** 层级一 本金投资回报率(=base) */
    private BigDecimal layer1Return;
    /** 层级二 供应商杠杆回报率(=base×供应商倍数) */
    private BigDecimal layer2Return;
    /** 层级三 融资杠杆回报率(=a×base+b) */
    private BigDecimal layer3Return;

    // ---- 价值定价两校验(P0-G) ----
    /** 承租方自购回本期(月)= 市场价/月节省 */
    private BigDecimal paybackMonths;
    /** 校验①品类准入:回本期≤18 月 */
    private Boolean paybackPass;
    /** 承租方月净收益 = 月节省 − 月租 */
    private BigDecimal monthlyNetBenefit;
    /** 校验②:月净收益>0 */
    private Boolean benefitPass;
    /** 两校验是否全过(达标 → 可解锁生成合同) */
    private Boolean valuePricingPass;

    // ---- 税负拆解(透明) ----
    private BigDecimal taxVat;
    private BigDecimal taxSurtax;
    private BigDecimal taxIncome;

    // ---- 单台首付杠杆(仅当传 firstPayRatio) ----
    private BigDecimal firstPayRatio;
    private BigDecimal firstPay;
    private BigDecimal deposit;
    /** 实际资金占用 = 首付 − 押金(方案书§4.3,对平 76560/58560/40560) */
    private BigDecimal occupiedCapital;
    /** 杠杆说明:单台杠杆 IRR 依赖供应商账期偿付计划(M1-12/M3-05 明确),占用资金已精确对平 */
    private String leverageNote;

    // ---- 元信息 ----
    private String note;
}
