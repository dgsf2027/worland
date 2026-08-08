package top.aole.rent.modules.billing.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.billing.domain.OverdueCase;
import top.aole.rent.modules.billing.domain.RentBill;
import top.aole.rent.modules.billing.domain.RepossessOrder;
import top.aole.rent.modules.billing.dto.BillDtos;
import top.aole.rent.modules.billing.mapper.OverdueCaseMapper;
import top.aole.rent.modules.billing.mapper.RentBillMapper;
import top.aole.rent.modules.billing.mapper.RepossessOrderMapper;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.domain.ContractAsset;
import top.aole.rent.modules.contract.mapper.ContractAssetMapper;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 逾期服务(M2-05/06)。逾期检测开案(cron) / 三步走(延期→罚息→锁机→收回) / 还款恢复 / 收回联动设备。
 *
 * <p><b>流程8(系统方案)</b>:内外一视同仁,物权在我方。每案必"裁决人 owner + 期限 deadline"。
 * 延期(宽限)→罚息(计罚息单·走 rule_config 罚息率)→锁机(远程锁·物权主张)→收回(生成收回单·设备转收回待处置)。
 * <p><b>还款恢复</b>:核销逾期收租单即关闭案(复用 {@link RentBillService#match});收回=结案生成收回单。
 * <p>幂等:{@code uk_overdue_bill} 唯一约束保证一张逾期单只开一个案(并发/重扫不重复开案)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OverdueService {

    private final OverdueCaseMapper overdueCaseMapper;
    private final RentBillMapper rentBillMapper;
    private final RepossessOrderMapper repossessOrderMapper;
    private final ContractMapper contractMapper;
    private final ContractAssetMapper contractAssetMapper;
    private final CustomerMapper customerMapper;
    private final AssetService assetService;
    private final RentBillService rentBillService;
    private final RuleConfigService rules;
    private final AuditLogService auditLogService;

    private static final String DEFAULT_OWNER = "财务";

    // ============ M2-05 逾期检测开案(cron 执行体) ============

    /**
     * 扫过期未付收租单(待收 且 due+grace < today)→ 标逾期 + 自动开案(step=延期,裁决人=财务,期限=7天)。
     * 幂等:已有案的单跳过;并发靠 uk_overdue_bill 唯一约束兜底(DuplicateKey 吞掉)。
     */
    @Transactional
    public BillDtos.OverdueScanResult runOverdueScan() {
        int grace = graceDays();
        LocalDate today = LocalDate.now();
        // 到期日 + grace < today 的待收单
        List<RentBill> candidates = rentBillMapper.selectList(new LambdaQueryWrapper<RentBill>()
                .eq(RentBill::getStatus, "待收")
                .eq(RentBill::getBillKind, "正常")
                .isNotNull(RentBill::getDueDate));
        BillDtos.OverdueScanResult r = new BillDtos.OverdueScanResult();
        List<String> cases = new ArrayList<>();
        int marked = 0;
        for (RentBill b : candidates) {
            if (!b.getDueDate().plusDays(grace).isBefore(today)) {
                continue;   // 未过宽限
            }
            // 已有案 → 跳过(幂等)
            OverdueCase exist = overdueCaseMapper.selectOne(new LambdaQueryWrapper<OverdueCase>()
                    .eq(OverdueCase::getRentBillId, b.getId()).last("limit 1"));
            if (exist != null) {
                continue;
            }
            // 标逾期
            b.setStatus("逾期");
            b.setRemark(append(b.getRemark(), "逾期 " + ChronoUnit.DAYS.between(b.getDueDate(), today) + " 天自动开案"));
            rentBillMapper.updateById(b);
            marked++;
            // 开案
            OverdueCase oc = new OverdueCase();
            oc.setRentBillId(b.getId());
            oc.setContractId(b.getContractId());
            oc.setStep("延期");
            oc.setStatus("开启");
            oc.setPenaltyAmount(BigDecimal.ZERO);
            oc.setNextAction("催收/协商延期,逾期加剧转罚息");
            oc.setDeadline(today.plusDays(7));
            oc.setOwner(DEFAULT_OWNER);
            oc.setOpenedAt(LocalDateTime.now());
            oc.setRemark("cron 逾期检测自动开案");
            try {
                overdueCaseMapper.insert(oc);
                cases.add("案#" + oc.getId() + " 单" + b.getBillNo());
                auditLogService.record("逾期开案", "rent_bill", b.getId(), AuditLogService.EXECUTED,
                        "自动开案 单=" + b.getBillNo() + " 逾期" + ChronoUnit.DAYS.between(b.getDueDate(), today) + "天");
            } catch (DuplicateKeyException dup) {
                // 并发下已被开案,幂等吞掉
                log.warn("[逾期开案] 单 {} 已有案(并发幂等)", b.getBillNo());
            }
        }
        r.setOpened(cases.size());
        r.setMarked(marked);
        r.setCases(cases);
        if (!cases.isEmpty()) {
            log.info("[逾期检测] 标逾期 {} 单, 开案 {} 个", marked, cases.size());
        }
        return r;
    }

    // ============ 列表 / 详情 ============

    public PageResult<BillDtos.OverdueItem> list(String status, Long contractId, int page, int size) {
        LambdaQueryWrapper<OverdueCase> qw = new LambdaQueryWrapper<OverdueCase>()
                .eq(status != null && !status.isEmpty(), OverdueCase::getStatus, status)
                .eq(contractId != null, OverdueCase::getContractId, contractId)
                .orderByDesc(OverdueCase::getId);
        List<OverdueCase> all = overdueCaseMapper.selectList(qw);
        List<BillDtos.OverdueItem> items = new ArrayList<>();
        for (OverdueCase c : all) {
            items.add(toItem(c));
        }
        long total = items.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(items.size(), from + size);
        List<BillDtos.OverdueItem> records = from >= items.size() ? new ArrayList<>() : items.subList(from, to);
        return new PageResult<>(total, page, size, records);
    }

    public BillDtos.OverdueItem detail(Long id) {
        return toItem(load(id));
    }

    // ============ M2-05 三步走处置 ============

    /** 延期:协商展期,更新期限/裁决人,step=延期。 */
    @Transactional
    public BillDtos.OverdueItem extend(Long id, BillDtos.OverdueActionRequest req) {
        OverdueCase oc = requireOpen(id);
        int days = req != null && req.getDays() != null && req.getDays() > 0 ? req.getDays() : 7;
        applyOwnerDeadline(oc, req, LocalDate.now().plusDays(days));
        oc.setStep("延期");
        oc.setNextAction("展期" + days + "天,到期未付转罚息");
        oc.setRemark(append(oc.getRemark(), "延期" + days + "天·裁决人" + oc.getOwner()
                + (req != null && req.getReason() != null ? "·" + req.getReason() : "")));
        overdueCaseMapper.updateById(oc);
        auditLogService.record("逾期延期", "overdue_case", id, AuditLogService.EXECUTED,
                "展期" + days + "天 裁决人=" + oc.getOwner());
        return toItem(oc);
    }

    /** 罚息:按 rule 罚息率×逾期额×天数 计罚息单(rent_bill kind=罚息),累计 penalty_amount,step=罚息。 */
    @Transactional
    public BillDtos.OverdueItem penalty(Long id, BillDtos.OverdueActionRequest req) {
        OverdueCase oc = requireOpen(id);
        RentBill bill = rentBillMapper.selectById(oc.getRentBillId());
        if (bill == null) {
            throw new BizException(404, "逾期收租单不存在");
        }
        BigDecimal rate = safeValue("rent_penalty_rate", "");
        if (rate == null) {
            throw new BizException(500, "罚息率未配置(rule_config rent_penalty_rate)");
        }
        int days = req != null && req.getDays() != null && req.getDays() > 0 ? req.getDays()
                : (int) Math.max(1, ChronoUnit.DAYS.between(bill.getDueDate(), LocalDate.now()));
        BigDecimal penalty = bill.getAmount().multiply(rate).multiply(BigDecimal.valueOf(days))
                .setScale(2, RoundingMode.HALF_UP);
        if (penalty.signum() <= 0) {
            throw new BizException(400, "计算罚息为 0,无需计罚息单");
        }
        // 罚息单(待收收租单·kind=罚息)
        Contract c = contractMapper.selectById(oc.getContractId());
        RentBill pb = new RentBill();
        pb.setBillNo("PN-" + (c != null ? c.getNo() : oc.getContractId()) + "-" + System.currentTimeMillis() % 100000);
        pb.setContractId(oc.getContractId());
        pb.setPeriodNo(bill.getPeriodNo());
        pb.setDueDate(LocalDate.now());
        pb.setAmount(penalty);
        pb.setReceivedAmount(BigDecimal.ZERO);
        pb.setStatus("待收");
        pb.setBillKind("罚息");
        pb.setOperatorId(currentUserId());
        pb.setRemark("逾期罚息 " + rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()
                + "%/天 ×" + days + "天 (原单 " + bill.getBillNo() + ")");
        rentBillMapper.insert(pb);

        oc.setPenaltyAmount((oc.getPenaltyAmount() != null ? oc.getPenaltyAmount() : BigDecimal.ZERO).add(penalty));
        oc.setStep("罚息");
        oc.setNextAction("罚息已计,仍不付转锁机/收回");
        applyOwnerDeadline(oc, req, oc.getDeadline() != null ? oc.getDeadline() : LocalDate.now().plusDays(7));
        oc.setRemark(append(oc.getRemark(), "计罚息单 " + pb.getBillNo() + " 金额" + penalty));
        overdueCaseMapper.updateById(oc);
        auditLogService.record("逾期罚息", "overdue_case", id, AuditLogService.EXECUTED,
                "计罚息 " + penalty + " 单=" + pb.getBillNo());
        log.info("[逾期罚息] 案#{} 计罚息 {} 单 {}", id, penalty, pb.getBillNo());
        return toItem(oc);
    }

    /** 锁机:远程锁机(物权主张·内外一视同仁),step=锁机。M2 记步+留痕,设备物理锁由 IoT 侧执行(钩子)。 */
    @Transactional
    public BillDtos.OverdueItem lock(Long id, BillDtos.OverdueActionRequest req) {
        OverdueCase oc = requireOpen(id);
        applyOwnerDeadline(oc, req, oc.getDeadline() != null ? oc.getDeadline() : LocalDate.now().plusDays(3));
        oc.setStep("锁机");
        oc.setNextAction("已锁机,限期未付启动收回");
        oc.setRemark(append(oc.getRemark(), "锁机(物权主张·裁决人" + oc.getOwner() + ")"
                + (req != null && req.getReason() != null ? "·" + req.getReason() : "")));
        overdueCaseMapper.updateById(oc);
        auditLogService.record("逾期锁机", "overdue_case", id, AuditLogService.EXECUTED,
                "锁机 裁决人=" + oc.getOwner());
        log.info("[逾期锁机] 案#{} 裁决人 {}", id, oc.getOwner());
        return toItem(oc);
    }

    /** 收回:生成收回单 + 合同挂载设备转收回待处置(接 M1 状态机),案结案(step=收回,status=关闭)。 */
    @Transactional
    public BillDtos.OverdueItem repossess(Long id, BillDtos.OverdueActionRequest req) {
        OverdueCase oc = requireOpen(id);
        Contract c = contractMapper.selectById(oc.getContractId());
        if (c == null) {
            throw new BizException(404, "合同不存在: id=" + oc.getContractId());
        }
        String reason = req != null && req.getReason() != null ? req.getReason() : "逾期收回";

        RepossessOrder ro = new RepossessOrder();
        ro.setNo("REP-" + c.getNo() + "-" + System.currentTimeMillis() % 100000);
        ro.setContractId(oc.getContractId());
        ro.setOverdueCaseId(id);
        ro.setDisposalStatus("待处置");
        ro.setOperatorId(currentUserId());
        ro.setReason(reason);
        ro.setBizTime(LocalDateTime.now());
        repossessOrderMapper.insert(ro);

        // 合同挂载设备 → 收回待处置(状态机 owner=AssetService)
        List<ContractAsset> links = contractAssetMapper.selectList(new LambdaQueryWrapper<ContractAsset>()
                .eq(ContractAsset::getContractId, oc.getContractId()));
        int count = 0;
        for (ContractAsset ca : links) {
            assetService.repossess(ca.getAssetId(), oc.getContractId(), ro.getId());
            count++;
        }
        ro.setAssetCount(count);
        repossessOrderMapper.updateById(ro);

        applyOwnerDeadline(oc, req, oc.getDeadline());
        oc.setStep("收回");
        oc.setStatus("关闭");
        oc.setClosedAt(LocalDateTime.now());
        oc.setNextAction("资产收回待处置(再投放/二手/报废 M4)");
        oc.setRemark(append(oc.getRemark(), "收回结案·收回单" + ro.getNo() + "·设备" + count + "台转收回待处置"));
        overdueCaseMapper.updateById(oc);
        auditLogService.record("逾期收回", "overdue_case", id, AuditLogService.EXECUTED,
                "收回单 " + ro.getNo() + " 设备" + count + "台 · " + reason);
        log.info("[逾期收回] 案#{} 收回单 {} 设备 {}台", id, ro.getNo(), count);
        return toItem(oc);
    }

    // ============ M2-06 还款恢复 ============

    /** 还款恢复:核销该案逾期收租单(复用 RentBillService.match,内含关案逻辑),案关闭。 */
    @Transactional
    public BillDtos.RepaymentResult repay(Long id, BillDtos.MatchRequest req) {
        OverdueCase oc = load(id);
        if ("关闭".equals(oc.getStatus())) {
            throw new BizException(400, "逾期案已关闭");
        }
        // 核销原逾期单(match 内部会关闭本案 + 记账)
        rentBillService.match(oc.getRentBillId(), req);
        OverdueCase after = load(id);
        BillDtos.RepaymentResult rr = new BillDtos.RepaymentResult();
        rr.setCaseId(id);
        rr.setCaseStatus(after.getStatus());
        rr.setMatchedBillId(oc.getRentBillId());
        rr.setAssetsRestored(!"收回".equals(after.getStep()));  // 未走到收回=承租关系存续
        log.info("[还款恢复] 案#{} → {}", id, after.getStatus());
        return rr;
    }

    // ============ 工具 ============

    private void applyOwnerDeadline(OverdueCase oc, BillDtos.OverdueActionRequest req, LocalDate fallbackDeadline) {
        if (req != null && req.getOwner() != null && !req.getOwner().trim().isEmpty()) {
            oc.setOwner(req.getOwner().trim());
        } else if (oc.getOwner() == null) {
            oc.setOwner(DEFAULT_OWNER);
        }
        LocalDate dl = req != null && req.getDeadline() != null ? req.getDeadline() : fallbackDeadline;
        oc.setDeadline(dl);
    }

    private BillDtos.OverdueItem toItem(OverdueCase c) {
        BillDtos.OverdueItem it = new BillDtos.OverdueItem();
        it.setId(c.getId());
        it.setRentBillId(c.getRentBillId());
        RentBill b = c.getRentBillId() != null ? rentBillMapper.selectById(c.getRentBillId()) : null;
        it.setBillNo(b != null ? b.getBillNo() : null);
        it.setContractId(c.getContractId());
        Contract ct = contractMapper.selectById(c.getContractId());
        it.setContractNo(ct != null ? ct.getNo() : null);
        it.setCustomerName(ct != null ? customerName(ct.getCustomerId()) : null);
        it.setStep(c.getStep());
        it.setStatus(c.getStatus());
        it.setPenaltyAmount(c.getPenaltyAmount());
        it.setNextAction(c.getNextAction());
        it.setDeadline(c.getDeadline());
        it.setOwner(c.getOwner());
        it.setOpenedAt(c.getOpenedAt());
        it.setClosedAt(c.getClosedAt());
        it.setRemark(c.getRemark());
        return it;
    }

    private OverdueCase requireOpen(Long id) {
        OverdueCase oc = load(id);
        if (!"开启".equals(oc.getStatus())) {
            throw new BizException(400, "逾期案已" + oc.getStatus() + ",不可处置");
        }
        return oc;
    }

    private OverdueCase load(Long id) {
        OverdueCase oc = overdueCaseMapper.selectById(id);
        if (oc == null || Integer.valueOf(1).equals(oc.getIsDeleted())) {
            throw new BizException(404, "逾期案不存在: id=" + id);
        }
        return oc;
    }

    private int graceDays() {
        BigDecimal v = safeValue("overdue_grace_days", "");
        return v != null && v.intValue() >= 0 ? v.intValue() : 0;
    }

    private String append(String base, String add) {
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

    private String customerName(Long customerId) {
        if (customerId == null) {
            return null;
        }
        Customer c = customerMapper.selectById(customerId);
        return c != null ? c.getName() : ("客户#" + customerId);
    }
}
