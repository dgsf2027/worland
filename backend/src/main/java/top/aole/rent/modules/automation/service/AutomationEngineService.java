package top.aole.rent.modules.automation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.automation.domain.AutomationLog;
import top.aole.rent.modules.automation.domain.AutomationRule;
import top.aole.rent.modules.automation.domain.BankStatement;
import top.aole.rent.modules.automation.domain.NotifyChannel;
import top.aole.rent.modules.automation.dto.AutomationDtos;
import top.aole.rent.modules.automation.mapper.AutomationLogMapper;
import top.aole.rent.modules.automation.mapper.AutomationRuleMapper;
import top.aole.rent.modules.automation.mapper.BankStatementMapper;
import top.aole.rent.modules.automation.mapper.NotifyChannelMapper;
import top.aole.rent.modules.billing.domain.OverdueCase;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.mapper.OverdueCaseMapper;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.pcontract.domain.PurchaseContract;
import top.aole.rent.modules.pcontract.domain.PurchaseContractTerm;
import top.aole.rent.modules.pcontract.domain.SupplierPaymentAlloc;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractMapper;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractTermMapper;
import top.aole.rent.modules.pcontract.mapper.SupplierPaymentAllocMapper;
import top.aole.rent.modules.project.domain.Project;
import top.aole.rent.modules.project.mapper.ProjectMapper;
import top.aole.rent.modules.task.service.TaskService;
import top.aole.rent.modules.workbench.dto.WorkbenchWaterlineDtos;
import top.aole.rent.modules.workbench.service.WorkbenchCashWaterlineService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 租赁公司系统自动化引擎（一期 + 二期总控服务 · V132）。
 * <p>统一管理：规则配置、外推通道、执行日志、4 类核心定时扫描、老板晨报、财务待办、
 * 逾期阶梯升级与催款通知函、1 元自动留购巡检、数据自愈巡检。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationEngineService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal DEFAULT_OVERDUE_DAILY_RATE = new BigDecimal("0.0005");

    private final AutomationRuleMapper ruleMapper;
    private final NotifyChannelMapper channelMapper;
    private final AutomationLogMapper logMapper;
    private final BankStatementMapper bankStatementMapper;
    private final RentBillMapper rentBillMapper;
    private final ContractMapper contractMapper;
    private final CustomerMapper customerMapper;
    private final PurchaseContractMapper pcMapper;
    private final PurchaseContractTermMapper pcTermMapper;
    private final SupplierPaymentAllocMapper spAllocMapper;
    private final ProjectMapper projectMapper;
    private final AssetMapper assetMapper;
    private final OverdueCaseMapper overdueCaseMapper;
    private final TaskService taskService;
    private final WorkbenchCashWaterlineService waterlineService;
    private final WebhookNotifyService webhookNotifyService;
    private final AutomationEventService eventService;
    private final AuditLogService auditLogService;

    // ============ 规则与通道配置 ============

    public List<AutomationRule> listRules() {
        return ruleMapper.selectList(new LambdaQueryWrapper<AutomationRule>()
                .eq(AutomationRule::getIsDeleted, 0)
                .orderByAsc(AutomationRule::getId));
    }

    @Transactional
    public AutomationRule updateRule(String ruleKey, AutomationDtos.RuleUpdateRequest req) {
        AutomationRule r = requireRule(ruleKey);
        if (req.getEnabled() != null) r.setEnabled(req.getEnabled() ? 1 : 0);
        if (req.getLeadDays() != null) r.setLeadDays(req.getLeadDays());
        if (req.getThresholdAmount() != null) r.setThresholdAmount(req.getThresholdAmount());
        if (req.getTargetRole() != null && !req.getTargetRole().trim().isEmpty()) {
            r.setTargetRole(req.getTargetRole().trim());
        }
        ruleMapper.updateById(r);
        auditLogService.record("调整自动化规则", "automation_rule", r.getId(), AuditLogService.EXECUTED,
                r.getRuleName() + " enabled=" + r.getEnabled() + " leadDays=" + r.getLeadDays()
                        + " threshold=" + r.getThresholdAmount());
        return r;
    }

    public List<NotifyChannel> listChannels() {
        return channelMapper.selectList(new LambdaQueryWrapper<NotifyChannel>()
                .eq(NotifyChannel::getIsDeleted, 0)
                .orderByDesc(NotifyChannel::getId));
    }

    @Transactional
    public NotifyChannel saveChannel(AutomationDtos.ChannelSaveRequest req) {
        String role = req.getTargetRole() == null || req.getTargetRole().trim().isEmpty()
                ? "老板" : req.getTargetRole().trim();
        if ("LP".equalsIgnoreCase(role)) {
            throw new BizException(400, "出于成本保密要求，禁止配置 LP 角色外推通道");
        }
        NotifyChannel ch;
        if (req.getId() != null) {
            ch = channelMapper.selectById(req.getId());
            if (ch == null || Integer.valueOf(1).equals(ch.getIsDeleted())) {
                throw new BizException(404, "推送通道不存在");
            }
        } else {
            ch = new NotifyChannel();
            ch.setIsDeleted(0);
        }
        ch.setName(req.getName().trim());
        ch.setChannelType(req.getChannelType().trim().toLowerCase());
        ch.setWebhookUrl(req.getWebhookUrl().trim());
        ch.setSecret(req.getSecret() == null ? null : req.getSecret().trim());
        ch.setTargetRole(role);
        ch.setEnabled(req.getEnabled() == null || req.getEnabled() ? 1 : 0);
        if (ch.getId() == null) {
            channelMapper.insert(ch);
        } else {
            channelMapper.updateById(ch);
        }
        return ch;
    }

    @Transactional
    public void deleteChannel(Long id) {
        NotifyChannel ch = channelMapper.selectById(id);
        if (ch == null) return;
        ch.setIsDeleted(1);
        channelMapper.updateById(ch);
    }

    public String testChannel(Long id) {
        NotifyChannel ch = channelMapper.selectById(id);
        if (ch == null || Integer.valueOf(1).equals(ch.getIsDeleted())) {
            throw new BizException(404, "推送通道不存在");
        }
        String err = webhookNotifyService.sendSingle(ch, "耀石租赁自动化 · 通道连通测试",
                "通道 **「" + ch.getName() + "」** 测试成功！\n- 接收角色：" + ch.getTargetRole()
                        + "\n- 测试时间：" + LocalDateTime.now().withNano(0));
        if (err != null) {
            throw new BizException(400, "测试推送失败：" + err);
        }
        return "测试消息已成功发送至「" + ch.getName() + "」";
    }

    public List<AutomationLog> listLogs(String ruleKey, int limit) {
        int cap = Math.max(1, Math.min(limit, 200));
        return logMapper.selectList(new LambdaQueryWrapper<AutomationLog>()
                .eq(ruleKey != null && !ruleKey.isEmpty(), AutomationLog::getRuleKey, ruleKey)
                .orderByDesc(AutomationLog::getId)
                .last("limit " + cap));
    }

    // ============ 统一调度执行入口（Cron & 手动试跑共用） ============

    public AutomationDtos.RunResult runRule(String ruleKey, String triggerSource, boolean forceManual) {
        AutomationRule rule = requireRule(ruleKey);
        long start = System.currentTimeMillis();
        if (!forceManual && !Integer.valueOf(1).equals(rule.getEnabled())) {
            AutomationDtos.RunResult skipped = new AutomationDtos.RunResult();
            skipped.setRuleKey(ruleKey);
            skipped.setRuleName(rule.getRuleName());
            skipped.setStatus("SKIPPED");
            skipped.setSummary("规则已关闭，跳过定时执行");
            return skipped;
        }
        // Cron 后台线程无登录用户，临时注入系统老板上下文以便复用水位线等权限口径
        boolean injected = false;
        if (UserContext.get() == null) {
            UserContext.set(new CurrentUser(0L, "系统自动化", "老板", 1L));
            injected = true;
        }
        try {
            AutomationDtos.RunResult res;
            switch (ruleKey) {
                case "boss_morning_brief":
                    res = executeBossMorningBrief(rule);
                    break;
                case "finance_daily_todo":
                    res = executeFinanceDailyTodo(rule);
                    break;
                case "invoice_overdue_scan":
                    res = executeInvoiceOverdueScan(rule);
                    break;
                case "payable_upcoming_scan":
                    res = executePayableUpcomingScan(rule);
                    break;
                case "warranty_expiry_scan":
                    res = executeWarrantyExpiryScan(rule);
                    break;
                case "cash_waterline_alert":
                    res = executeCashWaterlineAlert(rule);
                    break;
                case "overdue_escalation_scan":
                    res = executeOverdueEscalationScan(rule);
                    break;
                case "auto_nominal_buyout":
                    res = executeAutoNominalBuyoutScan(rule);
                    break;
                case "data_self_heal_scan":
                    res = executeDataSelfHealScan(rule);
                    break;
                default:
                    res = new AutomationDtos.RunResult();
                    res.setRuleKey(ruleKey);
                    res.setRuleName(rule.getRuleName());
                    res.setStatus("SUCCESS");
                    res.setSummary("实时事件开关状态：" + (Integer.valueOf(1).equals(rule.getEnabled()) ? "开启" : "关闭"));
                    break;
            }
            res.setRuleKey(ruleKey);
            res.setRuleName(rule.getRuleName());
            res.setDurationMs(System.currentTimeMillis() - start);
            persistRunOutcome(rule, triggerSource, res);
            return res;
        } catch (Exception e) {
            log.error("[自动化执行失败] ruleKey={}: {}", ruleKey, e.getMessage(), e);
            AutomationDtos.RunResult fail = new AutomationDtos.RunResult();
            fail.setRuleKey(ruleKey);
            fail.setRuleName(rule.getRuleName());
            fail.setStatus("FAILED");
            fail.setSummary("执行失败：" + e.getMessage());
            fail.setDurationMs(System.currentTimeMillis() - start);
            persistRunOutcome(rule, triggerSource, fail);
            return fail;
        } finally {
            if (injected) {
                UserContext.clear();
            }
        }
    }

    // ============ 1. 老板每日经营晨报 ============

    private AutomationDtos.RunResult executeBossMorningBrief(AutomationRule rule) {
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        int payDays = rule.getLeadDays() != null ? rule.getLeadDays() : 7;

        List<RentBill> bills = rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getIsDeleted, 0)
                .eq(RentBill::getBillKind, "正常"));
        BigDecimal yesterdayReceived = ZERO;
        int overdueCount = 0;
        BigDecimal overdueAmt = ZERO;
        int overdueInvoiceCount = 0;
        for (RentBill b : bills) {
            if (b.getMatchedAt() != null && yesterday.equals(b.getMatchedAt().toLocalDate()) && b.getReceivedAmount() != null) {
                yesterdayReceived = yesterdayReceived.add(b.getReceivedAmount());
            }
            if (("逾期".equals(b.getStatus()) || ("待收".equals(b.getStatus()) && b.getDueDate() != null && b.getDueDate().isBefore(today)))) {
                BigDecimal unpaid = nz(b.getAmount()).subtract(nz(b.getReceivedAmount())).max(ZERO);
                if (unpaid.signum() > 0) {
                    overdueCount++;
                    overdueAmt = overdueAmt.add(unpaid);
                }
            }
            if ("待开票".equals(b.getInvoiceStatus()) && b.getInvoiceDueDate() != null && b.getInvoiceDueDate().isBefore(today)) {
                overdueInvoiceCount++;
            }
        }

        List<UpcomingPayable> upcoming = findUpcomingPayables(today.plusDays(payDays));
        BigDecimal upcomingAmt = upcoming.stream().map(u -> u.unpaid).reduce(ZERO, BigDecimal::add);

        WorkbenchWaterlineDtos.CashWaterline wl = waterlineService.cashWaterline(6, null);
        long activeProjects = projectMapper.selectCount(new LambdaQueryWrapper<Project>()
                .eq(Project::getIsDeleted, 0)
                .ne(Project::getStage, "留购结项"));

        StringBuilder md = new StringBuilder();
        md.append("日期：").append(today).append("\n");
        md.append("- 昨日实收租金：**¥").append(fmt(yesterdayReceived)).append("**\n");
        md.append("- 当前逾期租金：**").append(overdueCount).append(" 笔 / ¥").append(fmt(overdueAmt)).append("**\n");
        md.append("- 未来 ").append(payDays).append(" 天采购应付：**").append(upcoming.size())
                .append(" 笔 / ¥").append(fmt(upcomingAmt)).append("**\n");
        md.append("- 超期未开 3% 销项票：**").append(overdueInvoiceCount).append(" 笔**\n");
        md.append("- 当前银行余额：¥").append(fmt(wl.getSavedCashBalance()))
                .append("，未来6月最低水位：**¥").append(fmt(wl.getMinBalance())).append("**")
                .append(wl.getMinBalanceMonth() != null ? "（" + wl.getMinBalanceMonth() + "）" : "").append("\n");
        md.append("- 在管租赁项目：**").append(activeProjects).append(" 个**");

        int pushed = webhookNotifyService.pushToRole(rule.getTargetRole(), "耀石租赁 · 老板经营晨报", md.toString());
        AutomationDtos.RunResult res = new AutomationDtos.RunResult();
        res.setStatus("SUCCESS");
        res.setAffectedCount(overdueCount + upcoming.size() + overdueInvoiceCount);
        res.setPushedCount(pushed);
        res.setSummary("晨报已生成：昨日实收 ¥" + fmt(yesterdayReceived) + "，逾期 " + overdueCount + " 笔，近"
                + payDays + "天应付 ¥" + fmt(upcomingAmt) + "，已推 " + pushed + " 个通道");
        res.setPreviewText(md.toString());
        return res;
    }

    // ============ 2. 财务/业务每日待办清单 ============

    private AutomationDtos.RunResult executeFinanceDailyTodo(AutomationRule rule) {
        LocalDate today = LocalDate.now();
        int days = rule.getLeadDays() != null ? rule.getLeadDays() : 7;

        long dueTodayBills = rentBillMapper.selectCount(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getIsDeleted, 0)
                .in(RentBill::getStatus, Arrays.asList("待收", "部分已收", "逾期"))
                .le(RentBill::getDueDate, today));
        long pendingBankStmts = bankStatementMapper.selectCount(new LambdaQueryWrapper<BankStatement>()
                .eq(BankStatement::getIsDeleted, 0)
                .in(BankStatement::getMatchStatus, Arrays.asList("已匹配", "待确认")));
        long pendingInvoices = rentBillMapper.selectCount(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getIsDeleted, 0)
                .eq(RentBill::getInvoiceStatus, "待开票"));
        List<UpcomingPayable> upcoming = findUpcomingPayables(today.plusDays(days));

        StringBuilder md = new StringBuilder();
        md.append("日期：").append(today).append("\n");
        md.append("- 今日及之前待收/逾期租金单：**").append(dueTodayBills).append(" 笔**\n");
        md.append("- 待确认核销银行流水：**").append(pendingBankStmts).append(" 笔**\n");
        md.append("- 待开 3% 销项发票：**").append(pendingInvoices).append(" 笔**\n");
        md.append("- 未来 ").append(days).append(" 天到期采购月结节点：**").append(upcoming.size()).append(" 笔**");
        if (!upcoming.isEmpty()) {
            md.append("\n\n**近期采购应付明细：**\n");
            for (int i = 0; i < Math.min(5, upcoming.size()); i++) {
                UpcomingPayable u = upcoming.get(i);
                md.append("- ").append(u.pcNo).append(" · ").append(u.stageName)
                        .append("（").append(u.dueDate).append("）：¥").append(fmt(u.unpaid)).append("\n");
            }
        }

        int pushed = webhookNotifyService.pushToRole(rule.getTargetRole(), "耀石租赁 · 财务每日待办清单", md.toString());
        AutomationDtos.RunResult res = new AutomationDtos.RunResult();
        res.setStatus("SUCCESS");
        res.setAffectedCount((int) (dueTodayBills + pendingBankStmts + pendingInvoices) + upcoming.size());
        res.setPushedCount(pushed);
        res.setSummary("今日待收 " + dueTodayBills + " 笔 · 待核销流水 " + pendingBankStmts
                + " 笔 · 待开票 " + pendingInvoices + " 笔 · 近" + days + "天应付 " + upcoming.size() + " 笔");
        res.setPreviewText(md.toString());
        return res;
    }

    // ============ 3. 收租后 3% 销项票超期/临期扫描 ============

    private AutomationDtos.RunResult executeInvoiceOverdueScan(AutomationRule rule) {
        LocalDate today = LocalDate.now();
        int lead = rule.getLeadDays() != null ? rule.getLeadDays() : 2;
        LocalDate horizon = today.plusDays(lead);

        List<RentBill> pending = rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getIsDeleted, 0)
                .eq(RentBill::getInvoiceStatus, "待开票")
                .isNotNull(RentBill::getInvoiceDueDate)
                .le(RentBill::getInvoiceDueDate, horizon)
                .orderByAsc(RentBill::getInvoiceDueDate));

        int overdueCnt = 0;
        StringBuilder md = new StringBuilder();
        for (RentBill b : pending) {
            boolean overdue = b.getInvoiceDueDate().isBefore(today);
            if (overdue) overdueCnt++;
            String title = (overdue ? "【超期未开票】" : "【临期开票】") + b.getBillNo()
                    + " 应于 " + b.getInvoiceDueDate() + " 前开 3% 销项专票（¥" + fmt(b.getReceivedAmount()) + "）";
            taskService.systemDispatch("开票提醒", title, "财务", "rent_bill_invoice",
                    b.getId(), b.getInvoiceDueDate(), overdue ? "高" : "中");
            md.append("- ").append(title).append("\n");
        }
        int pushed = 0;
        if (!pending.isEmpty()) {
            pushed = webhookNotifyService.pushToRole(rule.getTargetRole(),
                    "3% 销项发票开票预警（" + pending.size() + " 笔）", md.toString());
        }
        AutomationDtos.RunResult res = new AutomationDtos.RunResult();
        res.setStatus("SUCCESS");
        res.setAffectedCount(pending.size());
        res.setPushedCount(pushed);
        res.setSummary("命中 " + pending.size() + " 笔待开票（其中已超期 " + overdueCnt + " 笔），已自动派单给财务");
        res.setPreviewText(pending.isEmpty() ? "当前无临期或超期未开 3% 销项发票。" : md.toString());
        return res;
    }

    // ============ 4. 采购月结应付节点 T-N 预警扫描 ============

    private AutomationDtos.RunResult executePayableUpcomingScan(AutomationRule rule) {
        LocalDate today = LocalDate.now();
        int lead = rule.getLeadDays() != null ? rule.getLeadDays() : 7;
        List<UpcomingPayable> list = findUpcomingPayables(today.plusDays(lead));

        StringBuilder md = new StringBuilder();
        for (UpcomingPayable u : list) {
            boolean overdue = u.dueDate.isBefore(today);
            String title = (overdue ? "【采购应付逾期】" : "【采购应付临期】") + u.pcNo + " · " + u.stageName
                    + "（到期 " + u.dueDate + "）未付 ¥" + fmt(u.unpaid);
            taskService.systemDispatch("采购付款提醒", title, "财务", "pc_term",
                    u.termId, u.dueDate, overdue ? "高" : "中");
            md.append("- ").append(title).append("\n");
        }
        int pushed = 0;
        if (!list.isEmpty()) {
            pushed = webhookNotifyService.pushToRole(rule.getTargetRole(),
                    "采购月结应付预警（未来 " + lead + " 天 · " + list.size() + " 笔）", md.toString());
        }
        AutomationDtos.RunResult res = new AutomationDtos.RunResult();
        res.setStatus("SUCCESS");
        res.setAffectedCount(list.size());
        res.setPushedCount(pushed);
        res.setSummary("发现 " + list.size() + " 笔近期/逾期采购付款节点，已派单给财务并推送");
        res.setPreviewText(list.isEmpty() ? "未来 " + lead + " 天内无到期未付采购节点。" : md.toString());
        return res;
    }

    // ============ 5. 5 年质保 T-60 到期扫描 ============

    private AutomationDtos.RunResult executeWarrantyExpiryScan(AutomationRule rule) {
        LocalDate today = LocalDate.now();
        int lead = rule.getLeadDays() != null ? rule.getLeadDays() : 60;
        LocalDate horizon = today.plusDays(lead);

        List<Project> projects = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getIsDeleted, 0)
                .isNotNull(Project::getWarrantyEndDate)
                .ge(Project::getWarrantyEndDate, today.minusDays(30))
                .le(Project::getWarrantyEndDate, horizon));
        List<Asset> assets = assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getIsDeleted, 0)
                .isNotNull(Asset::getWarrantyEndDate)
                .ge(Asset::getWarrantyEndDate, today.minusDays(30))
                .le(Asset::getWarrantyEndDate, horizon));

        int hits = 0;
        StringBuilder md = new StringBuilder();
        for (Project p : projects) {
            long daysLeft = ChronoUnit.DAYS.between(today, p.getWarrantyEndDate());
            String title = "项目「" + p.getName() + "」质保将于 " + p.getWarrantyEndDate()
                    + " 到期（剩 " + daysLeft + " 天），请安排质保终检";
            taskService.systemDispatch("质保到期提醒", title, "业务", "project_warranty",
                    p.getId(), p.getWarrantyEndDate(), daysLeft <= 15 ? "高" : "中");
            md.append("- ").append(title).append("\n");
            hits++;
        }
        for (Asset a : assets) {
            long daysLeft = ChronoUnit.DAYS.between(today, a.getWarrantyEndDate());
            String title = "资产 " + a.getSerialNo() + "（" + a.getCategory() + "）质保将于 "
                    + a.getWarrantyEndDate() + " 到期（剩 " + daysLeft + " 天）";
            taskService.systemDispatch("质保到期提醒", title, "业务", "asset_warranty",
                    a.getId(), a.getWarrantyEndDate(), daysLeft <= 15 ? "高" : "中");
            md.append("- ").append(title).append("\n");
            hits++;
        }
        int pushed = 0;
        if (hits > 0) {
            pushed = webhookNotifyService.pushToRole(rule.getTargetRole(),
                    "货架/设备质保到期预警（" + hits + " 项）", md.toString());
        }
        AutomationDtos.RunResult res = new AutomationDtos.RunResult();
        res.setStatus("SUCCESS");
        res.setAffectedCount(hits);
        res.setPushedCount(pushed);
        res.setSummary("扫描到 " + hits + " 项质保临期/刚到期项目与资产（窗口 " + lead + " 天）");
        res.setPreviewText(hits == 0 ? "未来 " + lead + " 天内无临期质保项目或资产。" : md.toString());
        return res;
    }

    // ============ 6. 银行资金水位安全垫预警 ============

    private AutomationDtos.RunResult executeCashWaterlineAlert(AutomationRule rule) {
        BigDecimal safetyFloor = rule.getThresholdAmount() != null ? rule.getThresholdAmount() : new BigDecimal("50000");
        WorkbenchWaterlineDtos.CashWaterline wl = waterlineService.cashWaterline(6, null);
        BigDecimal minBal = nz(wl.getMinBalance());
        boolean below = minBal.compareTo(safetyFloor) < 0;

        String text = "当前银行余额：¥" + fmt(wl.getSavedCashBalance())
                + "\n未来6个月最低预测水位：**¥" + fmt(minBal) + "**"
                + (wl.getMinBalanceMonth() != null ? "（" + wl.getMinBalanceMonth() + "）" : "")
                + "\n安全垫阈值：¥" + fmt(safetyFloor)
                + (below ? "\n⚠️ **已低于安全水位，建议提前备款或催收逾期租金！**" : "\n✅ 资金水位健康，位于安全垫之上。");

        int pushed = 0;
        if (below) {
            pushed = webhookNotifyService.pushToRole(rule.getTargetRole(), "⚠️ 资金水位跌破安全垫告警", text);
        }
        AutomationDtos.RunResult res = new AutomationDtos.RunResult();
        res.setStatus("SUCCESS");
        res.setAffectedCount(below ? 1 : 0);
        res.setPushedCount(pushed);
        res.setSummary(below
                ? "⚠️ 预警：最低水位 ¥" + fmt(minBal) + " 低于安全垫 ¥" + fmt(safetyFloor)
                : "✅ 资金安全：最低水位 ¥" + fmt(minBal) + " ≥ 安全垫 ¥" + fmt(safetyFloor));
        res.setPreviewText(text);
        return res;
    }

    // ============ 7. 逾期阶梯升级与标准催款通知函 ============

    public List<AutomationDtos.CollectionNotice> listCollectionNotices(Integer leadDaysOverride) {
        LocalDate today = LocalDate.now();
        int lead = leadDaysOverride != null ? leadDaysOverride : 3;
        LocalDate horizon = today.plusDays(lead);

        List<RentBill> bills = rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getIsDeleted, 0)
                .eq(RentBill::getBillKind, "正常")
                .in(RentBill::getStatus, Arrays.asList("待收", "部分已收", "逾期"))
                .isNotNull(RentBill::getDueDate)
                .le(RentBill::getDueDate, horizon)
                .orderByAsc(RentBill::getDueDate));

        List<AutomationDtos.CollectionNotice> out = new ArrayList<>();
        for (RentBill b : bills) {
            BigDecimal unpaid = nz(b.getAmount()).subtract(nz(b.getReceivedAmount())).max(ZERO);
            if (unpaid.signum() <= 0) continue;
            Contract c = b.getContractId() == null ? null : contractMapper.selectById(b.getContractId());
            if (c != null && "已作废".equals(c.getStatus())) continue;
            Customer cust = c != null && c.getCustomerId() != null ? customerMapper.selectById(c.getCustomerId()) : null;

            long overdueDays = ChronoUnit.DAYS.between(b.getDueDate(), today);
            BigDecimal dailyRate = c != null && c.getOverdueDailyRate() != null
                    ? c.getOverdueDailyRate() : DEFAULT_OVERDUE_DAILY_RATE;
            BigDecimal penalty = overdueDays > 0
                    ? unpaid.multiply(dailyRate).multiply(BigDecimal.valueOf(overdueDays)).setScale(2, RoundingMode.HALF_UP)
                    : ZERO;

            String level;
            String notice;
            String custName = cust != null ? cust.getName() : "贵司";
            String ctNo = c != null ? c.getNo() : b.getBillNo();
            if (overdueDays <= 0) {
                level = "T-3预提醒";
                notice = String.format("【付款温馨提醒】%s您好！贵司租赁合同 %s 第 %d 期租金 ¥%s 将于 %s 到期，烦请提前安排对公打款，收款后我司将在约定工作日内开具 3%% 增值税专票。感谢配合！",
                        custName, ctNo, b.getPeriodNo() == null ? 0 : b.getPeriodNo(), fmt(unpaid), b.getDueDate());
            } else if (overdueDays < 7) {
                level = "T+1温和催收";
                notice = String.format("【租金逾期提醒】%s您好！贵司租赁合同 %s 第 %d 期租金 ¥%s 原定于 %s 支付，目前已逾期 %d 天（暂计滞纳金 ¥%s），请尽快安排付款并回传水单以便核销。",
                        custName, ctNo, b.getPeriodNo() == null ? 0 : b.getPeriodNo(), fmt(unpaid), b.getDueDate(), overdueDays, fmt(penalty));
            } else if (overdueDays < 15) {
                level = "T+7罚息升级";
                notice = String.format("【正式催款通知】%s：贵司合同 %s 第 %d 期租金 ¥%s 已逾期 %d 天，按合同约定每日 %s%% 计收违约金 ¥%s，合计应付 ¥%s。请务必于 3 个工作日内结清，避免升级处理。",
                        custName, ctNo, b.getPeriodNo() == null ? 0 : b.getPeriodNo(), fmt(unpaid), overdueDays,
                        dailyRate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString(),
                        fmt(penalty), fmt(unpaid.add(penalty)));
            } else {
                level = "T+15锁机收回预警";
                notice = String.format("【紧急催告函】%s：贵司合同 %s 第 %d 期租金 ¥%s 已严重逾期 %d 天（含违约金合计 ¥%s）。根据租赁合同物权条款，如 48 小时内未结清，我司将依法启动设备锁机/现场收回程序。",
                        custName, ctNo, b.getPeriodNo() == null ? 0 : b.getPeriodNo(), fmt(unpaid), overdueDays, fmt(unpaid.add(penalty)));
            }

            AutomationDtos.CollectionNotice cn = new AutomationDtos.CollectionNotice();
            cn.setBillId(b.getId());
            cn.setBillNo(b.getBillNo());
            cn.setContractId(b.getContractId());
            cn.setContractNo(ctNo);
            cn.setCustomerName(custName);
            cn.setPeriodNo(b.getPeriodNo());
            cn.setDueDate(b.getDueDate());
            cn.setOverdueDays(overdueDays);
            cn.setLevel(level);
            cn.setUnpaidAmount(unpaid.setScale(2, RoundingMode.HALF_UP));
            cn.setEstimatedPenalty(penalty);
            cn.setTotalDue(unpaid.add(penalty).setScale(2, RoundingMode.HALF_UP));
            cn.setNoticeText(notice);
            out.add(cn);
        }
        out.sort(Comparator.comparingLong(AutomationDtos.CollectionNotice::getOverdueDays).reversed());
        return out;
    }

    private AutomationDtos.RunResult executeOverdueEscalationScan(AutomationRule rule) {
        int lead = rule.getLeadDays() != null ? rule.getLeadDays() : 3;
        List<AutomationDtos.CollectionNotice> notices = listCollectionNotices(lead);
        int escalated = 0;
        StringBuilder md = new StringBuilder();
        for (AutomationDtos.CollectionNotice n : notices) {
            if (n.getOverdueDays() >= 7) {
                OverdueCase oc = overdueCaseMapper.selectOne(new LambdaQueryWrapper<OverdueCase>()
                        .eq(OverdueCase::getRentBillId, n.getBillId())
                        .eq(OverdueCase::getStatus, "开启")
                        .last("limit 1"));
                if (oc != null) {
                    oc.setPenaltyAmount(n.getEstimatedPenalty());
                    if (n.getOverdueDays() >= 15 && "延期".equals(oc.getStep())) {
                        oc.setStep("罚息");
                        oc.setNextAction("已逾期 " + n.getOverdueDays() + " 天，建议评估锁机/收回");
                    }
                    overdueCaseMapper.updateById(oc);
                    escalated++;
                }
            }
            md.append("- [").append(n.getLevel()).append("] ").append(n.getCustomerName())
                    .append(" · ").append(n.getContractNo()).append(" 第").append(n.getPeriodNo())
                    .append("期 欠 ¥").append(fmt(n.getUnpaidAmount())).append("\n");
        }
        int pushed = 0;
        if (!notices.isEmpty()) {
            pushed = webhookNotifyService.pushToRole(rule.getTargetRole(),
                    "租金预提醒与逾期催收清单（" + notices.size() + " 笔）", md.toString());
        }
        AutomationDtos.RunResult res = new AutomationDtos.RunResult();
        res.setStatus("SUCCESS");
        res.setAffectedCount(notices.size());
        res.setPushedCount(pushed);
        res.setSummary("生成 " + notices.size() + " 条催收/预提醒话术，自动升级逾期罚息案 " + escalated + " 件");
        res.setPreviewText(notices.isEmpty() ? "当前无临期或逾期应收租金单。" : md.toString());
        return res;
    }

    // ============ 8. 满期 1 元留购自动过户巡检 ============

    private AutomationDtos.RunResult executeAutoNominalBuyoutScan(AutomationRule rule) {
        BigDecimal maxPrice = rule.getThresholdAmount() != null ? rule.getThresholdAmount() : new BigDecimal("100");
        List<Contract> actives = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getIsDeleted, 0)
                .eq(Contract::getStatus, "生效"));
        int done = 0;
        StringBuilder md = new StringBuilder();
        for (Contract c : actives) {
            if (eventService.tryAutoNominalBuyout(c.getId(), maxPrice, "CRON")) {
                done++;
                md.append("- 合同 ").append(c.getNo()).append(" 全部租金已结清，已按 ¥")
                        .append(fmt(c.getEndTransferPrice())).append(" 自动留购结项\n");
            }
        }
        int pushed = done > 0
                ? webhookNotifyService.pushToRole(rule.getTargetRole(), "期末1元自动留购结项通知（" + done + " 份）", md.toString())
                : 0;
        AutomationDtos.RunResult res = new AutomationDtos.RunResult();
        res.setStatus("SUCCESS");
        res.setAffectedCount(done);
        res.setPushedCount(pushed);
        res.setSummary("巡检 " + actives.size() + " 份在租合同，自动办结 1 元留购过户 " + done + " 份");
        res.setPreviewText(done == 0 ? "当前暂无满期且全部收齐的 1 元留购合同。" : md.toString());
        return res;
    }

    // ============ 9. 数据自愈与异常巡检 ============

    private AutomationDtos.RunResult executeDataSelfHealScan(AutomationRule rule) {
        BigDecimal maxWeighPct = rule.getThresholdAmount() != null ? rule.getThresholdAmount() : new BigDecimal("3.0");
        WorkbenchWaterlineDtos.CleanupResult cleaned = waterlineService.cleanStaleTasks();

        long unlinkedPc = pcMapper.selectCount(new LambdaQueryWrapper<PurchaseContract>()
                .ne(PurchaseContract::getStatus, "作废")
                .isNull(PurchaseContract::getRentContractId));

        List<Project> projects = projectMapper.selectList(new LambdaQueryWrapper<Project>()
                .eq(Project::getIsDeleted, 0)
                .isNotNull(Project::getPlannedTons)
                .isNotNull(Project::getActualWeighTons));
        List<String> weighAlerts = new ArrayList<>();
        for (Project p : projects) {
            if (p.getPlannedTons().signum() <= 0) continue;
            BigDecimal diffPct = p.getActualWeighTons().subtract(p.getPlannedTons())
                    .divide(p.getPlannedTons(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).abs();
            if (diffPct.compareTo(maxWeighPct) > 0) {
                weighAlerts.add(p.getProjectNo() + "（偏差 " + diffPct.setScale(2, RoundingMode.HALF_UP) + "%）");
            }
        }

        StringBuilder md = new StringBuilder();
        md.append("- 自动清理幽灵/历史孤儿待办：**").append(cleaned.getCleanedCount()).append(" 条**\n");
        md.append("- 尚未绑定主租赁合同的采购合同：**").append(unlinkedPc).append(" 份**\n");
        md.append("- 过磅偏差超过 ±").append(maxWeighPct.stripTrailingZeros().toPlainString()).append("% 的项目：**")
                .append(weighAlerts.size()).append(" 个**");
        if (!weighAlerts.isEmpty()) {
            md.append("（").append(String.join("、", weighAlerts)).append("）");
        }

        int issues = (int) unlinkedPc + weighAlerts.size();
        int pushed = (issues > 0 || cleaned.getCleanedCount() > 0)
                ? webhookNotifyService.pushToRole(rule.getTargetRole(), "系统数据自愈巡检报告", md.toString())
                : 0;
        AutomationDtos.RunResult res = new AutomationDtos.RunResult();
        res.setStatus("SUCCESS");
        res.setAffectedCount(cleaned.getCleanedCount() + issues);
        res.setPushedCount(pushed);
        res.setSummary("自愈清理孤儿待办 " + cleaned.getCleanedCount() + " 条；待处理异常：未绑主合同采购 "
                + unlinkedPc + " 份，超磅差项目 " + weighAlerts.size() + " 个");
        res.setPreviewText(md.toString());
        return res;
    }

    // ============ 辅助方法 ============

    private static class UpcomingPayable {
        Long termId;
        String pcNo;
        String stageName;
        LocalDate dueDate;
        BigDecimal unpaid;
    }

    private List<UpcomingPayable> findUpcomingPayables(LocalDate horizon) {
        List<PurchaseContract> pcs = pcMapper.selectList(new LambdaQueryWrapper<PurchaseContract>()
                .ne(PurchaseContract::getStatus, "作废"));
        Map<Long, PurchaseContract> pcMap = new HashMap<>();
        for (PurchaseContract pc : pcs) pcMap.put(pc.getId(), pc);
        if (pcMap.isEmpty()) return new ArrayList<>();

        List<SupplierPaymentAlloc> allocs = spAllocMapper.selectList(null);
        Map<Long, BigDecimal> paidByTerm = new HashMap<>();
        for (SupplierPaymentAlloc a : allocs) {
            if (a.getTermId() != null && a.getAmount() != null) {
                paidByTerm.merge(a.getTermId(), a.getAmount(), BigDecimal::add);
            }
        }

        List<PurchaseContractTerm> terms = pcTermMapper.selectList(new LambdaQueryWrapper<PurchaseContractTerm>()
                .isNotNull(PurchaseContractTerm::getDueDate)
                .le(PurchaseContractTerm::getDueDate, horizon)
                .orderByAsc(PurchaseContractTerm::getDueDate));

        List<UpcomingPayable> out = new ArrayList<>();
        for (PurchaseContractTerm t : terms) {
            PurchaseContract pc = pcMap.get(t.getPcId());
            if (pc == null) continue;
            BigDecimal unpaid = nz(t.getAmount()).subtract(paidByTerm.getOrDefault(t.getId(), ZERO)).max(ZERO);
            if (unpaid.signum() <= 0) continue;
            UpcomingPayable u = new UpcomingPayable();
            u.termId = t.getId();
            u.pcNo = pc.getContractNo();
            u.stageName = t.getStageName();
            u.dueDate = t.getDueDate();
            u.unpaid = unpaid.setScale(2, RoundingMode.HALF_UP);
            out.add(u);
        }
        return out;
    }

    private void persistRunOutcome(AutomationRule rule, String triggerSource, AutomationDtos.RunResult res) {
        LocalDateTime now = LocalDateTime.now();
        rule.setLastRunAt(now);
        rule.setLastStatus(res.getStatus());
        rule.setLastSummary(truncate(res.getSummary(), 500));
        ruleMapper.updateById(rule);

        AutomationLog al = new AutomationLog();
        al.setRuleKey(rule.getRuleKey());
        al.setRuleName(rule.getRuleName());
        al.setTriggerSource(triggerSource);
        al.setStatus(res.getStatus());
        al.setAffectedCount(res.getAffectedCount());
        al.setPushedCount(res.getPushedCount());
        al.setSummary(truncate(res.getSummary(), 500));
        al.setDetailText(res.getPreviewText());
        al.setDurationMs(res.getDurationMs());
        al.setExecutedAt(now);
        logMapper.insert(al);
    }

    private AutomationRule requireRule(String ruleKey) {
        AutomationRule r = ruleMapper.selectOne(new LambdaQueryWrapper<AutomationRule>()
                .eq(AutomationRule::getRuleKey, ruleKey)
                .eq(AutomationRule::getIsDeleted, 0)
                .last("limit 1"));
        if (r == null) {
            throw new BizException(404, "自动化规则不存在: " + ruleKey);
        }
        return r;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    private static String fmt(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
