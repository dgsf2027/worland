package top.aole.rent.modules.monthly.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.aole.rent.modules.monthly.domain.MonthlyReport;
import top.aole.rent.modules.monthly.dto.MonthlyDtos;
import top.aole.rent.modules.monthly.mapper.MonthlyReportMapper;

import java.time.LocalDateTime;

/**
 * 月度报表包生成调度器(M3-07 / M3-10)。§4.14 cron 双出口:
 * ① {@link #monthlyPackageGen()} @Scheduled 每月 2 日 02:00 自动生成上一自然月报表包快照;
 * ② {@link #runMonthlyReportCron(String, String)} 执行体与 cron 解耦,手动/单测直接调。
 *
 * 落库 yc_rent_monthly_report:package_json(六件套)+ analysis_json(七节)+ calendar_status,一 period 一条(幂等 upsert)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlyReportScheduler {

    private final MonthlyReportService monthlyReportService;
    private final MonthlyAnalysisService monthlyAnalysisService;
    private final MonthlyCalendarService monthlyCalendarService;
    private final MonthlyReportMapper monthlyReportMapper;

    /** 每月 2 日 02:00 自动生成报表包快照(period=null → 取最近有数账期)。 */
    @Scheduled(cron = "0 0 2 2 * ?")
    public void monthlyPackageGen() {
        MonthlyReport r = runMonthlyReportCron(null, "cron");
        log.info("[月报包 cron] 期 {} 报表包快照已生成 id={}", r.getPeriod(), r.getId());
    }

    /** 执行体:聚合六件套 + 七节 + 财务日历,序列化落库(幂等 upsert)。 */
    public MonthlyReport runMonthlyReportCron(String period, String generatedBy) {
        MonthlyDtos.PackageResp pkg = monthlyReportService.buildPackage(period);
        String p = pkg.getPeriod();
        MonthlyDtos.AnalysisReport analysis = monthlyAnalysisService.analyze(p, false);
        MonthlyDtos.CalendarBoard calendar = monthlyCalendarService.board(p);

        MonthlyReport existing = monthlyReportMapper.selectOne(new LambdaQueryWrapper<MonthlyReport>()
                .eq(MonthlyReport::getPeriod, p)
                .eq(MonthlyReport::getIsDeleted, 0)
                .last("LIMIT 1"));
        MonthlyReport row = existing != null ? existing : new MonthlyReport();
        row.setPeriod(p);
        row.setPackageJson(JSONUtil.toJsonStr(pkg));
        row.setAnalysisJson(JSONUtil.toJsonStr(analysis));
        row.setCalendarStatus(calendar.getStatus());
        row.setLlmCallId(analysis.getLlmCallId());
        row.setGeneratedBy(generatedBy);
        row.setGeneratedAt(LocalDateTime.now());
        if (existing != null) {
            monthlyReportMapper.updateById(row);
        } else {
            monthlyReportMapper.insert(row);
        }
        return row;
    }
}
