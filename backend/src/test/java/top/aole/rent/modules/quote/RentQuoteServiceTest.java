package top.aole.rent.modules.quote;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.quote.dto.QuoteRequest;
import top.aole.rent.modules.quote.dto.QuoteResponse;
import top.aole.rent.modules.quote.service.RentQuoteService;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 报价服务:从 rule_config 取默认值、采购成本两种给法、入参校验。规则值为通用口径,不含真实价格。 */
class RentQuoteServiceTest {

    private final Map<String, String> rules = new HashMap<>();
    private RentQuoteService service;

    private void rule(String key, String scope, String value) {
        rules.put(key + "|" + scope, value);
    }

    @BeforeEach
    void setUp() {
        rules.clear();
        rule("quote_surtax_rate", "", "0.06");
        rule("quote_income_tax", "", "0.05");
        rule("quote_capital_cost", "", "0.06");
        rule("quote_mgmt_fee", "", "0.05");
        rule("quote_labor_cost", "", "4150");
        rule("quote_input_tax", "", "0.13");
        rule("quote_collect_rate", "云山快仓", "0.03");
        rule("quote_collect_rate", "其他:播种墙", "0.0222");
        rule("quote_collect_rate", "其他:货架", "0.025");
        rule("quote_prepay_ratio", "播种墙", "0.10");
        rule("quote_prepay_ratio", "货架", "0");
        rule("quote_target_irr", "云山快仓", "0.25");
        rule("quote_target_irr", "其他·标准", "0.30");
        rule("quote_target_irr", "其他·高", "0.35");
        rule("term_months", "播种墙", "36");
        rule("term_months", "货架", "60");
        rule("transfer_rate", "播种墙", "0.10");
        rule("transfer_rate", "货架", "0.30");
        rule("payback_ceiling_months", "", "18");

        RuleConfigService rcs = mock(RuleConfigService.class);
        when(rcs.getValue(anyString(), anyString(), any(LocalDate.class))).thenAnswer(inv -> {
            String k = inv.getArgument(0) + "|" + inv.getArgument(1);
            String v = rules.get(k);
            if (v == null) {
                throw new BizException(500, "规则配置缺失: " + k);
            }
            return new BigDecimal(v);
        });
        service = new RentQuoteService(rcs);
    }

    private QuoteRequest wallReq(String customerType) {
        QuoteRequest r = new QuoteRequest();
        r.setCategory("播种墙");
        r.setCustomerType(customerType);
        r.setMarketPrice(new BigDecimal("300000"));
        r.setPurchasePrice(new BigDecimal("240000"));
        return r;
    }

    @Test
    void 其他标准_取品类征收率与默认目标并反解月租() {
        QuoteResponse r = service.calc(wallReq("其他·标准"));
        assertEquals(0.0222, r.getLessor().getCollectRate().doubleValue(), 1e-9);
        assertEquals(0.30, r.getTargetIrr().doubleValue(), 1e-9);
        assertEquals(36, r.getTermMonths());
        assertEquals(30000, r.getPrepayment().doubleValue(), 0.01);
        assertEquals(30000, r.getTransferPrice().doubleValue(), 0.01);
        assertTrue(r.getRentSolved());
        assertTrue(r.getLessor().getMeetsTarget());
        assertEquals("其他·标准", r.getCustomerType());
    }

    @Test
    void 云山快仓_取三个点的征收率与25目标() {
        QuoteResponse r = service.calc(wallReq("云山快仓"));
        assertEquals(0.03, r.getLessor().getCollectRate().doubleValue(), 1e-9);
        assertEquals(0.25, r.getTargetIrr().doubleValue(), 1e-9);
    }

    @Test
    void 其他高_默认目标35() {
        QuoteResponse r = service.calc(wallReq("其他·高"));
        assertEquals(0.35, r.getTargetIrr().doubleValue(), 1e-9);
        assertEquals(0.0222, r.getLessor().getCollectRate().doubleValue(), 1e-9);
    }

    @Test
    void 货架_默认五年无预付三成转让() {
        QuoteRequest r = new QuoteRequest();
        r.setCategory("货架");
        r.setCustomerType("其他·标准");
        r.setMarketPrice(new BigDecimal("320000"));
        r.setPurchasePrice(new BigDecimal("270000"));
        QuoteResponse res = service.calc(r);
        assertEquals(60, res.getTermMonths());
        assertEquals(0, res.getPrepayment().doubleValue(), 0.01);
        assertEquals(96000, res.getTransferPrice().doubleValue(), 0.01);
        assertEquals(0.025, res.getLessor().getCollectRate().doubleValue(), 1e-9);
    }

    @Test
    void 采购成本由折扣基数折扣率运安费算出() {
        QuoteRequest r = wallReq("其他·标准");
        r.setPurchasePrice(null);
        r.setDiscountBase(new BigDecimal("290000"));
        r.setDiscountRate(new BigDecimal("0.2"));
        r.setFreightInstall(new BigDecimal("7000"));
        // 290000 × (1−0.2) + 7000 = 239000
        assertEquals(239000, service.calc(r).getPurchasePrice().doubleValue(), 0.01);
    }

    @Test
    void 运安费不填按0算() {
        QuoteRequest r = wallReq("其他·标准");
        r.setPurchasePrice(null);
        r.setDiscountBase(new BigDecimal("290000"));
        r.setDiscountRate(new BigDecimal("0.2"));
        assertEquals(232000, service.calc(r).getPurchasePrice().doubleValue(), 0.01);
    }

    @Test
    void 直接给采购成本时优先于折扣算法() {
        QuoteRequest r = wallReq("其他·标准");
        r.setDiscountBase(new BigDecimal("290000"));
        r.setDiscountRate(new BigDecimal("0.2"));
        assertEquals(240000, service.calc(r).getPurchasePrice().doubleValue(), 0.01);
    }

    @Test
    void 采购成本和折扣都没给_报错() {
        QuoteRequest r = wallReq("其他·标准");
        r.setPurchasePrice(null);
        BizException e = assertThrows(BizException.class, () -> service.calc(r));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("采购成本"), e.getMessage());
    }

    @Test
    void 折扣率不能大于等于1() {
        QuoteRequest r = wallReq("其他·标准");
        r.setPurchasePrice(null);
        r.setDiscountBase(new BigDecimal("290000"));
        r.setDiscountRate(new BigDecimal("1"));
        assertEquals(400, assertThrows(BizException.class, () -> service.calc(r)).getCode());
    }

    @Test
    void 请求里的值优先于规则默认() {
        QuoteRequest r = wallReq("其他·标准");
        r.setTermMonths(24);
        r.setPrepayRatio(new BigDecimal("0.2"));
        r.setTransferRate(new BigDecimal("0.05"));
        r.setTargetIrr(new BigDecimal("0.33"));
        r.setMonthlyRent(new BigDecimal("9000"));
        QuoteResponse res = service.calc(r);
        assertEquals(24, res.getTermMonths());
        assertEquals(60000, res.getPrepayment().doubleValue(), 0.01);
        assertEquals(15000, res.getTransferPrice().doubleValue(), 0.01);
        assertEquals(0.33, res.getTargetIrr().doubleValue(), 1e-9);
        assertEquals(9000, res.getMonthlyRent().doubleValue(), 0.01);
        assertEquals(false, res.getRentSolved());
    }

    @Test
    void 人工月成本默认取规则_请求可覆盖() {
        QuoteRequest r = wallReq("其他·标准");
        r.setReplaceHeadcount(new BigDecimal("2"));
        // 2 × 4150
        assertEquals(8300, service.calc(r).getCustomer().getMonthlySaving().doubleValue(), 0.01);
        r.setLaborCostMonthly(new BigDecimal("5000"));
        assertEquals(10000, service.calc(r).getCustomer().getMonthlySaving().doubleValue(), 0.01);
    }

    @Test
    void 一般纳税人默认是_可显式关闭() {
        QuoteRequest r = wallReq("其他·标准");
        assertTrue(service.calc(r).getCustomer().getBuyInputTax().doubleValue() > 0);
        r.setCustomerGeneralTaxpayer(false);
        assertEquals(0, service.calc(r).getCustomer().getBuyInputTax().doubleValue(), 0.01);
    }

    @Test
    void 未知客户类型_报错() {
        BizException e = assertThrows(BizException.class, () -> service.calc(wallReq("随便")));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("客户类型"), e.getMessage());
    }

    @Test
    void 未知品类_报错() {
        QuoteRequest r = wallReq("其他·标准");
        r.setCategory("阁楼");
        assertEquals(400, assertThrows(BizException.class, () -> service.calc(r)).getCode());
    }

    @Test
    void 规则缺失时显式暴露() {
        rules.remove("quote_surtax_rate|");
        assertEquals(500, assertThrows(BizException.class, () -> service.calc(wallReq("其他·标准"))).getCode());
    }
}
