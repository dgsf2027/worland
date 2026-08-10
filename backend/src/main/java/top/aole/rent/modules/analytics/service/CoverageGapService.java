package top.aole.rent.modules.analytics.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.rent.modules.analytics.dto.CashflowDtos;
import top.aole.rent.modules.distribution.domain.Distribution;
import top.aole.rent.modules.distribution.mapper.DistributionMapper;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.mapper.PayableMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 账期兑付缺口预警(M3-06 · P0-H)。每笔 payable 尾款到期 T-N 天,比对"该账期对应设备回款 + 未来回款 + 可动用留存",
 * <b>应付到期 − 可用回款 − 可动留存 &lt; 0 → 红灯 + 裁决人 + 补款来源</b>。进老板驾驶舱红点。
 *
 * <p><b>现金头寸模型</b>:待付 payable 按到期日升序,逐个 checkpoint 计算
 * 预计现金 = 可动用留存 + Σ收款(到期≤checkpoint) − Σ应付(到期≤checkpoint);&lt;0 即该期兑付缺口红灯。
 * <p><b>可动用留存</b> = 累计分配后留存(Σ active 分配 reserve_after) − 留存下限(20万);不足则无缓冲。
 */
@Service
@RequiredArgsConstructor
public class CoverageGapService {

    private final CashflowService cashflowService;
    private final PayableMapper payableMapper;
    private final DistributionMapper distributionMapper;
    private final RuleConfigService rules;

    private static final String ARBITER = "老板(合伙事务执行人/GP 数智云仓)";
    private static final List<String> FUNDING_SOURCES = Arrays.asList(
            "启用层级③银行融资额度(需资产回报−融资成本≥安全边际)",
            "GP/LP 股东借款过桥",
            "延后本月现金分配、提高滚存留存",
            "加速逾期收租/收回设备处置回款");

    public CashflowDtos.CoverageGapReport coverageGap(Integer tMinusDays) {
        int tMinus = tMinusDays != null && tMinusDays > 0 ? tMinusDays : 7;
        LocalDate asOf = LocalDate.now();

        BigDecimal usableReserve = usableReserve();

        // 未收应收(计划口径)+ 待付应付(按到期升序)
        List<CashflowService.Uncollected> receivables = cashflowService.uncollectedReceivables();
        List<Payable> payables = payableMapper.selectList(new LambdaQueryWrapper<Payable>()
                .eq(Payable::getStatus, "待付"));
        payables.sort(Comparator.comparing(Payable::getDueDate,
                Comparator.nullsLast(Comparator.naturalOrder())));

        CashflowDtos.CoverageGapReport rpt = new CashflowDtos.CoverageGapReport();
        rpt.setAsOf(asOf);
        rpt.setTMinusDays(tMinus);
        rpt.setUsableReserve(usableReserve.setScale(2, RoundingMode.HALF_UP));

        java.util.ArrayList<CashflowDtos.GapItem> items = new java.util.ArrayList<>();
        int redCount = 0;
        for (Payable p : payables) {
            if (p.getDueDate() == null) {
                continue;
            }
            LocalDate checkpoint = p.getDueDate();
            BigDecimal cumInflow = BigDecimal.ZERO;
            for (CashflowService.Uncollected u : receivables) {
                if (u.dueDate != null && !u.dueDate.isAfter(checkpoint)) {
                    cumInflow = cumInflow.add(u.amount);
                }
            }
            BigDecimal cumOutflow = BigDecimal.ZERO;
            for (Payable q : payables) {
                if (q.getDueDate() != null && !q.getDueDate().isAfter(checkpoint)) {
                    cumOutflow = cumOutflow.add(q.getAmount());
                }
            }
            BigDecimal projected = usableReserve.add(cumInflow).subtract(cumOutflow);
            boolean red = projected.signum() < 0;

            CashflowDtos.GapItem it = new CashflowDtos.GapItem();
            it.setPayableId(p.getId());
            it.setPurchaseInId(p.getPurchaseInId());
            it.setStage(p.getStage());
            it.setDueDate(p.getDueDate());
            it.setDaysToDue(ChronoUnit.DAYS.between(asOf, p.getDueDate()));
            it.setPayableAmount(p.getAmount().setScale(2, RoundingMode.HALF_UP));
            it.setCumInflow(cumInflow.setScale(2, RoundingMode.HALF_UP));
            it.setCumOutflow(cumOutflow.setScale(2, RoundingMode.HALF_UP));
            it.setProjectedCash(projected.setScale(2, RoundingMode.HALF_UP));
            it.setGap(projected.signum() < 0 ? projected.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
            it.setRed(red);
            if (red) {
                it.setArbiter(ARBITER);
                it.setFundingSources(FUNDING_SOURCES);
                redCount++;
            }
            items.add(it);
        }
        rpt.setItems(items);
        rpt.setRedCount(redCount);
        rpt.setHasRedAlert(redCount > 0);
        return rpt;
    }

    /** 可动用留存 = Σ active 非冲销分配 reserve_after − 留存下限(20万);下限内不可动用。 */
    private BigDecimal usableReserve() {
        BigDecimal accumulated = BigDecimal.ZERO;
        for (Distribution d : distributionMapper.selectList(new LambdaQueryWrapper<Distribution>()
                .eq(Distribution::getStatus, "active")
                .eq(Distribution::getIsReversal, 0))) {
            accumulated = accumulated.add(d.getReserveAfter());
        }
        BigDecimal floor = safe("reserve_floor", new BigDecimal("200000"));
        return accumulated.subtract(floor).max(BigDecimal.ZERO);
    }

    private BigDecimal safe(String key, BigDecimal dft) {
        try {
            return rules.getValue(key, "", LocalDate.now());
        } catch (Exception e) {
            return dft;
        }
    }
}
