package top.aole.rent.common.web;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.result.R;

import java.util.Arrays;
import java.util.List;

/**
 * 定时任务集登记表(M3-10):一处可查全部 cron(§4.14 cron 双出口 + startup 注册)。
 *
 * <p>每条 cron 均为「@Scheduled 入口 + run*Cron 执行体」双出口,@EnableScheduling 已在启动类注册;
 * 执行体可经对应模块的手动触发接口调用(便于运维/单测)。本表仅登记,不执行。
 */
@Api(tags = "运维 · 定时任务集登记表")
@RestController
@RequestMapping("/rent")
public class CronRegistryController {

    @Data
    @AllArgsConstructor
    public static class CronInfo {
        private String key;
        private String name;
        private String schedule;
        private String executor;
        private String manualTrigger;
        private String milestone;
    }

    @ApiOperation("列出全部定时任务(cron 表达式 / 执行体 / 手动触发入口 / 归属里程碑)")
    @GetMapping("/crons")
    public R<List<CronInfo>> list() {
        return R.ok(Arrays.asList(
                new CronInfo("rent_bill_gen", "收租单生成", "每日 01:00",
                        "RentBillGenScheduler.runRentBillGenCron",
                        "POST /api/rent/bills/gen", "M2"),
                new CronInfo("overdue_scan", "逾期检测(自动开案)", "每日 02:00",
                        "OverdueScanScheduler.runOverdueScanCron",
                        "POST /api/rent/overdue/scan", "M2"),
                new CronInfo("depreciation", "折旧计提", "每月 1 日 03:00",
                        "DepreciationScheduler.runDepreciationCron",
                        "POST /api/rent/vouchers/depreciation/run", "M3-02"),
                new CronInfo("coverage_gap_scan", "账期兑付缺口 T-N 扫描(P0-H)", "每日 05:00",
                        "CoverageGapScanScheduler.runCoverageGapCron",
                        "GET /api/rent/cashflow/coverage-gap", "M3-05/M3-10"),
                new CronInfo("distribution", "结账分配", "每月 5 日 04:00",
                        "DistributionScheduler.runDistributionCron",
                        "POST /api/rent/distribution/run", "M3-04"),
                new CronInfo("monthly_report", "月度报表包自动生成", "每月 2 日 02:00",
                        "MonthlyReportScheduler.runMonthlyReportCron",
                        "POST /api/rent/monthly-report/generate", "M3-07/M3-10"),
                new CronInfo("contract_expiry_reminder", "合同到期前30天转让提醒", "每日 06:00",
                        "ReminderScheduler.runContractExpiryCron",
                        "POST /api/rent/reminders/scan/contract-expiry", "M3-10"),
                new CronInfo("followup_due_reminder", "潜客下次跟进到期提醒", "每日 07:00",
                        "ReminderScheduler.runFollowupDueCron",
                        "POST /api/rent/reminders/scan/followup-due", "M3-10")
        ));
    }
}
