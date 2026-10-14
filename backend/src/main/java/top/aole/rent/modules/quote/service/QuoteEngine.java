package top.aole.rent.modules.quote.service;

import lombok.Data;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.quote.dto.QuoteResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 报价计算引擎(纯计算,无 IO):「曜石 货架/播种墙测算表(简版)」口径。
 *
 * <p><b>税(简易计税,采购 13% 专票不可抵扣)</b>:增值税 = 收款总额 ÷ (1+征收率) × 征收率;
 * 附加税 = 增值税 × 附加税率;所得税 = 所得税率 × max(0, 收款总额 − 增值税 − 附加税 − 采购成本),
 * 按租期月均摊。税费系数 k = 征收率/(1+征收率) × (1+附加税率)。
 *
 * <p><b>税后现金流</b>(月份 0 = 起租月):0 月 = −采购成本 + 预付×(1−k);1…T−1 月 = 月租×(1−k) − 所得税/T;
 * 第 T 月 = (月租+转让价)×(1−k) − 所得税/T。税后 IRR = (1+月 IRR)^12 − 1。税前现金流不含税。
 *
 * <p><b>月租</b>:不给则按目标税后 IRR 二分反解,<b>向上取整到整元</b>(保证 IRR 不低于目标);
 * 给了就直接算 IRR、利润并判是否达标(税后 IRR ≥ 目标 − 0.5 个百分点)。
 *
 * <p>全款口径:起租月一次付清设备款,不计供应商账期。与测算表 v3(增值税次月缴)比,IRR 差异约 0.1 个百分点以内。
 */
public final class QuoteEngine {

    private QuoteEngine() {
    }

    /** 达标容差:税后 IRR ≥ 目标 − 0.5 个百分点 */
    private static final double MEET_TOLERANCE = 0.005;

    @Data
    public static class Params {
        private String category;
        private String customerType;
        /** 客户自购价 / 市场价(含运安) */
        private double market;
        /** 采购成本(含税) */
        private double purchase;
        private int term;
        private double prepayRatio;
        private double transferRate;
        /** 开票征收率 */
        private double collectRate;
        private double surtaxRate;
        private double incomeTaxRate;
        private double capitalCostRate;
        private double mgmtFeeRate;
        private double maintPerYear;
        private int warrantyMonths;
        private double targetIrr;
        /** 月租;null = 按目标 IRR 反解 */
        private Double rent;
        private boolean customerGeneral;
        private double customerInputTax;
        /** 播种墙每台替代人工(人);null = 不评估客户价值 */
        private Double headcount;
        private double laborCost;
        /** 货架货位数 */
        private Integer slots;
        /** 自购回本期准入上限(月) */
        private double paybackCeiling;
    }

    public static QuoteResponse compute(Params p) {
        if (p.getTerm() <= 0) {
            throw new BizException(400, "租期须为正");
        }
        if (p.getMarket() <= 0) {
            throw new BizException(400, "市场价须为正");
        }
        if (p.getPurchase() <= 0) {
            throw new BizException(400, "采购成本须为正");
        }

        final int term = p.getTerm();
        final double market = p.getMarket();
        final double prepay = market * p.getPrepayRatio();
        final double transfer = market * p.getTransferRate();
        final double k = p.getCollectRate() / (1 + p.getCollectRate()) * (1 + p.getSurtaxRate());

        boolean solved = p.getRent() == null;
        double rent = solved ? solveRent(p, prepay, transfer, k) : p.getRent();
        Flow f = flow(p, rent, prepay, transfer, k);

        double afterIrr = annualize(irrMonthly(f.afterTax));
        double preIrr = annualize(irrMonthly(f.preTax));
        boolean meets = afterIrr >= p.getTargetIrr() - MEET_TOLERANCE;

        QuoteResponse r = new QuoteResponse();
        r.setCategory(p.getCategory());
        r.setCustomerType(p.getCustomerType());
        r.setMarketPrice(money(market));
        r.setPurchasePrice(money(p.getPurchase()));
        r.setTermMonths(term);
        r.setTargetIrr(rate(p.getTargetIrr()));
        r.setPrepayment(money(prepay));
        r.setMonthlyRent(money(rent));
        r.setRentSolved(solved);
        r.setTransferPrice(money(transfer));
        r.setTransferRate(rate(p.getTransferRate()));

        // ---- 租赁公司视角 ----
        QuoteResponse.Lessor l = r.getLessor();
        l.setTotalCollected(money(f.collected));
        l.setCollectRate(rate(p.getCollectRate()));
        l.setVat(money(f.vat));
        l.setSurtax(money(f.surtax));
        l.setIncomeTax(money(f.incomeTax));
        l.setNetProfit(money(f.netProfit));
        l.setPreTaxIrr(rate(preIrr));
        l.setAfterTaxIrr(rate(afterIrr));
        l.setMeetsTarget(meets);
        l.setTaxFactor(rate(k));
        l.setPaybackMonths((int) Math.ceil((p.getPurchase() - prepay) / (rent * (1 - k)) - 1e-12));

        // ---- 实际成本视角 ----
        double maint = p.getMaintPerYear() * Math.max(0, term - p.getWarrantyMonths()) / 12;
        double capital = capitalCost(f.afterTax, p.getCapitalCostRate());
        double mgmt = p.getMgmtFeeRate() * Math.max(0, f.netProfit - maint - capital);
        double actualProfit = f.netProfit - maint - capital - mgmt;
        double revenueExVat = f.collected - f.vat;
        double taxCost = f.surtax + f.incomeTax;
        double totalCost = p.getPurchase() + taxCost + maint + capital + mgmt;
        double breakEven = ((p.getPurchase() + maint + capital) / (1 - k) - prepay - transfer) / term;
        QuoteResponse.Cost c = r.getCost();
        c.setRevenueExVat(money(revenueExVat));
        c.setTaxCost(money(taxCost));
        c.setMaintenance(money(maint));
        c.setCapitalCost(money(capital));
        c.setMgmtFee(money(mgmt));
        c.setTotalCost(money(totalCost));
        c.setActualProfit(money(actualProfit));
        c.setProfitMargin(rate(actualProfit / revenueExVat));
        c.setAvgMonthlyCost(money(totalCost / term));
        c.setBreakEvenRent(money(breakEven));
        c.setSafetyMargin(rate(rent / breakEven - 1));
        c.setPeakCapital(money(peakCapital(f.afterTax)));

        // ---- 客户视角 ----
        fillCustomer(r.getCustomer(), p, rent, prepay, transfer, f.collected);

        // ---- 说明 ----
        if (solved) {
            r.getNotes().add("月租由目标税后 IRR 反解后向上取整到整元,税后 IRR 不低于目标。");
        }
        if (!meets) {
            r.getNotes().add("税后 IRR 低于目标 0.5 个百分点以上,未达标。");
        }
        QuoteResponse.Customer cu = r.getCustomer();
        if (Boolean.FALSE.equals(cu.getPaybackPass()) && cu.getBuyPaybackMonths() != null) {
            r.getNotes().add("客户自购回本期超过准入上限 " + (int) p.getPaybackCeiling() + " 个月,价值定价校验不通过。");
        }
        r.getNotes().add("口径:简易计税、全款口径(起租月一次付清设备款,不计供应商账期);增值税及附加当月缴纳,"
                + "所得税按租期月均摊。与测算表 v3(增值税次月缴)相比,IRR 差异约 0.1 个百分点以内。");
        return r;
    }

    // ============ 客户视角 ============

    private static void fillCustomer(QuoteResponse.Customer c, Params p, double rent, double prepay,
                                     double transfer, double collected) {
        int term = p.getTerm();
        double buy = p.getMarket();
        double totalPay = collected;   // 预付 + 月租×期数 + 转让价
        double flag = p.isCustomerGeneral() ? 1 : 0;
        double buyTax = flag * buy * p.getCustomerInputTax() / (1 + p.getCustomerInputTax());
        double leaseTax = flag * totalPay * p.getCollectRate() / (1 + p.getCollectRate());
        double afterExtra = (totalPay - leaseTax) - (buy - buyTax);

        c.setTotalPay(money(totalPay));
        c.setExtraPay(money(totalPay - buy));
        c.setExtraRatio(rate((totalPay - buy) / buy));
        c.setBuyInputTax(money(buyTax));
        c.setLeaseInputTax(money(leaseTax));
        c.setAfterTaxExtra(money(afterExtra));
        c.setAfterTaxExtraRatio(rate(afterExtra / (buy - buyTax)));
        c.setImpliedFinanceCost(rate(annualize(rateMonthly(term, rent, buy - prepay, transfer))));
        c.setBuyPricePerMonth(money(buy / term));

        boolean wall = "播种墙".equals(p.getCategory());
        if (wall) {
            if (p.getHeadcount() == null) {
                c.setVerdict("未填替代人数,无法评估客户价值");
                return;
            }
            double saving = p.getHeadcount() * p.getLaborCost();
            double net = saving - rent;
            c.setReplaceHeadcount(BigDecimal.valueOf(p.getHeadcount()).setScale(2, RoundingMode.HALF_UP));
            c.setMonthlySaving(money(saving));
            c.setMonthlyNetBenefit(money(net));
            boolean benefit = net > 0;
            boolean payback = false;
            if (saving > 0) {
                double months = buy / saving;
                c.setBuyPaybackMonths(BigDecimal.valueOf(months).setScale(1, RoundingMode.HALF_UP));
                payback = months <= p.getPaybackCeiling();
            }
            c.setPaybackPass(payback);
            c.setBenefitPass(benefit);
            c.setValuePricingPass(payback && benefit);
            c.setVerdict(benefit ? "租用当月即净省钱"
                    : "每台需替代≥" + String.format("%.1f", rent / p.getLaborCost()) + "人才不亏");
        } else {
            if (p.getSlots() != null && p.getSlots() > 0) {
                c.setUnitRent(money(rent / p.getSlots()));
            }
            c.setVerdict(rent <= buy / term ? "月租低于自购折月" : "月租高于自购折月,价值在免一次性投入");
        }
    }

    // ============ 现金流 ============

    private static final class Flow {
        double collected;
        double vat;
        double surtax;
        double incomeTax;
        double netProfit;
        double[] afterTax;
        double[] preTax;
    }

    private static Flow flow(Params p, double rent, double prepay, double transfer, double k) {
        int term = p.getTerm();
        Flow f = new Flow();
        f.collected = prepay + rent * term + transfer;
        f.vat = f.collected / (1 + p.getCollectRate()) * p.getCollectRate();
        f.surtax = f.vat * p.getSurtaxRate();
        f.incomeTax = p.getIncomeTaxRate() * Math.max(0, f.collected - f.vat - p.getPurchase() - f.surtax);
        f.netProfit = f.collected - f.vat - f.surtax - p.getPurchase() - f.incomeTax;
        f.afterTax = new double[term + 1];
        f.preTax = new double[term + 1];
        f.afterTax[0] = -p.getPurchase() + prepay * (1 - k);
        f.preTax[0] = -p.getPurchase() + prepay;
        for (int t = 1; t <= term; t++) {
            double income = t == term ? rent + transfer : rent;
            f.afterTax[t] = income * (1 - k) - f.incomeTax / term;
            f.preTax[t] = income;
        }
        return f;
    }

    /** 资金占用成本 = Σ(累计税后现金流为负的月份的占用额) × 资金成本 ÷ 12 */
    private static double capitalCost(double[] afterTax, double annualRate) {
        double cum = 0;
        double occupied = 0;
        for (double v : afterTax) {
            cum += v;
            if (cum < 0) {
                occupied += -cum;
            }
        }
        return occupied * annualRate / 12;
    }

    private static double peakCapital(double[] afterTax) {
        double cum = 0;
        double min = 0;
        for (double v : afterTax) {
            cum += v;
            min = Math.min(min, cum);
        }
        return -min;
    }

    // ============ 求解 ============

    /** 按目标税后 IRR 二分求月租,向上取整到整元。月租↑ → IRR↑(单调)。 */
    private static double solveRent(Params p, double prepay, double transfer, double k) {
        double lo = 1;
        double hi = 1_000_000;
        if (annualize(irrMonthly(flow(p, hi, prepay, transfer, k).afterTax)) < p.getTargetIrr()) {
            throw new BizException(400, "目标税后 IRR 过高,无法通过调整月租达到");
        }
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            double irr = annualize(irrMonthly(flow(p, mid, prepay, transfer, k).afterTax));
            if (irr < p.getTargetIrr()) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return Math.ceil((lo + hi) / 2 - 1e-9);
    }

    /** 月度 IRR(二分;NPV 随利率单调递减)。 */
    private static double irrMonthly(double[] cfs) {
        double lo = -0.9;
        double hi = 1.0;
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

    private static double npv(double rate, double[] cfs) {
        double v = 0;
        for (int i = 0; i < cfs.length; i++) {
            v += cfs[i] / Math.pow(1 + rate, i);
        }
        return v;
    }

    /** 等价 Excel RATE(n, -pmt, pv, -fv):解 pv − pmt×年金现值系数 − fv×(1+r)^−n = 0 的月利率。 */
    private static double rateMonthly(int n, double pmt, double pv, double fv) {
        double lo = -0.5;
        double hi = 1.0;
        for (int i = 0; i < 300; i++) {
            double r = (lo + hi) / 2;
            double annuity = Math.abs(r) < 1e-12 ? n : (1 - Math.pow(1 + r, -n)) / r;
            double f = pv - pmt * annuity - fv * Math.pow(1 + r, -n);
            if (f > 0) {
                hi = r;
            } else {
                lo = r;
            }
        }
        return (lo + hi) / 2;
    }

    private static double annualize(double monthly) {
        return Math.pow(1 + monthly, 12) - 1;
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal rate(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }
}
