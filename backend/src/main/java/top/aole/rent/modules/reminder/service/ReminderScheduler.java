package top.aole.rent.modules.reminder.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到期提醒调度器(M3-10)。§4.14 cron 双出口:
 * ① {@code @Scheduled} 方法为 cron 入口(@EnableScheduling 已注册);
 * ② run*Cron 执行体与 cron 解耦,手动触发(/admin)与单测直接调。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderScheduler {

    private final ReminderService reminderService;

    /** 每日 06:00 扫合同到期(前 N 天转让提醒)。 */
    @Scheduled(cron = "0 0 6 * * ?")
    public void dailyContractExpiry() {
        runContractExpiryCron();
    }

    /** 每日 07:00 扫潜客下次跟进日到期提醒。 */
    @Scheduled(cron = "0 0 7 * * ?")
    public void dailyFollowupDue() {
        runFollowupDueCron();
    }

    public ReminderService.ScanResult runContractExpiryCron() {
        return reminderService.scanContractExpiry();
    }

    public ReminderService.ScanResult runFollowupDueCron() {
        return reminderService.scanFollowupDue();
    }
}
