package top.aole.rent.modules.automation.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 自动化定时调度入口（V132）：所有任务统一委托给 {@link AutomationEngineService#runRule}，
 * 受数据库中 {@code yc_rent_automation_rule.enabled} 开关实时控制并写入执行日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutomationScheduler {

    private final AutomationEngineService engine;

    @Scheduled(cron = "0 30 6 * * ?")
    public void dailyDataSelfHeal() {
        engine.runRule("data_self_heal_scan", "CRON", false);
    }

    @Scheduled(cron = "0 0 8 * * ?")
    public void dailyInvoiceOverdueScan() {
        engine.runRule("invoice_overdue_scan", "CRON", false);
    }

    @Scheduled(cron = "0 15 8 * * ?")
    public void dailyPayableUpcomingScan() {
        engine.runRule("payable_upcoming_scan", "CRON", false);
    }

    @Scheduled(cron = "0 20 8 * * ?")
    public void dailyWarrantyExpiryScan() {
        engine.runRule("warranty_expiry_scan", "CRON", false);
    }

    @Scheduled(cron = "0 25 8 * * ?")
    public void dailyCashWaterlineAlert() {
        engine.runRule("cash_waterline_alert", "CRON", false);
    }

    @Scheduled(cron = "0 30 8 * * ?")
    public void dailyBossMorningBrief() {
        engine.runRule("boss_morning_brief", "CRON", false);
    }

    @Scheduled(cron = "0 0 9 * * ?")
    public void dailyFinanceTodo() {
        engine.runRule("finance_daily_todo", "CRON", false);
    }

    @Scheduled(cron = "0 30 9 * * ?")
    public void dailyOverdueEscalation() {
        engine.runRule("overdue_escalation_scan", "CRON", false);
    }

    @Scheduled(cron = "0 0 10 * * ?")
    public void dailyAutoNominalBuyout() {
        engine.runRule("auto_nominal_buyout", "CRON", false);
    }
}
