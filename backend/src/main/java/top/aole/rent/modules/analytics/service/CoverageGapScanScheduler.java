package top.aole.rent.modules.analytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.aole.rent.modules.analytics.dto.CashflowDtos;

/**
 * 账期兑付缺口 T-N 扫描调度器(M3-05/M3-10 · P0-H)。§4.14 cron 双出口:
 * ① {@code @Scheduled} 每日 05:00 扫描;② run*Cron 执行体与 cron 解耦,手动/单测直接调。
 *
 * <p>只读预警:调 {@link CoverageGapService#coverageGap} 算未来应付到期日的现金头寸,
 * 存在红灯缺口(可动留存+累计回款−累计应付<0)则 log.warn 报警(不改账,处置由人)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoverageGapScanScheduler {

    private final CoverageGapService coverageGapService;

    /** 默认提前 7 天预警窗口(T-7) */
    private static final int T_MINUS_DAYS = 7;

    @Scheduled(cron = "0 0 5 * * ?")
    public void dailyCoverageGapScan() {
        CashflowDtos.CoverageGapReport report = runCoverageGapCron();
        if (report.isHasRedAlert()) {
            log.warn("[兑付缺口 cron] 发现 {} 笔红灯缺口(T-{}),请裁决人及时安排补款来源", report.getRedCount(), report.getTMinusDays());
        } else {
            log.info("[兑付缺口 cron] T-{} 扫描无红灯缺口,现金覆盖健康", report.getTMinusDays());
        }
    }

    /** 执行体(与 cron 解耦,便于手动触发与单测)。 */
    public CashflowDtos.CoverageGapReport runCoverageGapCron() {
        return coverageGapService.coverageGap(T_MINUS_DAYS);
    }
}
