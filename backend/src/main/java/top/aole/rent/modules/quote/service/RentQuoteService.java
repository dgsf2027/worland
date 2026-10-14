package top.aole.rent.modules.quote.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.quote.dto.QuoteRequest;
import top.aole.rent.modules.quote.dto.QuoteResponse;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * 报价测算服务:按「曜石 货架/播种墙测算表(简版)」口径(10-14 起,替换原折旧模型 + 三层回报)。
 * 本类只做:校验入参 → 从 rule_config 取通用口径默认值 → 合并请求里的覆盖值 → 交给 {@link QuoteEngine} 计算。
 *
 * <p>rule_config 键(V123 灌入,均为通用口径):quote_collect_rate(云山快仓 / 其他:品类)、quote_surtax_rate、
 * quote_income_tax、quote_capital_cost、quote_mgmt_fee、quote_labor_cost、quote_input_tax、quote_prepay_ratio(品类)、
 * quote_target_irr(客户类型);租期与转让率沿用既有 term_months / transfer_rate,回本期上限沿用 payback_ceiling_months。
 * 基准价、框架折扣、采购价、货位数等商业数据不入库,每次由请求给出。
 */
@Service
@RequiredArgsConstructor
public class RentQuoteService {

    private static final List<String> CATEGORIES = Arrays.asList("播种墙", "货架");
    private static final List<String> CUSTOMER_TYPES = Arrays.asList("云山快仓", "其他·标准", "其他·高");
    private static final String YUNSHAN = "云山快仓";

    private final RuleConfigService rules;

    public QuoteResponse calc(QuoteRequest req) {
        LocalDate today = LocalDate.now();
        String category = req.getCategory().trim();
        String customerType = req.getCustomerType().trim();
        if (!CATEGORIES.contains(category)) {
            throw new BizException(400, "品类须为 播种墙 / 货架");
        }
        if (!CUSTOMER_TYPES.contains(customerType)) {
            throw new BizException(400, "客户类型须为 云山快仓 / 其他·标准 / 其他·高");
        }

        QuoteEngine.Params p = new QuoteEngine.Params();
        p.setCategory(category);
        p.setCustomerType(customerType);
        p.setMarket(req.getMarketPrice().doubleValue());
        p.setPurchase(purchaseCost(req));

        p.setTerm(req.getTermMonths() != null ? req.getTermMonths()
                : rules.getValue("term_months", category, today).intValue());
        p.setPrepayRatio(pick(req.getPrepayRatio(), () -> rules.getValue("quote_prepay_ratio", category, today)));
        p.setTransferRate(pick(req.getTransferRate(), () -> rules.getValue("transfer_rate", category, today)));
        p.setTargetIrr(pick(req.getTargetIrr(), () -> rules.getValue("quote_target_irr", customerType, today)));

        String collectScope = YUNSHAN.equals(customerType) ? YUNSHAN : "其他:" + category;
        p.setCollectRate(rules.getValue("quote_collect_rate", collectScope, today).doubleValue());
        p.setSurtaxRate(rules.getValue("quote_surtax_rate", "", today).doubleValue());
        p.setIncomeTaxRate(rules.getValue("quote_income_tax", "", today).doubleValue());
        p.setCapitalCostRate(pick(req.getCapitalCostRate(), () -> rules.getValue("quote_capital_cost", "", today)));
        p.setMgmtFeeRate(pick(req.getMgmtFeeRate(), () -> rules.getValue("quote_mgmt_fee", "", today)));

        p.setMaintPerYear(req.getMaintenancePerYear() == null ? 0 : req.getMaintenancePerYear().doubleValue());
        p.setWarrantyMonths(req.getWarrantyMonths() == null ? 0 : req.getWarrantyMonths());
        p.setRent(req.getMonthlyRent() == null ? null : req.getMonthlyRent().doubleValue());

        p.setCustomerGeneral(req.getCustomerGeneralTaxpayer() == null || req.getCustomerGeneralTaxpayer());
        p.setCustomerInputTax(rules.getValue("quote_input_tax", "", today).doubleValue());
        p.setHeadcount(req.getReplaceHeadcount() == null ? null : req.getReplaceHeadcount().doubleValue());
        p.setLaborCost(pick(req.getLaborCostMonthly(), () -> rules.getValue("quote_labor_cost", "", today)));
        p.setSlots(req.getSlots());
        p.setPaybackCeiling(rules.getValue("payback_ceiling_months", "", today).doubleValue());

        return QuoteEngine.compute(p);
    }

    /** 采购成本:直接给;或 折扣基数 ×(1−折扣率)+ 运安费(运安费不填按 0)。 */
    private double purchaseCost(QuoteRequest req) {
        if (req.getPurchasePrice() != null) {
            return req.getPurchasePrice().doubleValue();
        }
        if (req.getDiscountBase() != null && req.getDiscountRate() != null) {
            if (req.getDiscountRate().compareTo(BigDecimal.ONE) >= 0) {
                throw new BizException(400, "折扣率须小于 1");
            }
            BigDecimal freight = req.getFreightInstall() == null ? BigDecimal.ZERO : req.getFreightInstall();
            return req.getDiscountBase().multiply(BigDecimal.ONE.subtract(req.getDiscountRate()))
                    .add(freight).doubleValue();
        }
        throw new BizException(400, "请填写采购成本,或同时填写折扣基数与折扣率");
    }

    private static double pick(BigDecimal override, Supplier<BigDecimal> ruleDefault) {
        return (override != null ? override : ruleDefault.get()).doubleValue();
    }
}
