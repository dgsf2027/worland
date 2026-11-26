package top.aole.rent.modules.workbench.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.billing.domain.OverdueCase;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.mapper.OverdueCaseMapper;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.domain.RentSchedule;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.contract.mapper.RentScheduleMapper;
import top.aole.rent.modules.contract.service.PaymentDueDateCalculator;
import top.aole.rent.modules.pcontract.domain.PurchaseContract;
import top.aole.rent.modules.pcontract.domain.PurchaseContractTerm;
import top.aole.rent.modules.pcontract.domain.SupplierPayment;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractMapper;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractTermMapper;
import top.aole.rent.modules.pcontract.mapper.SupplierPaymentMapper;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.domain.PurchaseIn;
import top.aole.rent.modules.purchase.mapper.PayableMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.rule.domain.RuleConfig;
import top.aole.rent.modules.rule.mapper.RuleConfigMapper;
import top.aole.rent.modules.task.domain.Task;
import top.aole.rent.modules.task.mapper.TaskMapper;
import top.aole.rent.modules.workbench.dto.WorkbenchWaterlineDtos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 经营驾驶舱 · 资金水位线预测 & 历史脏数据体检清理服务（Phase 3）。
 *
 * <p>核心职责：
 * <ul>
 *   <li>保存/读取「当前银行可用余额」（存 {@code yc_rent_rule_config[bank_cash_balance]}），叠加未来 6/12 个月
 *       真实租金计划流入与采购合同/采购单应付流出，算出每月期末可用余额、最低水位月份及资金缺口。</li>
 *   <li>体检并一键清理历史测试遗留的孤儿系统任务（来源单据已关闭/结清/删除），杜绝假红灯。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class WorkbenchCashWaterlineService {

    public static final String RULE_BANK_CASH_BALANCE = "bank_cash_balance";
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final RuleConfigMapper ruleConfigMapper;
    private final ContractMapper contractMapper;
    private final RentScheduleMapper scheduleMapper;
    private final RentBillMapper billMapper;
    private final PurchaseContractMapper pcMapper;
    private final PurchaseContractTermMapper pcTermMapper;
    private final SupplierPaymentMapper spMapper;
    private final PurchaseInMapper purchaseInMapper;
    private final PayableMapper payableMapper;
    private final TaskMapper taskMapper;
    private final OverdueCaseMapper overdueCaseMapper;
    private final AuditLogService auditLogService;

    /** 读取已保存的银行可用余额（缺失兜底 0）。 */
    public BigDecimal savedCashBalance() {
        List<RuleConfig> rows = ruleConfigMapper.selectList(new LambdaQueryWrapper<RuleConfig>()
                .eq(RuleConfig::getRuleKey, RULE_BANK_CASH_BALANCE)
                .eq(RuleConfig::getScopeKey, "")
                .eq(RuleConfig::getIsDeleted, 0));
        RuleConfig latest = rows.stream()
                .max(Comparator.comparing(r -> r.getVersion() == null ? 0 : r.getVersion()))
                .orElse(null);
        return latest != null && latest.getRuleValue() != null ? scale2(latest.getRuleValue()) : scale2(ZERO);
    }

    /** 保存当前银行可用余额（老板/财务）。 */
    @Transactional
    public BigDecimal saveCashBalance(BigDecimal balance, String remark) {
        if (balance == null || balance.signum() < 0) {
            throw new BizException(400, "银行可用余额不能为负");
        }
        BigDecimal val = scale2(balance);
        List<RuleConfig> rows = ruleConfigMapper.selectList(new LambdaQueryWrapper<RuleConfig>()
                .eq(RuleConfig::getRuleKey, RULE_BANK_CASH_BALANCE)
                .eq(RuleConfig::getScopeKey, "")
                .eq(RuleConfig::getIsDeleted, 0));
        RuleConfig existing = rows.stream()
                .max(Comparator.comparing(r -> r.getVersion() == null ? 0 : r.getVersion()))
                .orElse(null);
        if (existing != null) {
            existing.setRuleValue(val);
            if (remark != null && !remark.trim().isEmpty()) {
                existing.setRemark(remark.trim());
            }
            ruleConfigMapper.updateById(existing);
        } else {
            RuleConfig rc = new RuleConfig();
            rc.setRuleKey(RULE_BANK_CASH_BALANCE);
            rc.setScopeKey("");
            rc.setRuleValue(val);
            rc.setValueType("money");
            rc.setVersion(1);
            rc.setEffectiveFrom(LocalDate.of(2025, 1, 1));
            rc.setRemark(remark == null || remark.trim().isEmpty() ? "当前银行可用余额(元)" : remark.trim());
            rc.setIsDeleted(0);
            ruleConfigMapper.insert(rc);
        }
        auditLogService.record("更新银行可用余额", "rule_config", existing != null ? existing.getId() : 0L,
                AuditLogService.EXECUTED, "银行可用余额更新为 " + val + (remark != null ? " · " + remark : ""));
        return val;
    }

    /** 未来 6/12 个月资金水位线预测（GP/LP 脱敏）。 */
    public WorkbenchWaterlineDtos.CashWaterline cashWaterline(Integer requestedMonths, BigDecimal overrideBalance) {
        int months = requestedMonths == null ? 6 : requestedMonths;
        if (months != 6 && months != 12) {
            throw new BizException(400, "预测月份仅支持 6 或 12");
        }
        String role = UserContext.getRole();
        boolean seeCost = DataScope.canSeeCost(role);
        LocalDate today = LocalDate.now();
        YearMonth startYm = YearMonth.from(today);

        WorkbenchWaterlineDtos.CashWaterline out = new WorkbenchWaterlineDtos.CashWaterline();
        out.setCostVisible(seeCost);
        out.setAsOf(today);
        out.setMonths(months);
        if (!seeCost) {
            return out;
        }

        BigDecimal saved = savedCashBalance();
        BigDecimal opening = overrideBalance != null ? scale2(overrideBalance.max(ZERO)) : saved;
        out.setSavedCashBalance(saved);
        out.setOpeningCashBalance(opening);

        // 初始化未来 N 个月桶
        LinkedHashMap<YearMonth, BigDecimal[]> buckets = new LinkedHashMap<>();
        for (int i = 0; i < months; i++) {
            buckets.put(startYm.plusMonths(i), new BigDecimal[]{ZERO, ZERO});
        }
        YearMonth endYm = startYm.plusMonths(months - 1);
        LocalDate due30dLimit = today.plusDays(30);

        // ---- 1. 未来租金计划流入 + 历史已逾期未收 ----
        List<Contract> activeContracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getIsDeleted, 0)
                .eq(Contract::getStatus, "生效"));
        Set<Long> activeContractIds = new HashSet<>();
        activeContracts.forEach(c -> activeContractIds.add(c.getId()));

        Map<Long, RentBill> billById = new HashMap<>();
        BigDecimal overdueReceivable = ZERO;
        if (!activeContractIds.isEmpty()) {
            List<RentBill> bills = billMapper.selectList(new LambdaQueryWrapper<RentBill>()
                    .eq(RentBill::getIsDeleted, 0)
                    .in(RentBill::getContractId, activeContractIds)
                    .eq(RentBill::getBillKind, "正常")
                    .ne(RentBill::getStatus, "红冲"));
            for (RentBill b : bills) {
                billById.put(b.getId(), b);
                if (!"已核销".equals(b.getStatus())) {
                    BigDecimal rem = nz(b.getAmount()).subtract(nz(b.getReceivedAmount())).max(ZERO);
                    if (rem.signum() > 0 && ("逾期".equals(b.getStatus()) || (b.getDueDate() != null && b.getDueDate().isBefore(today)))) {
                        overdueReceivable = overdueReceivable.add(rem);
                    }
                }
            }

            List<RentSchedule> schedules = scheduleMapper.selectList(new LambdaQueryWrapper<RentSchedule>()
                    .eq(RentSchedule::getIsDeleted, 0)
                    .in(RentSchedule::getContractId, activeContractIds));
            for (RentSchedule s : schedules) {
                if (s.getDueDate() == null) {
                    continue;
                }
                BigDecimal amt = nz(s.getAmount());
                if (s.getRentBillId() != null) {
                    RentBill rb = billById.get(s.getRentBillId());
                    if (rb != null) {
                        if ("已核销".equals(rb.getStatus())) {
                            continue;
                        }
                        amt = nz(rb.getAmount()).subtract(nz(rb.getReceivedAmount())).max(ZERO);
                    }
                }
                if (amt.signum() <= 0) {
                    continue;
                }
                YearMonth ym = YearMonth.from(s.getDueDate());
                if (!ym.isBefore(startYm) && !ym.isAfter(endYm)) {
                    buckets.get(ym)[0] = buckets.get(ym)[0].add(amt);
                }
            }
        }

        // ---- 2. 采购合同未付节点 + 独立采购单待付应付（不重复计数） ----
        BigDecimal overduePayable = ZERO;
        int due30dCount = 0;
        BigDecimal due30dAmount = ZERO;

        List<PurchaseContract> pcs = pcMapper.selectList(new LambdaQueryWrapper<PurchaseContract>()
                .ne(PurchaseContract::getStatus, "作废")
                .ne(PurchaseContract::getStatus, "已结清"));
        Set<Long> activePcIds = new HashSet<>();
        for (PurchaseContract pc : pcs) {
            activePcIds.add(pc.getId());
            List<SupplierPayment> sps = spMapper.selectList(new LambdaQueryWrapper<SupplierPayment>()
                    .eq(SupplierPayment::getPcId, pc.getId())
                    .eq(SupplierPayment::getStatus, "有效"));
            BigDecimal pcPaid = ZERO;
            for (SupplierPayment sp : sps) {
                pcPaid = pcPaid.add(nz(sp.getAmount()));
            }
            List<PurchaseContractTerm> terms = pcTermMapper.selectList(new LambdaQueryWrapper<PurchaseContractTerm>()
                    .eq(PurchaseContractTerm::getPcId, pc.getId())
                    .orderByAsc(PurchaseContractTerm::getSortNo).orderByAsc(PurchaseContractTerm::getId));
            BigDecimal remainPaid = pcPaid;
            for (PurchaseContractTerm term : terms) {
                BigDecimal tAmt = nz(term.getAmount());
                if (tAmt.signum() <= 0 && term.getRatio() != null && pc.getTotalAmount() != null) {
                    tAmt = pc.getTotalAmount().multiply(term.getRatio()).setScale(2, RoundingMode.HALF_UP);
                }
                BigDecimal covered = remainPaid.min(tAmt);
                remainPaid = remainPaid.subtract(covered);
                BigDecimal unpaid = tAmt.subtract(covered);
                if (unpaid.signum() <= 0) {
                    continue;
                }
                LocalDate due = term.getDueDate();
                if (due == null) {
                    LocalDate base = pc.getAuditPassDate() != null ? pc.getAuditPassDate()
                            : (pc.getAcceptanceDate() != null ? pc.getAcceptanceDate() : pc.getSignedDate());
                    due = PaymentDueDateCalculator.computeDueDate(base, term.getDueRule(), term.getDueDays(), term.getDueDayOfMonth());
                }
                if (due == null) {
                    continue;
                }
                if (!due.isAfter(due30dLimit)) {
                    due30dCount++;
                    due30dAmount = due30dAmount.add(unpaid);
                }
                if (due.isBefore(today) && YearMonth.from(due).isBefore(startYm)) {
                    overduePayable = overduePayable.add(unpaid);
                } else {
                    YearMonth ym = YearMonth.from(due);
                    if (!ym.isBefore(startYm) && !ym.isAfter(endYm)) {
                        buckets.get(ym)[1] = buckets.get(ym)[1].add(unpaid);
                    }
                }
            }
        }

        // 未关联采购合同的独立采购单待付应付
        List<PurchaseIn> standalonePos = purchaseInMapper.selectList(new LambdaQueryWrapper<PurchaseIn>()
                .eq(PurchaseIn::getIsDeleted, 0)
                .ne(PurchaseIn::getStatus, "已红冲"));
        Set<Long> standalonePoIds = new HashSet<>();
        for (PurchaseIn po : standalonePos) {
            if (po.getPcId() == null || !activePcIds.contains(po.getPcId())) {
                standalonePoIds.add(po.getId());
            }
        }
        if (!standalonePoIds.isEmpty()) {
            List<Payable> payables = payableMapper.selectList(new LambdaQueryWrapper<Payable>()
                    .eq(Payable::getIsDeleted, 0)
                    .eq(Payable::getStatus, "待付")
                    .in(Payable::getPurchaseInId, standalonePoIds));
            for (Payable p : payables) {
                BigDecimal amt = nz(p.getAmount());
                if (amt.signum() <= 0 || p.getDueDate() == null) {
                    continue;
                }
                LocalDate due = p.getDueDate();
                if (!due.isAfter(due30dLimit)) {
                    due30dCount++;
                    due30dAmount = due30dAmount.add(amt);
                }
                if (due.isBefore(today) && YearMonth.from(due).isBefore(startYm)) {
                    overduePayable = overduePayable.add(amt);
                } else {
                    YearMonth ym = YearMonth.from(due);
                    if (!ym.isBefore(startYm) && !ym.isAfter(endYm)) {
                        buckets.get(ym)[1] = buckets.get(ym)[1].add(amt);
                    }
                }
            }
        }

        // ---- 3. 逐月推演可用余额水位线 ----
        BigDecimal running = opening.subtract(overduePayable);
        BigDecimal totalIn = ZERO;
        BigDecimal totalOut = ZERO;
        BigDecimal minBal = null;
        String minMonth = null;
        String firstShortfallMonth = null;
        BigDecimal maxShortfall = ZERO;

        for (Map.Entry<YearMonth, BigDecimal[]> entry : buckets.entrySet()) {
            BigDecimal in = scale2(entry.getValue()[0]);
            BigDecimal outAmt = scale2(entry.getValue()[1]);
            BigDecimal net = scale2(in.subtract(outAmt));
            running = scale2(running.add(net));
            BigDecimal shortfall = running.signum() < 0 ? running.negate() : ZERO;

            WorkbenchWaterlineDtos.WaterlinePoint pt = new WorkbenchWaterlineDtos.WaterlinePoint();
            pt.setMonth(entry.getKey().toString());
            pt.setPlannedInflow(in);
            pt.setPlannedOutflow(outAmt);
            pt.setNetFlow(net);
            pt.setEndingBalance(running);
            pt.setShortfall(scale2(shortfall));
            out.getPoints().add(pt);

            totalIn = totalIn.add(in);
            totalOut = totalOut.add(outAmt);
            if (minBal == null || running.compareTo(minBal) < 0) {
                minBal = running;
                minMonth = pt.getMonth();
            }
            if (shortfall.signum() > 0) {
                if (firstShortfallMonth == null) {
                    firstShortfallMonth = pt.getMonth();
                }
                if (shortfall.compareTo(maxShortfall) > 0) {
                    maxShortfall = shortfall;
                }
            }
        }

        out.setOverdueReceivable(scale2(overdueReceivable));
        out.setOverduePayable(scale2(overduePayable));
        out.setDuePayable30dCount(due30dCount);
        out.setDuePayable30dAmount(scale2(due30dAmount));
        out.setTotalInflow(scale2(totalIn));
        out.setTotalOutflow(scale2(totalOut));
        out.setTotalNetFlow(scale2(totalIn.subtract(totalOut)));
        out.setMinBalance(minBal == null ? opening : scale2(minBal));
        out.setMinBalanceMonth(minMonth);
        out.setFirstShortfallMonth(firstShortfallMonth);
        out.setMaxShortfall(scale2(maxShortfall));
        return out;
    }

    /** 数据体检：扫描孤儿系统任务、未绑主合同的采购单据、真实逾期单与待开销项票。 */
    public WorkbenchWaterlineDtos.DataHealth dataHealth() {
        LocalDate today = LocalDate.now();
        List<Task> staleTasks = findStaleSystemTasks();

        // 真实逾期正常收租单 & 待开销项票
        List<Contract> activeContracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getIsDeleted, 0)
                .ne(Contract::getStatus, "已作废"));
        Set<Long> validContractIds = new HashSet<>();
        activeContracts.forEach(c -> validContractIds.add(c.getId()));

        int realOverdue = 0;
        BigDecimal realOverdueAmt = ZERO;
        int pendingInv = 0;
        int overdueInv = 0;
        if (!validContractIds.isEmpty()) {
            List<RentBill> bills = billMapper.selectList(new LambdaQueryWrapper<RentBill>()
                    .eq(RentBill::getIsDeleted, 0)
                    .in(RentBill::getContractId, validContractIds)
                    .eq(RentBill::getBillKind, "正常")
                    .ne(RentBill::getStatus, "红冲"));
            for (RentBill b : bills) {
                if ("逾期".equals(b.getStatus())
                        || (("待收".equals(b.getStatus()) || "部分已收".equals(b.getStatus()))
                        && b.getDueDate() != null && b.getDueDate().isBefore(today))) {
                    BigDecimal rem = nz(b.getAmount()).subtract(nz(b.getReceivedAmount())).max(ZERO);
                    if (rem.signum() > 0) {
                        realOverdue++;
                        realOverdueAmt = realOverdueAmt.add(rem);
                    }
                }
                if ("待开票".equals(b.getInvoiceStatus())) {
                    pendingInv++;
                    if (b.getInvoiceDueDate() != null && b.getInvoiceDueDate().isBefore(today)) {
                        overdueInv++;
                    }
                }
            }
        }

        long unlinkedPc = pcMapper.selectCount(new LambdaQueryWrapper<PurchaseContract>()
                .ne(PurchaseContract::getStatus, "作废")
                .isNull(PurchaseContract::getRentContractId));
        long unlinkedPo = purchaseInMapper.selectCount(new LambdaQueryWrapper<PurchaseIn>()
                .eq(PurchaseIn::getIsDeleted, 0)
                .ne(PurchaseIn::getStatus, "已红冲")
                .isNull(PurchaseIn::getContractId));

        WorkbenchWaterlineDtos.DataHealth dh = new WorkbenchWaterlineDtos.DataHealth();
        dh.setStaleTaskCount(staleTasks.size());
        dh.setRealOverdueBillCount(realOverdue);
        dh.setRealOverdueBillAmount(scale2(realOverdueAmt));
        dh.setPendingInvoiceCount(pendingInv);
        dh.setOverdueInvoiceCount(overdueInv);
        dh.setUnlinkedPurchaseContractCount((int) unlinkedPc);
        dh.setUnlinkedPurchaseInCount((int) unlinkedPo);
        dh.setHealthy(staleTasks.isEmpty() && realOverdue == 0 && overdueInv == 0 && unlinkedPc == 0 && unlinkedPo == 0);

        dh.getItems().add(healthItem("stale_tasks", "历史残留孤儿系统任务", staleTasks.size(),
                staleTasks.isEmpty() ? "ok" : "warn",
                staleTasks.isEmpty() ? "无残留孤儿任务" : "来源单据已关闭/删除，可点下方「一键清理」消除假待办", "/task"));
        dh.getItems().add(healthItem("real_overdue", "真实逾期收租单", realOverdue,
                realOverdue == 0 ? "ok" : "critical",
                realOverdue == 0 ? "当前无逾期未收租金" : "逾期未收合计 ¥" + scale2(realOverdueAmt), "/rent?status=逾期"));
        dh.getItems().add(healthItem("pending_invoice", "待开销项专票（含超期 " + overdueInv + "）", pendingInv,
                overdueInv > 0 ? "critical" : (pendingInv > 0 ? "warn" : "ok"),
                pendingInv == 0 ? "已收租金全部开票完结" : "请按合同约定工作日及时开具销项专票", "/rent?invoiceStatus=待开票"));
        dh.getItems().add(healthItem("unlinked_pc", "未绑定主租赁合同的采购合同", (int) unlinkedPc,
                unlinkedPc == 0 ? "ok" : "warn",
                unlinkedPc == 0 ? "所有采购合同已关联租赁项目" : "先采后签合同待补绑主租赁合同，绑定后自动计入项目利润", "/purchase-contract"));
        dh.getItems().add(healthItem("unlinked_po", "未绑定主租赁合同的采购单", (int) unlinkedPo,
                unlinkedPo == 0 ? "ok" : "warn",
                unlinkedPo == 0 ? "所有采购单已关联租赁项目" : "先采后签采购单待补绑租赁合同", "/purchase"));
        return dh;
    }

    /** 一键清理孤儿系统任务（来源逾期案/合同已关闭、结清或不存在）。 */
    @Transactional
    public WorkbenchWaterlineDtos.CleanupResult cleanStaleTasks() {
        List<Task> stale = findStaleSystemTasks();
        WorkbenchWaterlineDtos.CleanupResult res = new WorkbenchWaterlineDtos.CleanupResult();
        for (Task t : stale) {
            t.setStatus("已作废");
            String base = t.getRemark() == null ? "" : t.getRemark();
            t.setRemark((base.isEmpty() ? "" : base + " | ") + "数据体检自动清理（来源单据已关闭/结清/删除）");
            taskMapper.updateById(t);
            res.getDetails().add((t.getNo() != null ? t.getNo() : "#" + t.getId()) + " · " + t.getTitle());
        }
        res.setCleanedCount(stale.size());
        if (!stale.isEmpty()) {
            auditLogService.record("清理孤儿系统任务", "task", 0L, AuditLogService.EXECUTED,
                    "清理历史无效系统任务 " + stale.size() + " 条");
        }
        return res;
    }

    private List<Task> findStaleSystemTasks() {
        List<Task> openSys = taskMapper.selectList(new LambdaQueryWrapper<Task>()
                .eq(Task::getIsDeleted, 0)
                .eq(Task::getSource, "系统")
                .notIn(Task::getStatus, Arrays.asList("已完成", "已作废")));
        List<Task> stale = new ArrayList<>();
        for (Task t : openSys) {
            if ("overdue_case".equals(t.getBizType()) || "催收".equals(t.getType())) {
                if (t.getBizId() == null) {
                    stale.add(t);
                    continue;
                }
                OverdueCase oc = overdueCaseMapper.selectById(t.getBizId());
                if (oc == null || Integer.valueOf(1).equals(oc.getIsDeleted()) || !"开启".equals(oc.getStatus())) {
                    stale.add(t);
                    continue;
                }
                RentBill rb = oc.getRentBillId() == null ? null : billMapper.selectById(oc.getRentBillId());
                if (rb == null || Integer.valueOf(1).equals(rb.getIsDeleted())
                        || "已核销".equals(rb.getStatus()) || "红冲".equals(rb.getStatus())) {
                    stale.add(t);
                    continue;
                }
                Contract c = rb.getContractId() == null ? null : contractMapper.selectById(rb.getContractId());
                if (c == null || Integer.valueOf(1).equals(c.getIsDeleted()) || "已作废".equals(c.getStatus())) {
                    stale.add(t);
                }
            } else if ("contract".equals(t.getBizType())) {
                if (t.getBizId() == null) {
                    stale.add(t);
                    continue;
                }
                Contract c = contractMapper.selectById(t.getBizId());
                if (c == null || Integer.valueOf(1).equals(c.getIsDeleted()) || !"生效".equals(c.getStatus())) {
                    stale.add(t);
                }
            }
        }
        return stale;
    }

    private static WorkbenchWaterlineDtos.HealthItem healthItem(
            String key, String label, int count, String level, String suggestion, String link) {
        WorkbenchWaterlineDtos.HealthItem h = new WorkbenchWaterlineDtos.HealthItem();
        h.setKey(key);
        h.setLabel(label);
        h.setCount(count);
        h.setLevel(level);
        h.setSuggestion(suggestion);
        h.setLink(link);
        return h;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    private static BigDecimal scale2(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }
}
