package top.aole.rent.modules.monthly.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.rent.modules.billing.domain.AccountingPeriod;
import top.aole.rent.modules.billing.mapper.AccountingPeriodMapper;
import top.aole.rent.modules.distribution.dto.DistributionDtos;
import top.aole.rent.modules.distribution.service.DistributionService;
import top.aole.rent.modules.monthly.domain.MonthlyReport;
import top.aole.rent.modules.monthly.dto.MonthlyDtos.CalendarBoard;
import top.aole.rent.modules.monthly.dto.MonthlyDtos.CalendarDay;
import top.aole.rent.modules.monthly.mapper.MonthlyReportMapper;

import java.util.List;

/**
 * 财务月度工作日历(系统方案 流程11):1/2/3/5 日各步骤状态看板。
 *
 * 状态由本期真实数据判定(非拍脑袋):
 * 1 日 结账(人工):ops 账已锁账 → AUTO_DONE,否则 PENDING_MANUAL;
 * 2 日 报表包自动:monthly_report 快照已生成 → AUTO_DONE,否则 PENDING;
 * 3 日 核对(人工):报表包就绪后待财务核对 → PENDING_MANUAL;
 * 5 日 过报告+分配:本期已有 active 分配单 → AUTO_DONE,否则 PENDING。
 */
@Service
@RequiredArgsConstructor
public class MonthlyCalendarService {

    private final AccountingPeriodMapper accountingPeriodMapper;
    private final MonthlyReportMapper monthlyReportMapper;
    private final DistributionService distributionService;
    private final MonthlyReportService monthlyReportService;

    public static final String AUTO_DONE = "AUTO_DONE";
    public static final String PENDING_MANUAL = "PENDING_MANUAL";

    public CalendarBoard board(String period) {
        String p = period != null && !period.isEmpty() ? period
                : monthlyReportService.availablePeriods().get(0);
        CalendarBoard board = new CalendarBoard();
        board.setPeriod(p);
        int pending = 0;

        boolean closed = isOpsLocked(p);
        boolean packageReady = reportExists(p);
        boolean distributed = !distributionService.list(p, true).isEmpty();

        pending += add(board, 1, "月度结账 SOP:核对账套、锁账",
                "系统汇总本期凭证/收租/折旧,待人工确认锁账",
                closed ? AUTO_DONE : PENDING_MANUAL,
                closed ? "ops 账已锁账,本项已闭环" : "ops 账尚未锁账,需财务确认结账");

        pending += add(board, 2, "查看系统自动生成的月度报表包",
                "报表包(六件套)自动生成快照",
                packageReady ? AUTO_DONE : PENDING_MANUAL,
                packageReady ? "六件套报表快照已自动生成" : "报表包快照尚未生成(2日 cron 或手动生成)");

        pending += add(board, 3, "逐项核对报表包各件套",
                "报表包亮出待核对项",
                PENDING_MANUAL,
                "六件套已就绪,待财务逐项核对");

        pending += add(board, 5, "过《月度经营分析报告》七节、定改进、执行分配",
                "AI 起草七节综述;分配单按管理费阶梯/50-50 生成",
                distributed ? AUTO_DONE : PENDING_MANUAL,
                distributed ? "本期分配单已生成,待老板过报告并定下月改进"
                        : "本期尚未执行分配,待5号结账分配");

        board.setPendingCount(pending);
        board.setStatus(distributed ? "REPORTED" : packageReady ? "PACKAGE_READY" : closed ? "REVIEWED" : "PENDING");
        return board;
    }

    private boolean isOpsLocked(String period) {
        AccountingPeriod ap = accountingPeriodMapper.selectOne(new LambdaQueryWrapper<AccountingPeriod>()
                .eq(AccountingPeriod::getPeriod, period)
                .eq(AccountingPeriod::getBook, "ops")
                .last("LIMIT 1"));
        return ap != null && Integer.valueOf(1).equals(ap.getIsLocked());
    }

    private boolean reportExists(String period) {
        MonthlyReport r = monthlyReportMapper.selectOne(new LambdaQueryWrapper<MonthlyReport>()
                .eq(MonthlyReport::getPeriod, period)
                .eq(MonthlyReport::getIsDeleted, 0)
                .last("LIMIT 1"));
        return r != null;
    }

    private int add(CalendarBoard board, int day, String finance, String auto, String state, String note) {
        CalendarDay d = new CalendarDay();
        d.setDay(day);
        d.setFinanceAction(finance);
        d.setSystemAuto(auto);
        d.setState(state);
        d.setNote(note);
        board.getDays().add(d);
        return PENDING_MANUAL.equals(state) ? 1 : 0;
    }
}
