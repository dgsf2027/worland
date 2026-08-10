package top.aole.rent.modules.analytics.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.rent.modules.analytics.dto.CashflowDtos;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.contract.domain.RentSchedule;
import top.aole.rent.modules.contract.mapper.RentScheduleMapper;
import top.aole.rent.modules.distribution.domain.Investor;
import top.aole.rent.modules.distribution.mapper.InvestorMapper;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.mapper.PayableMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 现金流驾驶舱服务(M3-05)。应收(rent_schedule 未收)按到期分层 + 应付(payable 待付)按到期分层
 * + 未来 N 月净现金流预测曲线 + 三层杠杆资金占用(自有/供应商账期/融资)。
 *
 * <p>§4.24 单一真相源:应收未收态取自 rent_bill(收款唯一真相源)—— 计划已生成收租单且 status='已核销' 视为已收,
 * 否则计入应收未收(含未到期/未生成单/待收/逾期)。应付负债取 payable status='待付'。
 */
@Service
@RequiredArgsConstructor
public class CashflowService {

    private final RentScheduleMapper rentScheduleMapper;
    private final RentBillMapper rentBillMapper;
    private final PayableMapper payableMapper;
    private final InvestorMapper investorMapper;

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");

    public CashflowDtos.Cashflow cashflow(Integer months) {
        int n = months != null && months > 0 ? months : 12;
        LocalDate asOf = LocalDate.now();

        List<Uncollected> receivables = uncollectedReceivables();
        List<Payable> payables = payableMapper.selectList(new LambdaQueryWrapper<Payable>()
                .eq(Payable::getStatus, "待付"));

        CashflowDtos.Cashflow cf = new CashflowDtos.Cashflow();
        cf.setAsOf(asOf);
        cf.setReceivable(receivableBucket(receivables, asOf));
        cf.setPayable(payableBucket(payables, asOf));
        cf.setForecast(forecast(receivables, payables, asOf, n));
        cf.setLeverage(leverage(payables));
        cf.setNetPosition(cf.getReceivable().getTotal().subtract(cf.getPayable().getTotal())
                .setScale(2, RoundingMode.HALF_UP));
        return cf;
    }

    // ---------- 应收未收(供本服务与兑付缺口共用) ----------

    /** 一笔未收应收(计划口径·金额+到期日)。 */
    public static class Uncollected {
        public final BigDecimal amount;
        public final LocalDate dueDate;
        Uncollected(BigDecimal amount, LocalDate dueDate) {
            this.amount = amount; this.dueDate = dueDate;
        }
    }

    /** 未收应收 = rent_schedule 未被 rent_bill 已核销的行(§4.24 收款态取 rent_bill)。 */
    public List<Uncollected> uncollectedReceivables() {
        List<RentSchedule> schedules = rentScheduleMapper.selectList(new LambdaQueryWrapper<RentSchedule>()
                .eq(RentSchedule::getIsDeleted, 0));
        // 批量取已生成收租单的核销态
        List<Long> billIds = new ArrayList<>();
        for (RentSchedule s : schedules) {
            if (s.getRentBillId() != null) {
                billIds.add(s.getRentBillId());
            }
        }
        Map<Long, String> billStatus = new HashMap<>();
        if (!billIds.isEmpty()) {
            for (RentBill b : rentBillMapper.selectBatchIds(billIds)) {
                billStatus.put(b.getId(), b.getStatus());
            }
        }
        List<Uncollected> out = new ArrayList<>();
        for (RentSchedule s : schedules) {
            boolean collected = s.getRentBillId() != null
                    && "已核销".equals(billStatus.get(s.getRentBillId()));
            if (!collected && s.getAmount() != null && s.getAmount().signum() > 0) {
                out.add(new Uncollected(s.getAmount(), s.getDueDate()));
            }
        }
        return out;
    }

    // ---------- 分层 ----------

    private CashflowDtos.Bucket receivableBucket(List<Uncollected> rs, LocalDate asOf) {
        LocalDate oneYear = asOf.plusYears(1);
        BigDecimal within = BigDecimal.ZERO, beyond = BigDecimal.ZERO;
        for (Uncollected u : rs) {
            if (u.dueDate != null && u.dueDate.isAfter(oneYear)) {
                beyond = beyond.add(u.amount);
            } else {
                within = within.add(u.amount);
            }
        }
        return bucket(within, beyond, rs.size());
    }

    private CashflowDtos.Bucket payableBucket(List<Payable> ps, LocalDate asOf) {
        LocalDate oneYear = asOf.plusYears(1);
        BigDecimal within = BigDecimal.ZERO, beyond = BigDecimal.ZERO;
        for (Payable p : ps) {
            if (p.getDueDate() != null && p.getDueDate().isAfter(oneYear)) {
                beyond = beyond.add(p.getAmount());
            } else {
                within = within.add(p.getAmount());
            }
        }
        return bucket(within, beyond, ps.size());
    }

    private CashflowDtos.Bucket bucket(BigDecimal within, BigDecimal beyond, int count) {
        CashflowDtos.Bucket b = new CashflowDtos.Bucket();
        b.setWithin1Year(within.setScale(2, RoundingMode.HALF_UP));
        b.setBeyond1Year(beyond.setScale(2, RoundingMode.HALF_UP));
        b.setTotal(within.add(beyond).setScale(2, RoundingMode.HALF_UP));
        b.setCount(count);
        return b;
    }

    // ---------- 净现金流预测曲线 ----------

    private List<CashflowDtos.MonthFlow> forecast(List<Uncollected> rs, List<Payable> ps,
                                                  LocalDate asOf, int n) {
        Map<String, BigDecimal> inflow = new HashMap<>();
        Map<String, BigDecimal> outflow = new HashMap<>();
        for (Uncollected u : rs) {
            if (u.dueDate != null) {
                inflow.merge(u.dueDate.format(YM), u.amount, BigDecimal::add);
            }
        }
        for (Payable p : ps) {
            if (p.getDueDate() != null) {
                outflow.merge(p.getDueDate().format(YM), p.getAmount(), BigDecimal::add);
            }
        }
        List<CashflowDtos.MonthFlow> out = new ArrayList<>();
        YearMonth cur = YearMonth.from(asOf);
        BigDecimal cumulative = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            String key = cur.format(YM);
            BigDecimal in = inflow.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal out2 = outflow.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal net = in.subtract(out2);
            cumulative = cumulative.add(net);
            CashflowDtos.MonthFlow mf = new CashflowDtos.MonthFlow();
            mf.setPeriod(key);
            mf.setInflow(in.setScale(2, RoundingMode.HALF_UP));
            mf.setOutflow(out2.setScale(2, RoundingMode.HALF_UP));
            mf.setNet(net.setScale(2, RoundingMode.HALF_UP));
            mf.setCumulative(cumulative.setScale(2, RoundingMode.HALF_UP));
            out.add(mf);
            cur = cur.plusMonths(1);
        }
        return out;
    }

    // ---------- 三层杠杆资金占用 ----------

    private CashflowDtos.Leverage leverage(List<Payable> ps) {
        BigDecimal own = BigDecimal.ZERO;
        for (Investor iv : investorMapper.selectList(new LambdaQueryWrapper<Investor>().eq(Investor::getActive, 1))) {
            own = own.add(iv.getAmount());
        }
        BigDecimal supplier = BigDecimal.ZERO;
        for (Payable p : ps) {
            supplier = supplier.add(p.getAmount());
        }
        supplier = supplier.max(BigDecimal.ZERO);
        BigDecimal financing = BigDecimal.ZERO;   // 层级③融资额度:融资模块未启用(M4/M5)
        CashflowDtos.Leverage l = new CashflowDtos.Leverage();
        l.setOwnCapital(own.setScale(2, RoundingMode.HALF_UP));
        l.setSupplierCredit(supplier.setScale(2, RoundingMode.HALF_UP));
        l.setFinancing(financing.setScale(2, RoundingMode.HALF_UP));
        l.setTotal(own.add(supplier).add(financing).setScale(2, RoundingMode.HALF_UP));
        l.setNote("层级①自有=实缴出资;层级②供应商账期=待付应付(无息);层级③融资未启用(需融资模块)");
        return l;
    }
}
