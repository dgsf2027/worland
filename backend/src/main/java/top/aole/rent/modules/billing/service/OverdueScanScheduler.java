package top.aole.rent.modules.billing.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import top.aole.rent.modules.billing.dto.BillDtos;

/**
 * 逾期检测调度器(M2-05)。参照 §4.14 cron 双出口精神(Java 版):
 * ① {@link #dailyOverdueScan()} 带 {@code @Scheduled},每日 02:00 触发;
 * ② 执行体委托 {@link OverdueService#runOverdueScan()}——与 cron 解耦,手动/测试直接调。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OverdueScanScheduler {

    private final OverdueService overdueService;

    /** 每日 02:00 扫过期未付收租单,自动标逾期 + 开案。 */
    @Scheduled(cron = "0 0 2 * * ?")
    public void dailyOverdueScan() {
        BillDtos.OverdueScanResult r = runOverdueScanCron();
        if (r.getOpened() > 0) {
            log.info("[逾期检测 cron] 标逾期 {} 单, 开案 {} 个", r.getMarked(), r.getOpened());
        }
    }

    /** 执行体(与 cron 解耦,便于手动触发 /admin 与单测)。 */
    public BillDtos.OverdueScanResult runOverdueScanCron() {
        return overdueService.runOverdueScan();
    }
}
