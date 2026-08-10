package top.aole.rent.modules.finance.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.aole.rent.modules.finance.dto.VoucherDtos;

import java.time.LocalDate;

/**
 * 月度折旧计提调度器(M3-02)。参照 §4.14 cron 双出口精神(Java 版):
 * ① {@link #monthlyDepreciation()} 带 {@code @Scheduled},每月 1 日 03:00 触发(@EnableScheduling 已注册);
 * ② 执行体委托 {@link DepreciationService#runMonthlyDepreciation(LocalDate)}——与 cron 解耦,手动/测试直接调。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DepreciationScheduler {

    private final DepreciationService depreciationService;

    /** 每月 1 日 03:00 计提上月折旧(记账期取执行当日所在月)。 */
    @Scheduled(cron = "0 0 3 1 * ?")
    public void monthlyDepreciation() {
        VoucherDtos.DepreciationRunResult r = runDepreciationCron();
        if (r.getLinesGenerated() > 0) {
            log.info("[折旧计提 cron] 期 {} 计提 {} 台 · 折旧总额 {}", r.getPeriod(), r.getLinesGenerated(), r.getTotalDepr());
        }
    }

    /** 执行体(与 cron 解耦,便于手动触发 /admin 与单测)。 */
    public VoucherDtos.DepreciationRunResult runDepreciationCron() {
        return depreciationService.runMonthlyDepreciation(LocalDate.now());
    }
}
