package top.aole.rent.modules.reminder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.reminder.domain.Reminder;
import top.aole.rent.modules.reminder.mapper.ReminderMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 到期提醒服务(M3-10):扫描落库,cron 与手动触发共用执行体。
 *
 * <ul>
 *   <li>合同到期前 N 天转让提醒:合同 end=start+term_months,daysLeft∈[-N, N] 落 contract_expiry;</li>
 *   <li>潜客下次跟进到期提醒:customer.next_follow_date ≤ today+lead 落 followup_due。</li>
 * </ul>
 * 幂等:同 type+ref_id+due_date 已存在 → 只更新 daysLeft/title,不重复插(唯一键兜底)。
 * 提前天数走 rule_config(reminder_contract_expiry_days / reminder_followup_lead_days),不写死。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReminderService {

    private final ContractMapper contractMapper;
    private final CustomerMapper customerMapper;
    private final ReminderMapper reminderMapper;
    private final RuleConfigService ruleConfigService;

    /** 已作废/已终止合同不再提醒(状态包含这些关键字则跳过) */
    private static final String[] TERMINAL_HINT = {"作废", "已转让", "已收回", "已结束", "关闭"};

    public static class ScanResult {
        public int scanned;
        public int upserted;
        public final List<String> details = new ArrayList<>();
    }

    // ==================== 合同到期前 N 天转让提醒 ====================

    public ScanResult scanContractExpiry() {
        int windowDays = ruleValue("reminder_contract_expiry_days", 30);
        LocalDate today = LocalDate.now();
        ScanResult res = new ScanResult();

        List<Contract> contracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getIsDeleted, 0));
        for (Contract c : contracts) {
            res.scanned++;
            if (c.getStartDate() == null || c.getTermMonths() == null) {
                continue;
            }
            if (isTerminal(c.getStatus())) {
                continue;
            }
            LocalDate end = c.getStartDate().plusMonths(c.getTermMonths());
            long daysLeft = ChronoUnit.DAYS.between(today, end);
            // 到期前后各一个窗口内提醒(含即将到期与刚逾期未处置)
            if (daysLeft > windowDays || daysLeft < -windowDays) {
                continue;
            }
            String title = daysLeft >= 0
                    ? String.format("合同 %s 将于 %s 到期(还有 %d 天),请安排期末转让/续租/收回", c.getNo(), end, daysLeft)
                    : String.format("合同 %s 已于 %s 到期(逾期 %d 天)未处置,请尽快转让/收回", c.getNo(), end, -daysLeft);
            upsert("contract_expiry", c.getId(), c.getNo(), title, end, (int) daysLeft, "业务");
            res.upserted++;
            res.details.add(title);
        }
        log.info("[到期提醒·合同] 扫描 {} 份合同,生成/更新 {} 条转让提醒", res.scanned, res.upserted);
        return res;
    }

    // ==================== 潜客下次跟进到期提醒 ====================

    public ScanResult scanFollowupDue() {
        int lead = ruleValue("reminder_followup_lead_days", 0);
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(lead);
        ScanResult res = new ScanResult();

        List<Customer> customers = customerMapper.selectList(new LambdaQueryWrapper<Customer>()
                .eq(Customer::getIsDeleted, 0)
                .isNotNull(Customer::getNextFollowDate)
                .le(Customer::getNextFollowDate, horizon));
        for (Customer cu : customers) {
            res.scanned++;
            LocalDate due = cu.getNextFollowDate();
            long daysLeft = ChronoUnit.DAYS.between(today, due);
            String title = daysLeft >= 0
                    ? String.format("客户「%s」跟进日 %s 到(还有 %d 天),请及时跟进", cu.getName(), due, daysLeft)
                    : String.format("客户「%s」跟进已逾期 %d 天(应于 %s 跟进),请尽快联系", cu.getName(), -daysLeft, due);
            upsert("followup_due", cu.getId(), cu.getName(), title, due, (int) daysLeft, "业务");
            res.upserted++;
            res.details.add(title);
        }
        log.info("[到期提醒·跟进] 扫描 {} 个到期客户,生成/更新 {} 条跟进提醒", res.scanned, res.upserted);
        return res;
    }

    // ==================== upsert(幂等)====================

    private void upsert(String type, Long refId, String refNo, String title, LocalDate due, int daysLeft, String role) {
        Reminder existing = reminderMapper.selectOne(new LambdaQueryWrapper<Reminder>()
                .eq(Reminder::getType, type)
                .eq(Reminder::getRefId, refId)
                .eq(Reminder::getDueDate, due)
                .last("LIMIT 1"));
        if (existing != null) {
            existing.setTitle(title);
            existing.setDaysLeft(daysLeft);
            existing.setRefNo(refNo);
            reminderMapper.updateById(existing);
            return;
        }
        Reminder r = new Reminder();
        r.setType(type);
        r.setRefId(refId);
        r.setRefNo(refNo);
        r.setTitle(title);
        r.setDueDate(due);
        r.setDaysLeft(daysLeft);
        r.setOwnerRole(role);
        r.setStatus("OPEN");
        reminderMapper.insert(r);
    }

    public List<Reminder> list(String type, String status) {
        LambdaQueryWrapper<Reminder> q = new LambdaQueryWrapper<Reminder>()
                .eq(Reminder::getIsDeleted, 0);
        if (type != null && !type.isEmpty()) {
            q.eq(Reminder::getType, type);
        }
        if (status != null && !status.isEmpty()) {
            q.eq(Reminder::getStatus, status);
        }
        q.orderByAsc(Reminder::getDaysLeft);
        return reminderMapper.selectList(q);
    }

    private boolean isTerminal(String status) {
        if (status == null) {
            return false;
        }
        for (String hint : TERMINAL_HINT) {
            if (status.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private int ruleValue(String key, int fallback) {
        try {
            return ruleConfigService.getValue(key, LocalDate.now()).intValue();
        } catch (Exception e) {
            return fallback;
        }
    }
}
