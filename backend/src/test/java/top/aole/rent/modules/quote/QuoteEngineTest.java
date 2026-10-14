package top.aole.rent.modules.quote;

import org.junit.jupiter.api.Test;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.quote.dto.QuoteResponse;
import top.aole.rent.modules.quote.service.QuoteEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报价引擎(曜石测算表简版口径)。期望值由独立的参考实现算出,参考实现已与测算表缓存的
 * 12 行 × 多列(189 个值)逐一核对一致;这里用的是虚构输入,不含真实价格。
 * 金额断言容差 0.01 元,比率断言容差 1e-6(响应分别保留 2/6 位小数)。
 */
class QuoteEngineTest {

    private static final double MONEY = 0.01;
    private static final double RATE = 1e-6;

    /** 播种墙 · 虚构输入 */
    private static QuoteEngine.Params wall(double collect, double target, double purchase) {
        QuoteEngine.Params p = new QuoteEngine.Params();
        p.setCategory("播种墙");
        p.setCustomerType("云山快仓");
        p.setMarket(300000);
        p.setPurchase(purchase);
        p.setTerm(36);
        p.setPrepayRatio(0.10);
        p.setTransferRate(0.10);
        p.setCollectRate(collect);
        p.setSurtaxRate(0.06);
        p.setIncomeTaxRate(0.05);
        p.setCapitalCostRate(0.06);
        p.setMgmtFeeRate(0.05);
        p.setMaintPerYear(3000);
        p.setWarrantyMonths(12);
        p.setTargetIrr(target);
        p.setCustomerGeneral(true);
        p.setCustomerInputTax(0.13);
        p.setHeadcount(2.5);
        p.setLaborCost(4150);
        p.setPaybackCeiling(18);
        return p;
    }

    private static QuoteEngine.Params shelf() {
        QuoteEngine.Params p = wall(0.025, 0.30, 270000);
        p.setCategory("货架");
        p.setMarket(320000);
        p.setTerm(60);
        p.setPrepayRatio(0);
        p.setTransferRate(0.30);
        p.setMaintPerYear(0);
        p.setWarrantyMonths(60);
        p.setHeadcount(null);
        p.setSlots(2000);
        return p;
    }

    @Test
    void 播种墙手填月租_租赁公司视角() {
        QuoteEngine.Params p = wall(0.03, 0.25, 240000);
        p.setRent(8000.0);
        QuoteResponse r = QuoteEngine.compute(p);

        assertEquals(8000, r.getMonthlyRent().doubleValue(), MONEY);
        assertFalse(r.getRentSolved());
        assertEquals(30000, r.getPrepayment().doubleValue(), MONEY);
        assertEquals(30000, r.getTransferPrice().doubleValue(), MONEY);
        QuoteResponse.Lessor l = r.getLessor();
        assertEquals(348000, l.getTotalCollected().doubleValue(), MONEY);
        assertEquals(10135.92, l.getVat().doubleValue(), MONEY);
        assertEquals(608.16, l.getSurtax().doubleValue(), MONEY);
        assertEquals(4862.80, l.getIncomeTax().doubleValue(), MONEY);
        assertEquals(92393.13, l.getNetProfit().doubleValue(), MONEY);
        assertEquals(0.303578, l.getPreTaxIrr().doubleValue(), RATE);
        assertEquals(0.258062, l.getAfterTaxIrr().doubleValue(), RATE);
        assertEquals(0.030874, l.getTaxFactor().doubleValue(), RATE);
        assertEquals(28, l.getPaybackMonths());
        assertTrue(l.getMeetsTarget());
    }

    @Test
    void 播种墙手填月租_实际成本视角() {
        QuoteEngine.Params p = wall(0.03, 0.25, 240000);
        p.setRent(8000.0);
        QuoteResponse.Cost c = QuoteEngine.compute(p).getCost();

        assertEquals(337864.08, c.getRevenueExVat().doubleValue(), MONEY);
        assertEquals(5470.95, c.getTaxCost().doubleValue(), MONEY);
        assertEquals(6000, c.getMaintenance().doubleValue(), MONEY);
        assertEquals(15131.78, c.getCapitalCost().doubleValue(), MONEY);
        assertEquals(3563.07, c.getMgmtFee().doubleValue(), MONEY);
        assertEquals(270165.80, c.getTotalCost().doubleValue(), MONEY);
        assertEquals(67698.28, c.getActualProfit().doubleValue(), MONEY);
        assertEquals(0.200371, c.getProfitMargin().doubleValue(), RATE);
        assertEquals(7504.61, c.getAvgMonthlyCost().doubleValue(), MONEY);
        assertEquals(5818.08, c.getBreakEvenRent().doubleValue(), MONEY);
        assertEquals(0.375025, c.getSafetyMargin().doubleValue(), RATE);
        assertEquals(210926.21, c.getPeakCapital().doubleValue(), MONEY);
    }

    @Test
    void 播种墙手填月租_客户视角() {
        QuoteEngine.Params p = wall(0.03, 0.25, 240000);
        p.setRent(8000.0);
        QuoteResponse.Customer c = QuoteEngine.compute(p).getCustomer();

        assertEquals(348000, c.getTotalPay().doubleValue(), MONEY);
        assertEquals(48000, c.getExtraPay().doubleValue(), MONEY);
        assertEquals(0.16, c.getExtraRatio().doubleValue(), RATE);
        assertEquals(34513.27, c.getBuyInputTax().doubleValue(), MONEY);
        assertEquals(10135.92, c.getLeaseInputTax().doubleValue(), MONEY);
        assertEquals(72377.35, c.getAfterTaxExtra().doubleValue(), MONEY);
        assertEquals(0.272621, c.getAfterTaxExtraRatio().doubleValue(), RATE);
        assertEquals(0.105181, c.getImpliedFinanceCost().doubleValue(), RATE);
        // 替代 2.5 人 × 4150 = 10375;月净收益 2375;自购回本 300000/10375 ≈ 28.9 月 > 18
        assertEquals(10375, c.getMonthlySaving().doubleValue(), MONEY);
        assertEquals(2375, c.getMonthlyNetBenefit().doubleValue(), MONEY);
        assertEquals(28.9, c.getBuyPaybackMonths().doubleValue(), 0.05);
        assertFalse(c.getPaybackPass());
        assertTrue(c.getBenefitPass());
        assertFalse(c.getValuePricingPass());
        assertEquals("租用当月即净省钱", c.getVerdict());
        assertNull(c.getUnitRent());
    }

    @Test
    void 目标IRR反解月租并向上取整到整元() {
        QuoteEngine.Params p = wall(0.0222, 0.30, 235000);
        QuoteResponse r = QuoteEngine.compute(p);

        assertTrue(r.getRentSolved());
        assertEquals(8145, r.getMonthlyRent().doubleValue(), MONEY);
        assertEquals(0.300082, r.getLessor().getAfterTaxIrr().doubleValue(), RATE);
        assertTrue(r.getLessor().getMeetsTarget());
        // 少 1 元就低于目标:证明取整是"向上"且取到最小的整数月租
        QuoteEngine.Params lower = wall(0.0222, 0.30, 235000);
        lower.setRent(8144.0);
        assertTrue(QuoteEngine.compute(lower).getLessor().getAfterTaxIrr().doubleValue() < 0.30);
    }

    @Test
    void 月租偏低时不达标_容差半个百分点() {
        QuoteEngine.Params low = wall(0.03, 0.25, 240000);
        low.setRent(6500.0);
        assertFalse(QuoteEngine.compute(low).getLessor().getMeetsTarget());

        // 目标设成 IRR 之上 0.4 个百分点:仍算达标;之上 0.6 个百分点:不达标
        QuoteEngine.Params p = wall(0.03, 0.2580623 + 0.004, 240000);
        p.setRent(8000.0);
        assertTrue(QuoteEngine.compute(p).getLessor().getMeetsTarget());
        p.setTargetIrr(0.2580623 + 0.006);
        assertFalse(QuoteEngine.compute(p).getLessor().getMeetsTarget());
    }

    @Test
    void 货架_无预付_单位月租与自购折月() {
        QuoteEngine.Params p = shelf();
        p.setRent(8500.0);
        QuoteResponse r = QuoteEngine.compute(p);

        assertEquals(0, r.getPrepayment().doubleValue(), MONEY);
        assertEquals(96000, r.getTransferPrice().doubleValue(), MONEY);
        assertEquals(606000, r.getLessor().getTotalCollected().doubleValue(), MONEY);
        assertEquals(0.342464, r.getLessor().getAfterTaxIrr().doubleValue(), RATE);
        assertEquals(0.378090, r.getLessor().getPreTaxIrr().doubleValue(), RATE);
        assertEquals(33, r.getLessor().getPaybackMonths());
        assertEquals(23422.69, r.getCost().getCapitalCost().doubleValue(), MONEY);
        assertEquals(0, r.getCost().getMaintenance().doubleValue(), MONEY);
        assertEquals(266848.69, r.getCost().getActualProfit().doubleValue(), MONEY);
        assertEquals(3420.17, r.getCost().getBreakEvenRent().doubleValue(), MONEY);
        assertEquals(270000, r.getCost().getPeakCapital().doubleValue(), MONEY);
        assertEquals(0.276299, r.getCustomer().getImpliedFinanceCost().doubleValue(), RATE);
        assertEquals(4.25, r.getCustomer().getUnitRent().doubleValue(), MONEY);
        assertEquals(5333.33, r.getCustomer().getBuyPricePerMonth().doubleValue(), MONEY);
        assertEquals("月租高于自购折月,价值在免一次性投入", r.getCustomer().getVerdict());
        // 货架不替代人工:价值定价校验不适用
        assertNull(r.getCustomer().getPaybackPass());
        assertNull(r.getCustomer().getValuePricingPass());
    }

    @Test
    void 货架月租低于自购折月() {
        QuoteEngine.Params p = shelf();
        p.setRent(5000.0);
        assertEquals("月租低于自购折月", QuoteEngine.compute(p).getCustomer().getVerdict());
    }

    @Test
    void 货架没填货位数_单位月租为空() {
        QuoteEngine.Params p = shelf();
        p.setSlots(null);
        p.setRent(8500.0);
        assertNull(QuoteEngine.compute(p).getCustomer().getUnitRent());
    }

    @Test
    void 客户是小规模纳税人_不能抵进项() {
        QuoteEngine.Params p = wall(0.03, 0.25, 240000);
        p.setRent(8000.0);
        p.setCustomerGeneral(false);
        QuoteResponse.Customer c = QuoteEngine.compute(p).getCustomer();
        assertEquals(0, c.getBuyInputTax().doubleValue(), MONEY);
        assertEquals(0, c.getLeaseInputTax().doubleValue(), MONEY);
        assertEquals(48000, c.getAfterTaxExtra().doubleValue(), MONEY);
        assertEquals(0.16, c.getAfterTaxExtraRatio().doubleValue(), RATE);
    }

    @Test
    void 播种墙没填替代人数_不评估客户价值() {
        QuoteEngine.Params p = wall(0.03, 0.25, 240000);
        p.setRent(8000.0);
        p.setHeadcount(null);
        QuoteResponse.Customer c = QuoteEngine.compute(p).getCustomer();
        assertNull(c.getMonthlySaving());
        assertNull(c.getValuePricingPass());
        assertNotNull(c.getVerdict());
        assertTrue(c.getVerdict().contains("替代人数"));
    }

    @Test
    void 替代人数多时两校验全过() {
        QuoteEngine.Params p = wall(0.03, 0.25, 240000);
        p.setRent(8000.0);
        p.setHeadcount(5.0);   // 5×4150=20750;回本 300000/20750≈14.5 月
        QuoteResponse.Customer c = QuoteEngine.compute(p).getCustomer();
        assertTrue(c.getPaybackPass());
        assertTrue(c.getBenefitPass());
        assertTrue(c.getValuePricingPass());
    }

    @Test
    void 保外维保只算质保期之外() {
        QuoteEngine.Params p = wall(0.03, 0.25, 240000);
        p.setRent(8000.0);
        p.setWarrantyMonths(36);   // 质保覆盖整个租期
        assertEquals(0, QuoteEngine.compute(p).getCost().getMaintenance().doubleValue(), MONEY);
        p.setWarrantyMonths(0);    // 36 个月 × 3000 ÷ 12
        assertEquals(9000, QuoteEngine.compute(p).getCost().getMaintenance().doubleValue(), MONEY);
    }

    @Test
    void 非法输入被拒() {
        QuoteEngine.Params p = wall(0.03, 0.25, 240000);
        p.setTerm(0);
        assertThrows(BizException.class, () -> QuoteEngine.compute(p));
        QuoteEngine.Params q = wall(0.03, 0.25, 240000);
        q.setMarket(0);
        assertThrows(BizException.class, () -> QuoteEngine.compute(q));
        QuoteEngine.Params r = wall(0.03, 0.25, 0);
        assertThrows(BizException.class, () -> QuoteEngine.compute(r));
    }
}
