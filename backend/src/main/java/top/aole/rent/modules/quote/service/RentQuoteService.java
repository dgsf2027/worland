package top.aole.rent.modules.quote.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.quote.dto.QuoteRequest;
import top.aole.rent.modules.quote.dto.QuoteResponse;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * 报价测算规则引擎(S0-06 · P0-G)。冲刺0 招牌。
 *
 * <p><b>口径(设立方案书§二)</b>:以集采成本为初始流出、各期税后净现金流入为回流、期末加计转让价,
 * 所求月度内部收益率(IRR)的年化值 = 目标税后 IRR。等额本金式回收,本金逐月回流可复投,故用 IRR 而非简单年化。
 *
 * <p><b>税(小规模,价内口径)</b>:增值税 = 收入/(1+vat)×vat ; 附加 = 增值税×surtax ; 所得税 = 利润×inc。
 * 全部税率/转让率/租期/速算系数/杠杆参数由 {@link RuleConfigService} 从 rule_config 取,禁硬编码。
 *
 * <p><b>折旧口径</b>:集采成本按租期直线折旧到期末残值(=转让价),期末按账面转让(无处置损益、无所得税),
 * 月度所得税享折旧抵扣。此模型使 全生命周期税后净利/客户总付/税后 IRR 与方案书§10 定价表对平。
 */
@Service
@RequiredArgsConstructor
public class RentQuoteService {

    private final RuleConfigService rules;

    public QuoteResponse calc(QuoteRequest req) {
        LocalDate today = LocalDate.now();
        String category = req.getCategory().trim();
        String customerType = req.getCustomerType().trim();

        // ---- 从 rule_config 取口径常量 ----
        double vatRate = dv(rules.getValue("tax_vat", today));
        double surtaxRate = dv(rules.getValue("tax_surtax", today));
        double incRate = dv(rules.getValue("tax_income", today));
        double transferRate = dv(rules.getValue("transfer_rate", category, today));

        int term = req.getTermMonths() != null ? req.getTermMonths()
                : (int) dv(rules.getValue("term_months", category, today));
        if (term <= 0) {
            throw new BizException(400, "租期须为正");
        }

        double targetIrr = req.getTargetIrr() != null ? dv(req.getTargetIrr())
                : dv(rules.getValue("target_irr", customerType, today));

        double market = dv(req.getMarketPrice());
        double purchase = req.getPurchasePrice() != null ? dv(req.getPurchasePrice())
                : market * dv(rules.getValue("purchase_cost_ratio", today));
        double transfer = market * transferRate;

        // ---- 求解精确月租(层级一税后 IRR = 目标) ----
        double rent = solveRentForIrr(targetIrr, term, transfer, purchase, vatRate, surtaxRate, incRate);
        double actualIrr = annualize(irrMonthly(buildCashflows(rent, term, transfer, purchase, vatRate, surtaxRate, incRate)));

        // ---- 客户总付 / 税后净利(全生命周期聚合口径,对平定价表) ----
        double totalRev = rent * term + transfer;
        double vat = totalRev / (1 + vatRate) * vatRate;
        double surtax = vat * surtaxRate;
        double pretax = totalRev - purchase - vat - surtax;
        double incomeTax = pretax * incRate;
        double netProfit = pretax - incomeTax;

        // ---- 速算系数(方案书§10.3,仅作起价建议) ----
        int irrPct = (int) Math.round(targetIrr * 100);
        String coeffScope = category + ":" + irrPct;
        double speedCoeff = safeRate("speed_coeff", coeffScope, today);
        double quickRent = speedCoeff > 0 ? market * speedCoeff : 0;

        // ---- 三层回报(方案书§四,校准自杠杆临界点表) ----
        double supplierLev = dv(rules.getValue("supplier_leverage", today));
        double a = dv(rules.getValue("financing_leverage_a", today));
        double b = dv(rules.getValue("financing_leverage_b", today));
        double layer1 = actualIrr;                 // = base
        double layer2 = layer1 * supplierLev;
        double layer3 = layer1 * a + b;

        // ---- 价值定价两校验(P0-G) ----
        double laborValue = dv(req.getMonthlyLaborValue());
        double paybackCeiling = dv(rules.getValue("payback_ceiling_months", today));
        double payback = market / laborValue;
        boolean paybackPass = payback <= paybackCeiling;
        double netBenefit = laborValue - rent;
        boolean benefitPass = netBenefit > 0;

        // ---- 组装 ----
        QuoteResponse r = new QuoteResponse();
        r.setMarketPrice(money(market));
        r.setPurchasePrice(money(purchase));
        r.setCategory(category);
        r.setCustomerType(customerType);
        r.setTermMonths(term);
        r.setTargetIrr(rate(targetIrr));

        r.setMonthlyRent(money(rent));
        r.setMonthlyRentQuick(quickRent > 0 ? money(quickRent) : null);
        r.setSpeedCoeff(speedCoeff > 0 ? rate(speedCoeff) : null);
        r.setTransferPrice(money(transfer));
        r.setTransferRate(rate(transferRate));
        r.setCustomerTotalPay(money(totalRev));
        r.setAfterTaxNetProfit(money(netProfit));
        r.setAfterTaxIrr(rate(actualIrr));

        r.setLayer1Return(rate(layer1));
        r.setLayer2Return(rate(layer2));
        r.setLayer3Return(rate(layer3));

        r.setPaybackMonths(BigDecimal.valueOf(payback).setScale(1, RoundingMode.HALF_UP));
        r.setPaybackPass(paybackPass);
        r.setMonthlyNetBenefit(money(netBenefit));
        r.setBenefitPass(benefitPass);
        r.setValuePricingPass(paybackPass && benefitPass);

        r.setTaxVat(money(vat));
        r.setTaxSurtax(money(surtax));
        r.setTaxIncome(money(incomeTax));

        // ---- 单台首付杠杆(仅当传 firstPayRatio):占用资金精确对平 ----
        if (req.getFirstPayRatio() != null) {
            double fpr = dv(req.getFirstPayRatio());
            double depositMonths = dv(rules.getValue("deposit_months", today));
            double firstPay = fpr * purchase;
            double deposit = depositMonths * rent;
            double occupied = firstPay - deposit;
            r.setFirstPayRatio(rate(fpr));
            r.setFirstPay(money(firstPay));
            r.setDeposit(money(deposit));
            r.setOccupiedCapital(money(occupied));
            r.setLeverageNote("实际资金占用 = 首付 − 押金(" + (int) depositMonths + "个月租金)。"
                    + "单台杠杆 IRR 依赖供应商账期偿付计划(账期越短占用越高),该计划在 M1-12 采购应付/M3-05 现金流驾驶舱明确后可精确出数;"
                    + "占用资金已按方案书§4.3 口径精确对平。");
        }

        r.setNote("月租=层级一税后IRR求解;税后净利/客户总付/税后IRR 对平方案书§10;三层回报校准自§4.4 杠杆临界点表。数字全部来自 rule_config。");
        return r;
    }

    // ============ IRR 引擎 ============

    /** 给定目标年化 IRR,二分求月租(层级一全自有,单调:月租↑→IRR↑)。 */
    private double solveRentForIrr(double targetAnnual, int term, double transfer, double purchase,
                                   double vatRate, double surtaxRate, double incRate) {
        double targetMonthly = Math.pow(1 + targetAnnual, 1.0 / 12) - 1;
        double lo = 100, hi = 100000;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            double m = irrMonthly(buildCashflows(mid, term, transfer, purchase, vatRate, surtaxRate, incRate));
            if (m < targetMonthly) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    /** 层级一税后现金流:t0 流出集采;每月税后净现金;期末加转让价(账面转让无处置税)。 */
    private double[] buildCashflows(double rent, int term, double transfer, double purchase,
                                    double vatRate, double surtaxRate, double incRate) {
        double monthlyDepr = (purchase - transfer) / term;   // 直线折旧到期末残值=转让价
        double[] cfs = new double[term + 1];
        cfs[0] = -purchase;
        for (int t = 1; t <= term; t++) {
            double vat = rent / (1 + vatRate) * vatRate;
            double surtax = vat * surtaxRate;
            double taxable = rent - monthlyDepr - vat - surtax;
            double inc = taxable * incRate;
            double cf = rent - vat - surtax - inc;
            if (t == term) {
                double tvat = transfer / (1 + vatRate) * vatRate;
                double tsurtax = tvat * surtaxRate;
                cf += transfer - tvat - tsurtax;
            }
            cfs[t] = cf;
        }
        return cfs;
    }

    /** 月度 IRR(二分;NPV 随利率单调递减)。 */
    private double irrMonthly(double[] cfs) {
        double lo = -0.9, hi = 1.0;
        for (int i = 0; i < 300; i++) {
            double mid = (lo + hi) / 2;
            if (npv(mid, cfs) > 0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    private double npv(double rate, double[] cfs) {
        double v = 0;
        for (int i = 0; i < cfs.length; i++) {
            v += cfs[i] / Math.pow(1 + rate, i);
        }
        return v;
    }

    private double annualize(double monthly) {
        return Math.pow(1 + monthly, 12) - 1;
    }

    // ============ 工具 ============

    private double dv(BigDecimal b) {
        return b.doubleValue();
    }

    private double safeRate(String key, String scope, LocalDate date) {
        try {
            return dv(rules.getValue(key, scope, date));
        } catch (BizException e) {
            return 0;   // 速算系数缺失不阻断(仅少个起价建议)
        }
    }

    private BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }
}
