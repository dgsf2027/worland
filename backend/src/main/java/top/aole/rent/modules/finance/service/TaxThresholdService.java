package top.aole.rent.modules.finance.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.rent.modules.finance.domain.LedgerBook;
import top.aole.rent.modules.finance.dto.VoucherDtos;
import top.aole.rent.modules.finance.mapper.LedgerBookMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 500万营收红线服务(M3-08)。营收监控<b>取税务账 ledger_book</b>(ops≠tax·必须税务口径:折旧只落 ops,
 * 两账套天然分岔),逼近 500 万预警(小规模纳税人→一般纳税人/税负跳档红线)。
 *
 * <p>本年 tax 账套已确认收入 = SUM(ledger_book.amount) WHERE book='tax' AND entry_type='revenue'
 * AND YEAR(biz_date)=当年(净红冲:红冲写负额行,自动回退)。阈值/预警占比走 rule_config,禁硬编码。
 */
@Service
@RequiredArgsConstructor
public class TaxThresholdService {

    private final LedgerBookMapper ledgerBookMapper;
    private final RuleConfigService rules;

    public VoucherDtos.TaxThreshold threshold(Integer year) {
        int y = year != null ? year : LocalDate.now().getYear();
        LocalDate from = LocalDate.of(y, 1, 1);
        LocalDate to = LocalDate.of(y, 12, 31);

        BigDecimal threshold = safe("tax_revenue_threshold", new BigDecimal("5000000"));
        BigDecimal warnRatio = safe("tax_threshold_warn_ratio", new BigDecimal("0.80"));

        List<LedgerBook> taxRows = ledgerBookMapper.selectList(new LambdaQueryWrapper<LedgerBook>()
                .eq(LedgerBook::getBook, VoucherService.BOOK_TAX)
                .eq(LedgerBook::getEntryType, "revenue")
                .ge(LedgerBook::getBizDate, from).le(LedgerBook::getBizDate, to));
        List<LedgerBook> opsRows = ledgerBookMapper.selectList(new LambdaQueryWrapper<LedgerBook>()
                .eq(LedgerBook::getBook, VoucherService.BOOK_OPS)
                .eq(LedgerBook::getEntryType, "revenue")
                .ge(LedgerBook::getBizDate, from).le(LedgerBook::getBizDate, to));

        BigDecimal taxRevenue = sum(taxRows);
        BigDecimal opsRevenue = sum(opsRows);

        // 按月归集(tax + ops 对照)
        Map<String, BigDecimal> taxByMonth = new TreeMap<>();
        Map<String, BigDecimal> opsByMonth = new TreeMap<>();
        for (LedgerBook lb : taxRows) {
            taxByMonth.merge(lb.getPeriod(), lb.getAmount(), BigDecimal::add);
        }
        for (LedgerBook lb : opsRows) {
            opsByMonth.merge(lb.getPeriod(), lb.getAmount(), BigDecimal::add);
        }

        VoucherDtos.TaxThreshold t = new VoucherDtos.TaxThreshold();
        t.setYear(y);
        t.setBook(VoucherService.BOOK_TAX);
        t.setThreshold(threshold);
        t.setWarnRatio(warnRatio);
        t.setCurrentRevenue(taxRevenue);
        t.setRemaining(threshold.subtract(taxRevenue).setScale(2, RoundingMode.HALF_UP));
        BigDecimal used = threshold.signum() > 0
                ? taxRevenue.divide(threshold, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        t.setUsedRatio(used);
        t.setOpsRevenue(opsRevenue);

        String level;
        if (taxRevenue.compareTo(threshold) >= 0) {
            level = "超限";
        } else if (used.compareTo(warnRatio) >= 0) {
            level = "预警";
        } else {
            level = "正常";
        }
        t.setLevel(level);

        List<VoucherDtos.MonthRevenue> byMonth = new ArrayList<>();
        java.util.Set<String> months = new java.util.TreeSet<>();
        months.addAll(taxByMonth.keySet());
        months.addAll(opsByMonth.keySet());
        for (String m : months) {
            VoucherDtos.MonthRevenue mr = new VoucherDtos.MonthRevenue();
            mr.setPeriod(m);
            mr.setTaxRevenue(taxByMonth.getOrDefault(m, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
            mr.setOpsRevenue(opsByMonth.getOrDefault(m, BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
            byMonth.add(mr);
        }
        t.setByMonth(byMonth);
        return t;
    }

    private BigDecimal sum(List<LedgerBook> rows) {
        BigDecimal s = BigDecimal.ZERO;
        for (LedgerBook lb : rows) {
            s = s.add(lb.getAmount());
        }
        return s.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(String key, BigDecimal dft) {
        try {
            return rules.getValue(key, "", LocalDate.now());
        } catch (Exception e) {
            return dft;
        }
    }
}
