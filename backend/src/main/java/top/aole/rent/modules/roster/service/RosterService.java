package top.aole.rent.modules.roster.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.purchase.domain.PurchaseIn;
import top.aole.rent.modules.purchase.domain.PurchaseItem;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseItemMapper;
import top.aole.rent.modules.roster.domain.Commission;
import top.aole.rent.modules.roster.domain.UserRoleExt;
import top.aole.rent.modules.roster.dto.RosterDtos;
import top.aole.rent.modules.roster.mapper.CommissionMapper;
import top.aole.rent.modules.roster.mapper.UserRoleExtMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 花名册/提成服务(M5-03)。
 *
 * <p>①花名册:角色权限矩阵(复用 DataScope 口径),改动限老板 + 入 audit(M5-06)。
 * ②提成:供应链集采降本贡献 / 业务成交贡献,从管理费列支;
 * 提成率走 rule commission_rate(禁硬编码);base 溯源真实业务单据(purchase_item 降本 / 合同成交额);
 * 幂等键 uk(period,user_id,type,source_ref)避免重复计提。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RosterService {

    private final UserRoleExtMapper roleExtMapper;
    private final CommissionMapper commissionMapper;
    private final PurchaseInMapper purchaseInMapper;
    private final PurchaseItemMapper purchaseItemMapper;
    private final ContractMapper contractMapper;
    private final RuleConfigService rules;
    private final AuditLogService auditLogService;

    // ============ 花名册 ============

    public List<RosterDtos.RosterItem> roster() {
        List<UserRoleExt> all = roleExtMapper.selectList(new LambdaQueryWrapper<UserRoleExt>()
                .eq(UserRoleExt::getIsDeleted, 0).orderByAsc(UserRoleExt::getId));
        List<RosterDtos.RosterItem> out = new ArrayList<>();
        for (UserRoleExt u : all) {
            out.add(toRosterItem(u));
        }
        return out;
    }

    /** 角色权限改动(限老板·切面卡+此处入 audit)。 */
    @Transactional
    public RosterDtos.RosterItem updateRole(Long id, RosterDtos.RoleUpdateRequest req) {
        UserRoleExt u = roleExtMapper.selectById(id);
        if (u == null || Integer.valueOf(1).equals(u.getIsDeleted())) {
            throw new BizException(404, "花名册成员不存在: id=" + id);
        }
        StringBuilder chg = new StringBuilder();
        if (req.getRole() != null && !req.getRole().equals(u.getRole())) {
            chg.append("角色 ").append(u.getRole()).append("→").append(req.getRole()).append("; ");
            u.setRole(req.getRole());
        }
        if (req.getDataScope() != null) {
            u.setDataScope(req.getDataScope());
        }
        if (req.getCostVisible() != null && !req.getCostVisible().equals(Integer.valueOf(1).equals(u.getCostVisible()))) {
            chg.append("成本可见 ").append(bool(u.getCostVisible())).append("→").append(req.getCostVisible()).append("; ");
            u.setCostVisible(req.getCostVisible() ? 1 : 0);
        }
        if (req.getOwnerScoped() != null) {
            u.setOwnerScoped(req.getOwnerScoped() ? 1 : 0);
        }
        if (req.getProjectId() != null) {
            u.setProjectId(req.getProjectId());
        }
        if (req.getActive() != null) {
            u.setActive(req.getActive() ? 1 : 0);
        }
        if (req.getRemark() != null) {
            u.setRemark(req.getRemark());
        }
        roleExtMapper.updateById(u);
        auditLogService.record("角色权限变更", "user_role_ext", id, AuditLogService.EXECUTED,
                "成员 " + u.getUserName() + " · " + (chg.length() == 0 ? "字段调整" : chg.toString()));
        log.info("花名册角色权限变更: user={}, changes={}", u.getUserName(), chg);
        return toRosterItem(u);
    }

    // ============ 提成计提 ============

    @Transactional
    public RosterDtos.ComputeResult computeCommission(String period) {
        YearMonth ym = YearMonth.parse(period);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        LocalDate ruleDate = to;

        BigDecimal costCutRate = rules.getValue("commission_rate", "集采降本", ruleDate);
        BigDecimal dealRate = rules.getValue("commission_rate", "成交贡献", ruleDate);

        RosterDtos.ComputeResult r = new RosterDtos.ComputeResult();
        r.setPeriod(period);

        // 集采降本 → 供应链
        UserRoleExt scm = firstActiveByRole("供应链");
        if (scm != null) {
            List<PurchaseIn> ins = purchaseInMapper.selectList(new LambdaQueryWrapper<PurchaseIn>()
                    .eq(PurchaseIn::getIsDeleted, 0)
                    .eq(PurchaseIn::getStatus, "已入库")
                    .ge(PurchaseIn::getReceiveDate, from)
                    .le(PurchaseIn::getReceiveDate, to));
            for (PurchaseIn p : ins) {
                BigDecimal cut = costCutOfPurchase(p.getId());
                if (cut.signum() <= 0) {
                    continue;
                }
                String ref = "purchase:" + p.getId();
                if (exists(period, scm.getUserId(), "集采降本", ref)) {
                    r.setSkipped(r.getSkipped() + 1);
                    continue;
                }
                BigDecimal amt = cut.multiply(costCutRate).setScale(2, RoundingMode.HALF_UP);
                insertCommission(period, scm, "集采降本", cut, costCutRate, amt, ref,
                        "采购单 " + p.getNo() + " 集采降本");
                r.setCostCutRows(r.getCostCutRows() + 1);
                r.setTotalCommission(r.getTotalCommission().add(amt));
            }
        } else {
            log.warn("[提成计提] 无在职供应链成员,跳过集采降本计提");
        }

        // 成交贡献 → 业务
        UserRoleExt bd = firstActiveByRole("业务");
        if (bd != null) {
            List<Contract> contracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                    .eq(Contract::getIsDeleted, 0)
                    .ne(Contract::getStatus, "已作废")
                    .ge(Contract::getSignDate, from)
                    .le(Contract::getSignDate, to));
            for (Contract c : contracts) {
                BigDecimal deal = dealAmountOfContract(c);
                if (deal.signum() <= 0) {
                    continue;
                }
                String ref = "contract:" + c.getId();
                if (exists(period, bd.getUserId(), "成交贡献", ref)) {
                    r.setSkipped(r.getSkipped() + 1);
                    continue;
                }
                BigDecimal amt = deal.multiply(dealRate).setScale(2, RoundingMode.HALF_UP);
                insertCommission(period, bd, "成交贡献", deal, dealRate, amt, ref,
                        "合同 " + c.getNo() + " 成交额");
                r.setDealRows(r.getDealRows() + 1);
                r.setTotalCommission(r.getTotalCommission().add(amt));
            }
        } else {
            log.warn("[提成计提] 无在职业务成员,跳过成交贡献计提");
        }

        log.info("[提成计提] period={}, 降本 {} 行 · 成交 {} 行 · 跳过 {} · 合计 ¥{}",
                period, r.getCostCutRows(), r.getDealRows(), r.getSkipped(), r.getTotalCommission());
        return r;
    }

    // ============ 提成汇总(拆解可溯) ============

    public List<RosterDtos.CommissionSummary> commissionSummary(String period, Long userId) {
        List<Commission> rows = commissionMapper.selectList(new LambdaQueryWrapper<Commission>()
                .eq(Commission::getPeriod, period)
                .eq(userId != null, Commission::getUserId, userId)
                .orderByAsc(Commission::getUserId).orderByAsc(Commission::getId));
        Map<Long, RosterDtos.CommissionSummary> byUser = new LinkedHashMap<>();
        for (Commission c : rows) {
            RosterDtos.CommissionSummary s = byUser.computeIfAbsent(c.getUserId(), k -> {
                RosterDtos.CommissionSummary ns = new RosterDtos.CommissionSummary();
                ns.setPeriod(period);
                ns.setUserId(c.getUserId());
                ns.setUserName(c.getUserName());
                ns.setRole(c.getRole());
                ns.setLines(new ArrayList<>());
                return ns;
            });
            s.getLines().add(toLine(c));
            if ("集采降本".equals(c.getType())) {
                s.setCostCutOrders(s.getCostCutOrders() + 1);
                s.setCostCutBase(s.getCostCutBase().add(nz(c.getBaseAmount())));
                s.setCostCutCommission(s.getCostCutCommission().add(nz(c.getCommissionAmount())));
            } else if ("成交贡献".equals(c.getType())) {
                s.setDealOrders(s.getDealOrders() + 1);
                s.setDealBase(s.getDealBase().add(nz(c.getBaseAmount())));
                s.setDealCommission(s.getDealCommission().add(nz(c.getCommissionAmount())));
            }
            s.setTotalCommission(s.getTotalCommission().add(nz(c.getCommissionAmount())));
        }
        return new ArrayList<>(byUser.values());
    }

    // ============ 工具 ============

    private BigDecimal costCutOfPurchase(Long purchaseInId) {
        List<PurchaseItem> items = purchaseItemMapper.selectList(new LambdaQueryWrapper<PurchaseItem>()
                .eq(PurchaseItem::getPurchaseInId, purchaseInId));
        BigDecimal cut = BigDecimal.ZERO;
        for (PurchaseItem it : items) {
            if (it.getMarketPrice() != null && it.getPurchasePrice() != null) {
                BigDecimal d = it.getMarketPrice().subtract(it.getPurchasePrice());
                if (d.signum() > 0) {
                    cut = cut.add(d);
                }
            }
        }
        return cut.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal dealAmountOfContract(Contract c) {
        if (c.getMonthRent() == null || c.getTermMonths() == null) {
            return BigDecimal.ZERO;
        }
        return c.getMonthRent().multiply(BigDecimal.valueOf(c.getTermMonths())).setScale(2, RoundingMode.HALF_UP);
    }

    private void insertCommission(String period, UserRoleExt u, String type, BigDecimal base,
                                  BigDecimal rate, BigDecimal amt, String ref, String remark) {
        Commission c = new Commission();
        c.setPeriod(period);
        c.setUserId(u.getUserId());
        c.setUserName(u.getUserName());
        c.setRole(u.getRole());
        c.setType(type);
        c.setBaseAmount(base);
        c.setRate(rate);
        c.setCommissionAmount(amt);
        c.setFundedFrom("管理费");
        c.setSourceRef(ref);
        c.setOrderCount(1);
        c.setRemark(remark);
        commissionMapper.insert(c);
    }

    private boolean exists(String period, Long userId, String type, String ref) {
        return commissionMapper.selectCount(new LambdaQueryWrapper<Commission>()
                .eq(Commission::getPeriod, period)
                .eq(Commission::getUserId, userId)
                .eq(Commission::getType, type)
                .eq(Commission::getSourceRef, ref)) > 0;
    }

    private UserRoleExt firstActiveByRole(String role) {
        return roleExtMapper.selectList(new LambdaQueryWrapper<UserRoleExt>()
                        .eq(UserRoleExt::getIsDeleted, 0)
                        .eq(UserRoleExt::getActive, 1)
                        .eq(UserRoleExt::getRole, role)
                        .orderByAsc(UserRoleExt::getId)).stream()
                .findFirst().orElse(null);
    }

    private RosterDtos.RosterItem toRosterItem(UserRoleExt u) {
        RosterDtos.RosterItem it = new RosterDtos.RosterItem();
        it.setId(u.getId());
        it.setUserId(u.getUserId());
        it.setUserName(u.getUserName());
        it.setRole(u.getRole());
        it.setDataScope(u.getDataScope());
        it.setCostVisible(Integer.valueOf(1).equals(u.getCostVisible()));
        it.setOwnerScoped(Integer.valueOf(1).equals(u.getOwnerScoped()));
        it.setProjectId(u.getProjectId());
        it.setActive(Integer.valueOf(1).equals(u.getActive()));
        it.setRemark(u.getRemark());
        return it;
    }

    private RosterDtos.CommissionLine toLine(Commission c) {
        RosterDtos.CommissionLine l = new RosterDtos.CommissionLine();
        l.setId(c.getId());
        l.setPeriod(c.getPeriod());
        l.setUserId(c.getUserId());
        l.setUserName(c.getUserName());
        l.setRole(c.getRole());
        l.setType(c.getType());
        l.setBaseAmount(c.getBaseAmount());
        l.setRate(c.getRate());
        l.setCommissionAmount(c.getCommissionAmount());
        l.setFundedFrom(c.getFundedFrom());
        l.setSourceRef(c.getSourceRef());
        l.setOrderCount(c.getOrderCount());
        l.setCreateTime(c.getCreateTime());
        return l;
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String bool(Integer i) {
        return Integer.valueOf(1).equals(i) ? "true" : "false";
    }
}
