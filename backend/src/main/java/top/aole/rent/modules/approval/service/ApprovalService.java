package top.aole.rent.modules.approval.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.approval.domain.Approval;
import top.aole.rent.modules.approval.dto.ApprovalDtos;
import top.aole.rent.modules.approval.mapper.ApprovalMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 审批服务(M5-02)。投放审批闸:本金回报 ≥ 目标方可发起;金额 ≤ 自主额度(300万)→自主,超额→协商。
 *
 * <p>裁决(通过/驳回)由 {@link top.aole.rent.common.auth.RequireRole} 统一切面卡「老板」(P0-D);
 * 达标线/自主额度均走 {@link RuleConfigService}(禁硬编码 §4.24):
 * target_rate ← 请求或 rule target_irr[其他];self_limit ← rule approval_self_limit。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final ApprovalMapper approvalMapper;
    private final RuleConfigService rules;
    private final AuditLogService auditLogService;

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    // ============ 发起 ============

    @Transactional
    public Long initiate(ApprovalDtos.InitiateRequest req) {
        BigDecimal target = req.getTargetRate() != null ? req.getTargetRate() : defaultTarget();
        // 达标闸:本金回报 < 目标 → 不允许进审批
        if (req.getPrincipalReturnRate().compareTo(target) < 0) {
            throw new BizException(400, "本金回报率 " + pct(req.getPrincipalReturnRate())
                    + " < 目标 " + pct(target) + ",不允许发起投放审批(须先达标)");
        }
        BigDecimal selfLimit = selfLimit();
        boolean self = req.getAmount().compareTo(selfLimit) <= 0;

        CurrentUser u = UserContext.get();
        Approval a = new Approval();
        a.setNo(genNo());
        a.setType("投放审批");
        a.setBizType(req.getBizType());
        a.setBizId(req.getBizId());
        a.setSubject(req.getSubject());
        a.setAmount(req.getAmount());
        a.setPrincipalReturnRate(req.getPrincipalReturnRate());
        a.setTargetRate(target);
        a.setSelfLimit(selfLimit);
        a.setDecisionMode(self ? "自主" : "协商");
        a.setStatus("待审批");
        a.setRemark(req.getRemark());
        if (u != null) {
            a.setApplicantId(u.getUserId());
            a.setApplicantName(u.getUserName());
        }
        approvalMapper.insert(a);
        log.info("发起投放审批: no={}, 金额={}, 本金回报={}≥目标{}, 裁决={}",
                a.getNo(), a.getAmount(), pct(a.getPrincipalReturnRate()), pct(target), a.getDecisionMode());
        return a.getId();
    }

    // ============ 裁决(切面卡老板) ============

    @Transactional
    public ApprovalDtos.ApprovalItem approve(Long id, ApprovalDtos.DecisionRequest req) {
        Approval a = load(id);
        if (!"待审批".equals(a.getStatus())) {
            throw new BizException(400, "审批状态为" + a.getStatus() + ",不可重复裁决");
        }
        CurrentUser u = UserContext.get();
        a.setStatus("已通过");
        a.setApproverId(u != null ? u.getUserId() : null);
        a.setApproverName(u != null ? u.getUserName() : null);
        a.setApprovedAt(LocalDateTime.now());
        String reason = req != null && req.getReason() != null ? req.getReason()
                : ("自主".equals(a.getDecisionMode()) ? "300万内自主通过" : "与合伙人协商通过");
        a.setDecisionReason(reason);
        approvalMapper.updateById(a);
        auditLogService.record("投放审批", a.getBizType() != null ? a.getBizType() : "approval", a.getId(),
                AuditLogService.EXECUTED,
                "通过 · " + a.getDecisionMode() + " · 金额" + a.getAmount() + " · 本金回报" + pct(a.getPrincipalReturnRate())
                        + " · " + reason);
        log.info("投放审批通过: no={}, 裁决={}, by={}", a.getNo(), a.getDecisionMode(),
                u != null ? u.getUserName() : "-");
        return toItem(a);
    }

    @Transactional
    public ApprovalDtos.ApprovalItem reject(Long id, ApprovalDtos.DecisionRequest req) {
        Approval a = load(id);
        if (!"待审批".equals(a.getStatus())) {
            throw new BizException(400, "审批状态为" + a.getStatus() + ",不可重复裁决");
        }
        CurrentUser u = UserContext.get();
        a.setStatus("已驳回");
        a.setApproverId(u != null ? u.getUserId() : null);
        a.setApproverName(u != null ? u.getUserName() : null);
        a.setApprovedAt(LocalDateTime.now());
        a.setDecisionReason(req != null ? req.getReason() : "驳回");
        approvalMapper.updateById(a);
        auditLogService.record("投放审批", a.getBizType() != null ? a.getBizType() : "approval", a.getId(),
                AuditLogService.EXECUTED, "驳回 · " + a.getDecisionReason());
        log.info("投放审批驳回: no={}, by={}", a.getNo(), u != null ? u.getUserName() : "-");
        return toItem(a);
    }

    // ============ 查询 ============

    public PageResult<ApprovalDtos.ApprovalItem> list(String status, String decisionMode, int page, int size) {
        LambdaQueryWrapper<Approval> qw = new LambdaQueryWrapper<Approval>()
                .eq(Approval::getIsDeleted, 0)
                .eq(status != null && !status.isEmpty(), Approval::getStatus, status)
                .eq(decisionMode != null && !decisionMode.isEmpty(), Approval::getDecisionMode, decisionMode)
                .orderByDesc(Approval::getId);
        List<Approval> all = approvalMapper.selectList(qw);
        long total = all.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(all.size(), from + size);
        List<ApprovalDtos.ApprovalItem> records = new ArrayList<>();
        if (from < all.size()) {
            for (Approval a : all.subList(from, to)) {
                records.add(toItem(a));
            }
        }
        return new PageResult<>(total, page, size, records);
    }

    public ApprovalDtos.ApprovalItem detail(Long id) {
        return toItem(load(id));
    }

    // ============ 工具 ============

    private ApprovalDtos.ApprovalItem toItem(Approval a) {
        ApprovalDtos.ApprovalItem it = new ApprovalDtos.ApprovalItem();
        it.setId(a.getId());
        it.setNo(a.getNo());
        it.setType(a.getType());
        it.setBizType(a.getBizType());
        it.setBizId(a.getBizId());
        it.setSubject(a.getSubject());
        it.setAmount(a.getAmount());
        it.setPrincipalReturnRate(a.getPrincipalReturnRate());
        it.setTargetRate(a.getTargetRate());
        it.setSelfLimit(a.getSelfLimit());
        it.setDecisionMode(a.getDecisionMode());
        it.setStatus(a.getStatus());
        it.setApplicantName(a.getApplicantName());
        it.setApproverName(a.getApproverName());
        it.setApprovedAt(a.getApprovedAt());
        it.setDecisionReason(a.getDecisionReason());
        it.setCreateTime(a.getCreateTime());
        return it;
    }

    private Approval load(Long id) {
        Approval a = approvalMapper.selectById(id);
        if (a == null || Integer.valueOf(1).equals(a.getIsDeleted())) {
            throw new BizException(404, "审批单不存在: id=" + id);
        }
        return a;
    }

    private BigDecimal selfLimit() {
        return rules.getValue("approval_self_limit", "", LocalDate.now());
    }

    private BigDecimal defaultTarget() {
        return rules.getValue("target_irr", "其他", LocalDate.now());
    }

    private String pct(BigDecimal r) {
        if (r == null) {
            return "-";
        }
        return r.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
    }

    private String genNo() {
        String base = "AP-" + LocalDate.now().format(NO_DAY) + "-" + (System.currentTimeMillis() % 100000);
        String no = base;
        int n = 1;
        while (approvalMapper.selectCount(new LambdaQueryWrapper<Approval>().eq(Approval::getNo, no)) > 0) {
            no = base + "-" + (++n);
        }
        return no;
    }
}
