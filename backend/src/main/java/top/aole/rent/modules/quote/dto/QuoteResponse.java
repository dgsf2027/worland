package top.aole.rent.modules.quote.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 报价测算结果(10-14 起按「曜石 货架/播种墙测算表(简版)」口径)。
 * 三个视角:租赁公司(税后 IRR 与利润)/ 客户(租 vs 买)/ 实际成本(全成本与保本)。
 * 金额 2 位小数,比率 6 位小数;所有口径常量来自 rule_config(quote_*),不写死。
 */
@Data
public class QuoteResponse {

    // ---- 回显输入 ----
    private String category;
    private String customerType;
    private BigDecimal marketPrice;
    /** 采购成本(含税) */
    private BigDecimal purchasePrice;
    private Integer termMonths;
    private BigDecimal targetIrr;
    /** 起租预付款 = 市场价 × 预付比例 */
    private BigDecimal prepayment;

    // ---- 核心报价 ----
    /** 月租(元);rentSolved=true 表示由目标税后 IRR 反解后向上取整到整元 */
    private BigDecimal monthlyRent;
    private Boolean rentSolved;
    /** 期末转让价 = 市场价 × 转让率 */
    private BigDecimal transferPrice;
    private BigDecimal transferRate;

    private Lessor lessor = new Lessor();
    private Customer customer = new Customer();
    private Cost cost = new Cost();

    private List<String> notes = new ArrayList<>();

    /** 租赁公司视角 */
    @Data
    public static class Lessor {
        /** 收款总额(含税) = 预付 + 月租×期数 + 转让价 */
        private BigDecimal totalCollected;
        /** 开票征收率 */
        private BigDecimal collectRate;
        private BigDecimal vat;
        private BigDecimal surtax;
        private BigDecimal incomeTax;
        /** 税后净利(尚未扣保外维保、资金成本、管理费) */
        private BigDecimal netProfit;
        private BigDecimal preTaxIrr;
        private BigDecimal afterTaxIrr;
        /** 税后 IRR ≥ 目标 − 0.5 个百分点 */
        private Boolean meetsTarget;
        /** 回本月数(近似) = (采购成本−预付) ÷ 每月税后租金,不含转让价 */
        private Integer paybackMonths;
        /** 税费系数 k = 征收率/(1+征收率)×(1+附加税率) */
        private BigDecimal taxFactor;
    }

    /** 客户视角(租 vs 买) */
    @Data
    public static class Customer {
        /** 租赁总付(含税) */
        private BigDecimal totalPay;
        private BigDecimal extraPay;
        private BigDecimal extraRatio;
        /** 自购可抵进项(客户一般纳税人) */
        private BigDecimal buyInputTax;
        /** 租赁可抵进项 */
        private BigDecimal leaseInputTax;
        private BigDecimal afterTaxExtra;
        private BigDecimal afterTaxExtraRatio;
        /** 隐含年化融资成本(税前) */
        private BigDecimal impliedFinanceCost;

        // ---- 播种墙:替代人工 ----
        private BigDecimal replaceHeadcount;
        private BigDecimal monthlySaving;
        /** 客户月净收益 = 月节省人工 − 月租 */
        private BigDecimal monthlyNetBenefit;
        /** 自购回本月数 = 自购价 ÷ 月节省人工 */
        private BigDecimal buyPaybackMonths;
        /** 校验①:自购回本期 ≤ 准入上限(18 月) */
        private Boolean paybackPass;
        /** 校验②:客户月净收益 > 0 */
        private Boolean benefitPass;
        /** 两校验全过(可解锁生成合同) */
        private Boolean valuePricingPass;

        // ---- 货架:单位月租 ----
        /** 单位月租(元/货位·月),未填货位数为空 */
        private BigDecimal unitRent;
        /** 自购价折合每月 = 自购价 ÷ 租期 */
        private BigDecimal buyPricePerMonth;

        private String verdict;
    }

    /** 实际成本视角 */
    @Data
    public static class Cost {
        /** 收入(扣销项税) */
        private BigDecimal revenueExVat;
        /** 附加税 + 所得税 */
        private BigDecimal taxCost;
        private BigDecimal maintenance;
        /** 资金占用成本 = Σ(累计税后现金流为负的月份占用额) × 资金成本 ÷ 12 */
        private BigDecimal capitalCost;
        private BigDecimal mgmtFee;
        private BigDecimal totalCost;
        private BigDecimal actualProfit;
        private BigDecimal profitMargin;
        private BigDecimal avgMonthlyCost;
        private BigDecimal breakEvenRent;
        private BigDecimal safetyMargin;
        private BigDecimal peakCapital;
    }
}
