package top.aole.rent.modules.reminder.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.reminder.domain.Reminder;
import top.aole.rent.modules.reminder.service.ReminderScheduler;
import top.aole.rent.modules.reminder.service.ReminderService;

import java.util.List;

/**
 * 到期提醒(M3-10):列表 + 手动触发扫描(cron 双出口的手动入口)。
 */
@Api(tags = "到期提醒 · 合同到期30天/潜客跟进到期")
@RestController
@RequestMapping("/rent/reminders")
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderService reminderService;
    private final ReminderScheduler reminderScheduler;

    @ApiOperation("提醒列表(type=contract_expiry/followup_due,status=OPEN/DONE;按剩余天数升序)")
    @GetMapping
    public R<List<Reminder>> list(@RequestParam(required = false) String type,
                                  @RequestParam(required = false) String status) {
        return R.ok(reminderService.list(type, status));
    }

    @ApiOperation("手动触发合同到期扫描(cron 同一执行体)")
    @PostMapping("/scan/contract-expiry")
    public R<ReminderService.ScanResult> scanContract() {
        return R.ok(reminderScheduler.runContractExpiryCron());
    }

    @ApiOperation("手动触发潜客跟进到期扫描(cron 同一执行体)")
    @PostMapping("/scan/followup-due")
    public R<ReminderService.ScanResult> scanFollowup() {
        return R.ok(reminderScheduler.runFollowupDueCron());
    }
}
