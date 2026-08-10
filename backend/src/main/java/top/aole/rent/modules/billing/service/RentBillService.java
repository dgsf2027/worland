package top.aole.rent.modules.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.billing.domain.AccountingPeriod;
import top.aole.rent.modules.billing.domain.OverdueCase;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.dto.BillDtos;
import top.aole.rent.modules.billing.mapper.AccountingPeriodMapper;
import top.aole.rent.modules.billing.mapper.OverdueCaseMapper;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.domain.RentSchedule;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.contract.mapper.RentScheduleMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.finance.dto.VoucherDtos;
import top.aole.rent.modules.finance.service.VoucherService;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 收租服务(M2-01/02/03/04)。收租单生成 / 到账核销 / 红冲(P0-F) / 退款 / 钱该动没动稽核。
 *
 * <p><b>单一真相源(§4.24)</b>:{@code rent_bill} = 收款态 owner;{@code rent_schedule.plan_status}
 * 单向回写(生成单→"已生成单",红冲→回退"未到期")。设备状态机仍归 AssetService。
 * <p><b>P0-F 红冲三保险</b>:①{@code @Transactional} 单事务原子 ②{@code reverses_id} 唯一约束幂等键
 * (重复红冲 DB 层 DuplicateKey 被拒) ③锁账守卫(落 is_locked 期的写一律拒,只走上期调整)。
 * <p><b>收入记账简化</b>:核销即以 {@code rent_bill(status=已核销,received_amount)} 作简化收入流水;
 * 完整双账凭证 M3 接入,{@code voucher_id} 为其预留钩子(M2 恒 NULL,稽核据此亮"已核销缺凭证")。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RentBillService {

    private final RentBillMapper rentBillMapper;
    private final RentScheduleMapper rentScheduleMapper;
    private final ContractMapper contractMapper;
    private final CustomerMapper customerMapper;
    private final AccountingPeriodMapper accountingPeriodMapper;
    private final OverdueCaseMapper overdueCaseMapper;
    private final RuleConfigService rules;
    private final AuditLogService auditLogService;
    private final VoucherService voucherService;

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");
    /** 收入记账账套(分期收款销售);锁账守卫按此账套判期。 */
    private static final String BOOK = "tax";

    // ============ M2-01 收租单生成(cron 执行体) ============

    /**
     * 按 rent_schedule 到期日 T-lead 生成收租单,回写 plan_status=已生成单 + rent_bill_id。
     * 幂等:已回填 rent_bill_id 或 plan_status=已生成单 的计划行跳过;单事务保证"建单+回写"同生共死。
     */
    @Transactional
    public BillDtos.GenResult runRentBillGen() {
        int lead = leadDays();
        LocalDate horizon = LocalDate.now().plusDays(lead);
        // 到期窗口内、尚未生成单的计划行(排除已删/已生成)
        List<RentSchedule> due = rentScheduleMapper.selectList(new LambdaQueryWrapper<RentSchedule>()
                .le(RentSchedule::getDueDate, horizon)
                .ne(RentSchedule::getPlanStatus, "已生成单")
                .isNull(RentSchedule::getRentBillId)
                .orderByAsc(RentSchedule::getContractId).orderByAsc(RentSchedule::getPeriodNo));

        BillDtos.GenResult r = new BillDtos.GenResult();
        r.setHorizon(horizon);
        List<String> bills = new ArrayList<>();
        int skipped = 0;
        for (RentSchedule s : due) {
            Contract c = contractMapper.selectById(s.getContractId());
            // 仅生效合同生成;草稿/关闭/作废跳过
            if (c == null || !"生效".equals(c.getStatus())) {
                skipped++;
                continue;
            }
            RentBill bill = new RentBill();
            bill.setBillNo(genBillNo(c, s.getPeriodNo()));
            bill.setContractId(s.getContractId());
            bill.setPeriodNo(s.getPeriodNo());
            bill.setDueDate(s.getDueDate());
            bill.setAmount(s.getAmount());
            bill.setReceivedAmount(BigDecimal.ZERO);
            bill.setStatus("待收");
            bill.setBillKind("正常");
            bill.setOperatorId(currentUserId());
            bill.setRemark("cron 到期生成(T-" + lead + ")");
            rentBillMapper.insert(bill);

            // 回写租金计划:plan_status=已生成单 + rent_bill_id
            s.setPlanStatus("已生成单");
            s.setRentBillId(bill.getId());
            rentScheduleMapper.updateById(s);
            bills.add(bill.getBillNo());
        }
        r.setGenerated(bills.size());
        r.setSkipped(skipped);
        r.setBills(bills);
        if (!bills.isEmpty()) {
            log.info("[收租单生成] 窗口<= {}, 生成 {} 张, 跳过 {}", horizon, bills.size(), skipped);
        }
        return r;
    }

    // ============ 列表 / 详情 ============

    public PageResult<BillDtos.BillItem> list(String status, Long contractId, String billKind, int page, int size) {
        LambdaQueryWrapper<RentBill> qw = new LambdaQueryWrapper<RentBill>()
                .eq(status != null && !status.isEmpty(), RentBill::getStatus, status)
                .eq(contractId != null, RentBill::getContractId, contractId)
                .eq(billKind != null && !billKind.isEmpty(), RentBill::getBillKind, billKind)
                .orderByDesc(RentBill::getId);
        List<RentBill> all = rentBillMapper.selectList(qw);
        List<BillDtos.BillItem> items = new ArrayList<>();
        for (RentBill b : all) {
            items.add(toItem(b));
        }
        long total = items.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(items.size(), from + size);
        List<BillDtos.BillItem> records = from >= items.size() ? new ArrayList<>() : items.subList(from, to);
        return new PageResult<>(total, page, size, records);
    }

    public BillDtos.BillDetail detail(Long id) {
        RentBill b = load(id);
        BillDtos.BillDetail d = new BillDtos.BillDetail();
        d.setBill(toItem(b));
        // 关联单:指向本单的红冲 + 退款 + 同期罚息
        List<RentBill> related = rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .and(w -> w.eq(RentBill::getReversesId, id).or().eq(RentBill::getRefBillId, id)));
        List<BillDtos.BillItem> relItems = new ArrayList<>();
        for (RentBill r : related) {
            relItems.add(toItem(r));
        }
        d.setRelated(relItems);
        OverdueCase oc = overdueCaseMapper.selectOne(new LambdaQueryWrapper<OverdueCase>()
                .eq(OverdueCase::getRentBillId, id).last("limit 1"));
        d.setOverdueCaseId(oc != null ? oc.getId() : null);
        return d;
    }

    // ============ M2-02 到账核销 ============

    /**
     * 到账匹配核销:金额一致自动核销(不一致→人工处理,拒绝)。
     * 核销即写简化收入流水(rent_bill 已核销 + received_amount);记账期=当月,受锁账守卫。
     * 若该单处逾期且有开启中的逾期案 → 顺带关闭案(还款恢复)。
     */
    @Transactional
    public BillDtos.BillItem match(Long id, BillDtos.MatchRequest req) {
        RentBill b = load(id);
        if ("红冲".equals(b.getStatus())) {
            throw new BizException(400, "收租单已红冲,不可核销");
        }
        if ("已核销".equals(b.getStatus())) {
            throw new BizException(400, "收租单已核销(幂等拒):" + b.getBillNo());
        }
        BigDecimal received = req != null && req.getReceivedAmount() != null
                ? req.getReceivedAmount() : b.getAmount();
        if (received.compareTo(b.getAmount()) != 0) {
            throw new BizException(400, "到账金额(" + received + ")与应收(" + b.getAmount()
                    + ")不一致,需人工处理(部分/超额收款 M3 精确化)");
        }
        String period = LocalDate.now().format(YM);
        assertPeriodOpen(period);   // 锁账守卫:收入落当期,期锁则拒

        b.setReceivedAmount(received);
        b.setStatus("已核销");
        b.setMatchedAt(LocalDateTime.now());
        b.setAccountPeriod(period);
        if (req != null && req.getRemark() != null) {
            b.setRemark(appendRemark(b.getRemark(), req.getRemark()));
        }
        // M3-01:核销即生成收入凭证(税务账+经营账各一·借贷平衡),回填 voucher_id(稽核 matchedNoVoucher 归 0)
        Long voucherId = voucherService.postRentIncome(id, b.getAmount(), LocalDate.now(),
                contractNo(b.getContractId()), b.getPeriodNo() != null ? b.getPeriodNo() : 0);
        b.setVoucherId(voucherId);
        rentBillMapper.updateById(b);

        // 还款恢复:核销逾期单 → 关闭其开启中的逾期案
        OverdueCase oc = overdueCaseMapper.selectOne(new LambdaQueryWrapper<OverdueCase>()
                .eq(OverdueCase::getRentBillId, id).eq(OverdueCase::getStatus, "开启").last("limit 1"));
        if (oc != null) {
            oc.setStep("关闭");
            oc.setStatus("关闭");
            oc.setClosedAt(LocalDateTime.now());
            oc.setNextAction(null);
            oc.setRemark(appendRemark(oc.getRemark(), "还款到账核销·案关闭恢复"));
            overdueCaseMapper.updateById(oc);
            log.info("[还款恢复] 核销逾期单 {} 顺带关闭逾期案 #{}", b.getBillNo(), oc.getId());
        }

        auditLogService.record("收租核销", "rent_bill", id, AuditLogService.EXECUTED,
                "核销 " + b.getBillNo() + " 收 " + received + " 期=" + period);
        log.info("[核销] {} 收 {} 期 {} by {}", b.getBillNo(), received, period, currentUserId());
        return toItem(load(id));
    }

    @Transactional
    public List<BillDtos.BillItem> batchMatch(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BizException(400, "批量核销 ids 不能为空");
        }
        List<BillDtos.BillItem> out = new ArrayList<>();
        for (Long id : ids) {
            out.add(match(id, null));   // 各单按应收全额核销
        }
        return out;
    }

    // ============ M2-03 红冲(P0-F:原子 + 幂等 + 锁账守卫) ============

    /**
     * 红冲一张收租单:
     * ① 单事务原子(失败整体回滚);
     * ② reverses_id 唯一约束(幂等键)——重复红冲触发 DuplicateKey,DB 层拒;
     * ③ 锁账守卫——原单记账期(已核销单)或到期月(待收单)若 is_locked 则拒,只走上期调整;
     * ④ 生成负额红冲行 + 原单转红冲 + 回退租金计划(已生成单→未到期,清 rent_bill_id)。
     * 返回影响清单。
     */
    @Transactional
    public BillDtos.ReverseImpact reverse(Long id, BillDtos.ReverseRequest req) {
        RentBill b = load(id);
        if ("红冲".equals(b.getStatus())) {
            throw new BizException(400, "收租单已是红冲态,不可再红冲");
        }
        if ("红冲".equals(b.getBillKind())) {
            throw new BizException(400, "红冲行本身不可再红冲");
        }
        String reason = req != null && req.getReason() != null ? req.getReason() : "收租红冲";

        // 锁账守卫:已核销取记账期,未核销取到期月;缺则当月
        String period = b.getAccountPeriod() != null ? b.getAccountPeriod()
                : (b.getDueDate() != null ? b.getDueDate().format(YM) : LocalDate.now().format(YM));
        assertPeriodOpen(period);

        // 幂等键:已存在指向本单的红冲行 → 拒(DB 唯一约束兜底,此处先友好提示)
        boolean existRev = !rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getReversesId, id)).isEmpty();
        if (existRev) {
            throw new BizException(400, "该单已被红冲(幂等拒),不可重复红冲: " + b.getBillNo());
        }

        List<String> impact = new ArrayList<>();
        // 生成负额红冲行(reverses_id=原单·唯一)
        RentBill rev = new RentBill();
        rev.setBillNo(b.getBillNo() + "-R");
        rev.setContractId(b.getContractId());
        rev.setPeriodNo(b.getPeriodNo());
        rev.setDueDate(b.getDueDate());
        rev.setAmount(b.getAmount().negate());
        rev.setReceivedAmount(b.getReceivedAmount() != null ? b.getReceivedAmount().negate() : BigDecimal.ZERO);
        rev.setStatus("红冲");
        rev.setBillKind("红冲");
        rev.setReversesId(id);
        rev.setAccountPeriod(period);
        rev.setOperatorId(currentUserId());
        rev.setRemark("红冲: " + reason);
        rentBillMapper.insert(rev);   // 唯一约束在此兜底(并发重复红冲抛 DuplicateKey → 事务回滚)
        impact.add("生成红冲行 " + rev.getBillNo() + " 金额 " + rev.getAmount());

        // 原单转红冲
        b.setStatus("红冲");
        b.setRemark(appendRemark(b.getRemark(), "已红冲: " + reason));
        rentBillMapper.updateById(b);
        impact.add("原单 " + b.getBillNo() + " 状态 → 红冲");

        // 回退租金计划:已生成单 → 未到期,清 rent_bill_id(正常单红冲才回退;罚息/退款单无计划)
        if ("正常".equals(b.getBillKind())) {
            RentSchedule s = rentScheduleMapper.selectOne(new LambdaQueryWrapper<RentSchedule>()
                    .eq(RentSchedule::getContractId, b.getContractId())
                    .eq(RentSchedule::getPeriodNo, b.getPeriodNo())
                    .last("limit 1"));
            if (s != null) {
                s.setPlanStatus("未到期");
                s.setRentBillId(null);
                // updateById 跳过 null;用 mapper.update 显式清 rent_bill_id
                rentScheduleMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RentSchedule>()
                        .eq(RentSchedule::getId, s.getId())
                        .set(RentSchedule::getPlanStatus, "未到期")
                        .set(RentSchedule::getRentBillId, null));
                impact.add("租金计划 期" + s.getPeriodNo() + " 回退未到期 + 清收租单关联");
            }
        }
        // M3-01:联动红冲收入凭证(税务账+经营账·ledger_book 同步写负额冲销·营收红线回退)
        int revVouchers = voucherService.reverseBySource("rent_bill", id, "收租单红冲联动: " + reason);
        if (revVouchers > 0) {
            impact.add("联动红冲收入凭证 " + revVouchers + " 张(税务账+经营账·ledger_book 同步冲销·营收红线回退)");
        } else if (b.getVoucherId() != null) {
            impact.add("凭证 #" + b.getVoucherId() + " 已红冲,无需重复冲销");
        } else {
            impact.add("原单无凭证,无需冲销凭证");
        }

        auditLogService.record("收租红冲", "rent_bill", id, AuditLogService.EXECUTED,
                "红冲 " + b.getBillNo() + " 期=" + period + " · " + reason);
        log.info("[红冲] {} 期 {} by {} 影响 {} 项", b.getBillNo(), period, currentUserId(), impact.size());

        BillDtos.ReverseImpact ri = new BillDtos.ReverseImpact();
        ri.setOriginalBillId(id);
        ri.setReversalBillId(rev.getId());
        ri.setOriginalAmount(b.getAmount());
        ri.setReversalAmount(rev.getAmount());
        ri.setAccountPeriod(period);
        ri.setItems(impact);
        return ri;
    }

    // ============ 退款(退款单·负额) ============

    /** 退款:对已核销单退回款项(生成负额退款单,ref_bill_id 追溯原单)。受锁账守卫。 */
    @Transactional
    public BillDtos.BillItem refund(Long id, BillDtos.RefundRequest req) {
        RentBill b = load(id);
        if (!"已核销".equals(b.getStatus())) {
            throw new BizException(400, "仅已核销单可退款,当前: " + b.getStatus());
        }
        BigDecimal amt = req != null && req.getAmount() != null ? req.getAmount()
                : (b.getReceivedAmount() != null ? b.getReceivedAmount() : b.getAmount());
        if (amt.signum() <= 0) {
            throw new BizException(400, "退款金额须 > 0");
        }
        if (amt.compareTo(b.getReceivedAmount() != null ? b.getReceivedAmount() : b.getAmount()) > 0) {
            throw new BizException(400, "退款金额不可超过已收额");
        }
        String period = LocalDate.now().format(YM);
        assertPeriodOpen(period);
        String reason = req != null && req.getReason() != null ? req.getReason() : "退款";

        RentBill rf = new RentBill();
        rf.setBillNo(b.getBillNo() + "-RF");
        rf.setContractId(b.getContractId());
        rf.setPeriodNo(b.getPeriodNo());
        rf.setDueDate(b.getDueDate());
        rf.setAmount(amt.negate());
        rf.setReceivedAmount(amt.negate());
        rf.setStatus("已核销");   // 退款即时出账
        rf.setBillKind("退款");
        rf.setRefBillId(id);
        rf.setMatchedAt(LocalDateTime.now());
        rf.setAccountPeriod(period);
        rf.setOperatorId(currentUserId());
        rf.setRemark("退款: " + reason);
        rentBillMapper.insert(rf);

        auditLogService.record("收租退款", "rent_bill", id, AuditLogService.EXECUTED,
                "退款 " + amt + " 追溯 " + b.getBillNo() + " · " + reason);
        log.info("[退款] 原单 {} 退 {} 期 {} by {}", b.getBillNo(), amt, period, currentUserId());
        return toItem(rf);
    }

    // ============ M3-01 回填历史已核销单收入凭证(消稽核 matchedNoVoucher) ============

    /**
     * 为历史"已核销缺凭证"单补生成收入凭证(税务账+经营账)+ 回填 voucher_id。
     * M2 遗留的已核销单(voucher_id=NULL)一次性入账,钱该动没动稽核 matchedNoVoucher → 0。幂等(已过账跳过)。
     */
    @Transactional
    public VoucherDtos.BackfillResult backfillRentIncomeVouchers() {
        List<RentBill> matched = rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getStatus, "已核销")
                .isNull(RentBill::getVoucherId));
        VoucherDtos.BackfillResult r = new VoucherDtos.BackfillResult();
        List<String> details = new ArrayList<>();
        int posted = 0, voucherCount = 0;
        for (RentBill b : matched) {
            LocalDate bizDate = b.getAccountPeriod() != null
                    ? LocalDate.parse(b.getAccountPeriod() + "-01") : LocalDate.now();
            Long taxId = voucherService.postRentIncome(b.getId(), b.getAmount(), bizDate,
                    contractNo(b.getContractId()), b.getPeriodNo() != null ? b.getPeriodNo() : 0);
            b.setVoucherId(taxId);
            rentBillMapper.updateById(b);
            posted++;
            voucherCount += 2;   // 税务账 + 经营账
            details.add(b.getBillNo() + "(" + b.getBillKind() + ") → tax凭证#" + taxId + " +ops");
        }
        r.setScanned(matched.size());
        r.setPosted(posted);
        r.setVoucherCount(voucherCount);
        r.setDetails(details);
        log.info("[凭证回填] 已核销缺凭证 {} 单入账,生成凭证 {} 张", posted, voucherCount);
        return r;
    }

    // ============ M2-04 钱该动没动稽核 ============

    public BillDtos.CashCheck cashCheck() {
        LocalDate today = LocalDate.now();
        BillDtos.CashCheck cc = new BillDtos.CashCheck();

        // ① 到期未生成收租单:生效合同的计划行 due<=today 且未生成单
        List<String> notGen = new ArrayList<>();
        List<RentSchedule> overdueSchedules = rentScheduleMapper.selectList(new LambdaQueryWrapper<RentSchedule>()
                .le(RentSchedule::getDueDate, today)
                .ne(RentSchedule::getPlanStatus, "已生成单"));
        for (RentSchedule s : overdueSchedules) {
            Contract c = contractMapper.selectById(s.getContractId());
            if (c != null && "生效".equals(c.getStatus())) {
                notGen.add(c.getNo() + " 期" + s.getPeriodNo() + " 到期 " + s.getDueDate() + " 未生成收租单");
            }
        }

        // ② 已到账未核销:待收/逾期 但 received_amount>0(款到了没销账)
        List<String> notMatched = new ArrayList<>();
        List<RentBill> received = rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .in(RentBill::getStatus, "待收", "逾期")
                .gt(RentBill::getReceivedAmount, BigDecimal.ZERO));
        for (RentBill b : received) {
            notMatched.add(b.getBillNo() + " 已收 " + b.getReceivedAmount() + " 但状态仍 " + b.getStatus());
        }

        // ③ 已核销缺凭证:已核销 且 voucher_id 为空(M2 恒亮·待 M3 凭证接入)
        List<String> noVoucher = new ArrayList<>();
        List<RentBill> matched = rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getStatus, "已核销")
                .isNull(RentBill::getVoucherId));
        for (RentBill b : matched) {
            noVoucher.add(b.getBillNo() + " 已核销 " + b.getAccountPeriod() + " 缺收入凭证(M3 接入)");
        }

        cc.setBillNotGenerated(notGen.size());
        cc.setReceivedNotMatched(notMatched.size());
        cc.setMatchedNoVoucher(noVoucher.size());
        cc.setBillNotGeneratedDetail(notGen);
        cc.setReceivedNotMatchedDetail(notMatched);
        cc.setMatchedNoVoucherDetail(noVoucher);
        cc.setAllClear(notGen.isEmpty() && notMatched.isEmpty() && noVoucher.isEmpty());
        return cc;
    }

    // ============ 工具 ============

    /** 锁账守卫:period(YYYY-MM) 在 BOOK 账套 is_locked=1 → 拒写(只走上期调整);无期间行=开放。 */
    private void assertPeriodOpen(String period) {
        AccountingPeriod ap = accountingPeriodMapper.selectOne(new LambdaQueryWrapper<AccountingPeriod>()
                .eq(AccountingPeriod::getPeriod, period)
                .eq(AccountingPeriod::getBook, BOOK)
                .last("limit 1"));
        if (ap != null && Integer.valueOf(1).equals(ap.getIsLocked())) {
            throw new BizException(400, "会计期 " + period + "(" + BOOK + ")已锁账,禁止写入;请走上期调整(P0-F 锁账守卫)");
        }
    }

    private int leadDays() {
        BigDecimal v = safeValue("rent_bill_gen_lead_days", "");
        return v != null && v.intValue() >= 0 ? v.intValue() : 3;
    }

    private BillDtos.BillItem toItem(RentBill b) {
        boolean seeAmount = true; // 收款额财务/老板/业务均可见(成本类才打码);收租单金额=对客应收,非成本
        BillDtos.BillItem it = new BillDtos.BillItem();
        it.setId(b.getId());
        it.setBillNo(b.getBillNo());
        it.setContractId(b.getContractId());
        it.setContractNo(contractNo(b.getContractId()));
        it.setCustomerName(customerNameOfContract(b.getContractId()));
        it.setPeriodNo(b.getPeriodNo());
        it.setDueDate(b.getDueDate());
        it.setAmount(b.getAmount());
        it.setReceivedAmount(b.getReceivedAmount());
        it.setStatus(b.getStatus());
        it.setBillKind(b.getBillKind());
        it.setMatchedAt(b.getMatchedAt());
        it.setAccountPeriod(b.getAccountPeriod());
        it.setReversesId(b.getReversesId());
        it.setVoucherId(b.getVoucherId());
        it.setRemark(b.getRemark());
        boolean isOverdue = ("待收".equals(b.getStatus()) || "逾期".equals(b.getStatus()))
                && b.getDueDate() != null && b.getDueDate().isBefore(LocalDate.now());
        it.setOverdue(isOverdue);
        it.setOverdueDays(isOverdue ? (int) ChronoUnit.DAYS.between(b.getDueDate(), LocalDate.now()) : 0);
        // seeAmount 占位:成本口径打码在采购/设备模块;此处保持可见
        if (!seeAmount) {
            it.setAmount(null);
            it.setReceivedAmount(null);
        }
        return it;
    }

    /**
     * 收租单号:RB-{合同号}-P{期次};同期若已存在(如原单红冲后重新生成)追加 -G{n} 保证 bill_no 唯一。
     * 红冲→回退计划→再生成 是合法闭环,不可因单号撞车 500。
     */
    private String genBillNo(Contract c, int periodNo) {
        String base = "RB-" + c.getNo() + "-P" + String.format("%02d", periodNo);
        int existing = rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getContractId, c.getId())
                .eq(RentBill::getPeriodNo, periodNo)
                .eq(RentBill::getBillKind, "正常")).size();
        return existing == 0 ? base : base + "-G" + (existing + 1);
    }

    private RentBill load(Long id) {
        RentBill b = rentBillMapper.selectById(id);
        if (b == null || Integer.valueOf(1).equals(b.getIsDeleted())) {
            throw new BizException(404, "收租单不存在: id=" + id);
        }
        return b;
    }

    private String appendRemark(String base, String add) {
        if (add == null) {
            return base;
        }
        return (base == null || base.isEmpty()) ? add : base + " | " + add;
    }

    private Long currentUserId() {
        return UserContext.get() != null ? UserContext.get().getUserId() : null;
    }

    private BigDecimal safeValue(String ruleKey, String scopeKey) {
        try {
            return rules.getValue(ruleKey, scopeKey, LocalDate.now());
        } catch (Exception e) {
            return null;
        }
    }

    private String contractNo(Long contractId) {
        if (contractId == null) {
            return null;
        }
        Contract c = contractMapper.selectById(contractId);
        return c != null ? c.getNo() : ("合同#" + contractId);
    }

    private String customerNameOfContract(Long contractId) {
        if (contractId == null) {
            return null;
        }
        Contract c = contractMapper.selectById(contractId);
        if (c == null) {
            return null;
        }
        Customer cust = customerMapper.selectById(c.getCustomerId());
        return cust != null ? cust.getName() : ("客户#" + c.getCustomerId());
    }

    // DataScope import 占位:保留成本可见性判断接口一致性(收租额非成本,当前全可见)
    @SuppressWarnings("unused")
    private boolean seeCost() {
        return DataScope.canSeeCost(UserContext.getRole());
    }
}
