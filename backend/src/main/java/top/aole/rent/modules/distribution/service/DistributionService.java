package top.aole.rent.modules.distribution.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.distribution.domain.Distribution;
import top.aole.rent.modules.distribution.domain.Investor;
import top.aole.rent.modules.distribution.dto.DistributionDtos;
import top.aole.rent.modules.distribution.mapper.DistributionMapper;
import top.aole.rent.modules.distribution.mapper.InvestorMapper;
import top.aole.rent.modules.finance.domain.LedgerBook;
import top.aole.rent.modules.finance.mapper.LedgerBookMapper;
import top.aole.rent.modules.finance.service.VoucherService;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.mapper.PayableMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 结账分配服务(M3-03/04)。每月 5 号:提取前净利 → 管理费阶梯 → 可分配 → 50%现金/50%滚存 → 留存下限校验。
 *
 * <p><b>计算链路(方案书§11/§13.1)</b>:
 * <ol>
 *   <li>提取前净利 profit_before = 经营账(ops)本期 revenue − cost(缺省从 ledger_book 汇总,亦可结账手工传入)</li>
 *   <li>公司回报率 return_rate = profit_before / 实缴出资合计(§13.2:50万/200万=25%)</li>
 *   <li>管理费率 = 阶梯档(rule_config mgmt_fee_ladder,首个 return_rate&lt;maxReturn 的档;25%→10%)</li>
 *   <li>管理费 = profit_before × 费率(先于分配提取);可分配 = profit_before − 管理费</li>
 *   <li>现金 = 可分配 × 50%,滚存 = 余;留存 = 滚存,须 ≥ max(20万, 未来3月供应商净应付),不足则压减现金抬滚存</li>
 *   <li>每人份额 = 现场按快照 × investor.ratio 即时算(GP 数智云仓另得管理费)</li>
 * </ol>
 *
 * <p><b>§十一 D 幂等/冲销</b>:同 period 已有 active 分配 → 拒(force=true 先冲销旧的置 reversed 再重算);
 * {@code reverses_id} 唯一约束 = 冲销幂等键(一原分配仅允许被冲销一次·并发重复冲销 DuplicateKey 回滚)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DistributionService {

    private final DistributionMapper distributionMapper;
    private final InvestorMapper investorMapper;
    private final LedgerBookMapper ledgerBookMapper;
    private final PayableMapper payableMapper;
    private final RuleConfigService rules;
    private final AuditLogService auditLogService;

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ACTIVE = "active";
    private static final String REVERSED = "reversed";

    // ================= 结账分配运行(幂等) =================

    @Transactional
    public DistributionDtos.DistributionDetail run(DistributionDtos.RunRequest req) {
        if (req == null || req.getPeriod() == null || !req.getPeriod().matches("\\d{4}-\\d{2}")) {
            throw new BizException(400, "分配期 period 必填,格式 YYYY-MM");
        }
        String period = req.getPeriod();
        LocalDate bizDate = req.getBizDate() != null && !req.getBizDate().isEmpty()
                ? LocalDate.parse(req.getBizDate())
                : LocalDate.parse(period + "-05");   // 每月 5 号分配

        // 幂等:同期已有 active 分配
        Distribution existing = activeOf(period);
        if (existing != null) {
            if (!Boolean.TRUE.equals(req.getForce())) {
                throw new BizException(400, "分配期 " + period + " 已存在生效分配单 " + existing.getDistributionNo()
                        + ";重算请带 force=true(将先冲销旧单)。");
            }
            doReverse(existing, "force 重算冲销");
        }

        // 出资人 + 实缴出资合计
        List<Investor> investors = activeInvestors();
        if (investors.isEmpty()) {
            throw new BizException(500, "出资人名册为空,无法分配(应 seed GP/LP 四出资方)");
        }
        BigDecimal totalCapital = BigDecimal.ZERO;
        for (Investor iv : investors) {
            totalCapital = totalCapital.add(iv.getAmount());
        }
        if (totalCapital.signum() <= 0) {
            throw new BizException(500, "实缴出资合计为 0,回报率分母非法");
        }

        // ① 提取前净利
        BigDecimal profitBefore = req.getProfitBefore() != null
                ? req.getProfitBefore()
                : opsProfit(period);
        if (profitBefore.signum() <= 0) {
            throw new BizException(400, "提取前净利 ≤ 0(period=" + period + " 经营账净利=" + profitBefore
                    + "),本期无可分配利润;可手工传 profitBefore 结账。");
        }

        // ② 公司回报率
        BigDecimal returnRate = req.getReturnRate() != null
                ? req.getReturnRate()
                : profitBefore.divide(totalCapital, 8, RoundingMode.HALF_UP);

        // ③ 管理费阶梯选档
        BigDecimal mgmtFeeRate = ladderRate(returnRate, bizDate);

        // ④ 管理费 + 可分配
        BigDecimal mgmtFee = profitBefore.multiply(mgmtFeeRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal distributable = profitBefore.subtract(mgmtFee).setScale(2, RoundingMode.HALF_UP);

        // ⑤ 50%现金/50%滚存 + 留存下限校验(不足则压减现金抬滚存)
        BigDecimal cashRatio = safe("distribution_cash_ratio", bizDate, new BigDecimal("0.50"));
        BigDecimal reserveFloor = reserveFloor(bizDate);
        BigDecimal cash50 = distributable.multiply(cashRatio).setScale(2, RoundingMode.HALF_UP);
        BigDecimal roll50 = distributable.subtract(cash50);
        boolean reserveSufficient = true;
        if (roll50.compareTo(reserveFloor) < 0) {
            // 压减现金分配,抬高滚存到留存下限(不超过可分配)
            BigDecimal targetRoll = reserveFloor.min(distributable);
            roll50 = targetRoll;
            cash50 = distributable.subtract(roll50).setScale(2, RoundingMode.HALF_UP);
            reserveSufficient = roll50.compareTo(reserveFloor) >= 0;   // distributable < floor 时仍不足
        }
        BigDecimal reserveAfter = roll50;

        Distribution d = new Distribution();
        d.setDistributionNo(genNo(period, false));
        d.setPeriod(period);
        d.setBizDate(bizDate);
        d.setTotalCapital(totalCapital);
        d.setProfitBefore(profitBefore.setScale(2, RoundingMode.HALF_UP));
        d.setReturnRate(returnRate);
        d.setMgmtFeeRate(mgmtFeeRate);
        d.setMgmtFee(mgmtFee);
        d.setDistributable(distributable);
        d.setCash50(cash50);
        d.setRoll50(roll50);
        d.setReserveFloor(reserveFloor);
        d.setReserveAfter(reserveAfter);
        d.setReserveSufficient(reserveSufficient ? 1 : 0);
        d.setStatus(ACTIVE);
        d.setIsReversal(0);
        d.setOperatorId(currentUserId());
        d.setRemark(req.getRemark());
        distributionMapper.insert(d);

        auditLogService.record("结账分配", "distribution", d.getId(), AuditLogService.EXECUTED,
                "期=" + period + " 净利=" + profitBefore + " 回报率=" + returnRate + " 管理费率=" + mgmtFeeRate
                        + " 可分配=" + distributable + " 现金=" + cash50 + " 滚存=" + roll50
                        + (reserveSufficient ? "" : " ⚠留存不足"));
        log.info("[结账分配] 期 {} 净利 {} → 回报率 {} → 管理费 {}({}) → 可分配 {} → 现金 {}/滚存 {} 留存下限 {}{}",
                period, profitBefore, returnRate, mgmtFee, mgmtFeeRate, distributable, cash50, roll50, reserveFloor,
                reserveSufficient ? "" : " ⚠不足");

        return detail(d.getId());
    }

    // ================= 冲销(§十一 D) =================

    @Transactional
    public DistributionDtos.ReverseImpact reverse(Long id, DistributionDtos.ReverseRequest req) {
        Distribution orig = load(id);
        String reason = req != null && req.getReason() != null ? req.getReason() : "分配冲销";
        Distribution rev = doReverse(orig, reason);

        DistributionDtos.ReverseImpact ri = new DistributionDtos.ReverseImpact();
        ri.setOriginalId(orig.getId());
        ri.setOriginalNo(orig.getDistributionNo());
        ri.setReversalId(rev.getId());
        ri.setReversalNo(rev.getDistributionNo());
        ri.setPeriod(orig.getPeriod());
        ri.setDistributable(orig.getDistributable());
        List<String> items = new ArrayList<>();
        items.add("原分配单 " + orig.getDistributionNo() + " 置为 reversed(退出生效)");
        items.add("生成冲销单 " + rev.getDistributionNo() + "(负额镜像·reverses_id 唯一幂等键)");
        items.add("期 " + orig.getPeriod() + " 释放,可 force 重算或重新 run");
        ri.setItems(items);
        return ri;
    }

    /** 冲销原语:原单置 reversed + 生成负额冲销单(reverses_id 唯一约束兜底幂等)。 */
    private Distribution doReverse(Distribution orig, String reason) {
        if (Integer.valueOf(1).equals(orig.getIsReversal())) {
            throw new BizException(400, "冲销单本身不可再冲销: " + orig.getDistributionNo());
        }
        if (REVERSED.equals(orig.getStatus())) {
            throw new BizException(400, "该分配已冲销,不可重复冲销: " + orig.getDistributionNo());
        }
        boolean existRev = distributionMapper.selectCount(new LambdaQueryWrapper<Distribution>()
                .eq(Distribution::getReversesId, orig.getId())) > 0;
        if (existRev) {
            throw new BizException(400, "该分配已被冲销(幂等拒): " + orig.getDistributionNo());
        }

        Distribution rev = new Distribution();
        rev.setDistributionNo(genNo(orig.getPeriod(), true));
        rev.setPeriod(orig.getPeriod());
        rev.setBizDate(orig.getBizDate());
        rev.setTotalCapital(orig.getTotalCapital());
        rev.setProfitBefore(orig.getProfitBefore().negate());
        rev.setReturnRate(orig.getReturnRate());
        rev.setMgmtFeeRate(orig.getMgmtFeeRate());
        rev.setMgmtFee(orig.getMgmtFee().negate());
        rev.setDistributable(orig.getDistributable().negate());
        rev.setCash50(orig.getCash50().negate());
        rev.setRoll50(orig.getRoll50().negate());
        rev.setReserveFloor(orig.getReserveFloor());
        rev.setReserveAfter(orig.getReserveAfter().negate());
        rev.setReserveSufficient(1);
        rev.setStatus(REVERSED);
        rev.setIsReversal(1);
        rev.setReversesId(orig.getId());   // 唯一约束幂等键
        rev.setOperatorId(currentUserId());
        rev.setRemark(reason);
        distributionMapper.insert(rev);

        orig.setStatus(REVERSED);
        distributionMapper.updateById(orig);

        auditLogService.record("分配冲销", "distribution", orig.getId(), AuditLogService.EXECUTED,
                "冲销 " + orig.getDistributionNo() + " 期=" + orig.getPeriod() + " · " + reason);
        log.info("[分配冲销] {} 期 {} · {}", orig.getDistributionNo(), orig.getPeriod(), reason);
        return rev;
    }

    // ================= 列表 / 详情 / 每人份额 =================

    public List<DistributionDtos.DistributionItem> list(String period, Boolean activeOnly) {
        LambdaQueryWrapper<Distribution> qw = new LambdaQueryWrapper<Distribution>()
                .eq(period != null && !period.isEmpty(), Distribution::getPeriod, period)
                .eq(Boolean.TRUE.equals(activeOnly), Distribution::getStatus, ACTIVE)
                .orderByDesc(Distribution::getId);
        List<DistributionDtos.DistributionItem> out = new ArrayList<>();
        for (Distribution d : distributionMapper.selectList(qw)) {
            out.add(toItem(d));
        }
        return out;
    }

    public DistributionDtos.DistributionDetail detail(Long id) {
        Distribution d = load(id);
        DistributionDtos.DistributionDetail det = new DistributionDtos.DistributionDetail();
        det.setDistribution(toItem(d));
        det.setShares(shares(d));
        det.setSteps(steps(d));
        return det;
    }

    /**
     * 每人份额 = 分配快照 × investor.ratio 即时算(单一真相源·防漂移)。
     * P0-E 角色投影:LP 仅见自己那份(user_id 匹配),老板/财务(canSeeCost)见全量。
     */
    public List<DistributionDtos.ShareItem> shares(Distribution d) {
        CurrentUser cu = UserContext.get();
        String role = cu != null ? cu.getRole() : null;
        Long uid = cu != null ? cu.getUserId() : null;
        boolean seeAll = DataScope.canSeeCost(role);   // 老板/财务/供应链/业务 见全量;GP/LP 仅自己

        List<DistributionDtos.ShareItem> out = new ArrayList<>();
        for (Investor iv : activeInvestors()) {
            boolean self = uid != null && uid.equals(iv.getUserId());
            if (!seeAll && !self) {
                continue;   // 投资人只读:只投影自己那份(P0-E 字段级隔离)
            }
            DistributionDtos.ShareItem s = new DistributionDtos.ShareItem();
            s.setInvestorId(iv.getId());
            s.setName(iv.getName());
            s.setRole(iv.getRole());
            s.setAmount(iv.getAmount());
            s.setRatio(iv.getRatio());
            BigDecimal cashShare = d.getCash50().multiply(iv.getRatio()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal rollShare = d.getRoll50().multiply(iv.getRatio()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal gpFee = "GP".equals(iv.getRole()) ? d.getMgmtFee() : BigDecimal.ZERO;
            s.setCashShare(cashShare);
            s.setRollShare(rollShare);
            s.setMgmtFee(gpFee);
            s.setTotalGain(cashShare.add(rollShare).add(gpFee).setScale(2, RoundingMode.HALF_UP));
            s.setSelf(self);
            out.add(s);
        }
        return out;
    }

    public List<DistributionDtos.InvestorItem> investors() {
        CurrentUser cu = UserContext.get();
        Long uid = cu != null ? cu.getUserId() : null;
        List<DistributionDtos.InvestorItem> out = new ArrayList<>();
        for (Investor iv : activeInvestors()) {
            DistributionDtos.InvestorItem it = new DistributionDtos.InvestorItem();
            it.setId(iv.getId());
            it.setName(iv.getName());
            it.setRole(iv.getRole());
            it.setAmount(iv.getAmount());
            it.setRatio(iv.getRatio());
            it.setSelf(uid != null && uid.equals(iv.getUserId()));
            out.add(it);
        }
        return out;
    }

    // ================= 计算工具 =================

    /** 经营账(ops)本期净利 = revenue − cost(有符号净额·净红冲)。 */
    private BigDecimal opsProfit(String period) {
        BigDecimal revenue = sumLedger(VoucherService.BOOK_OPS, "revenue", period);
        BigDecimal cost = sumLedger(VoucherService.BOOK_OPS, "cost", period);
        return revenue.subtract(cost).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sumLedger(String book, String entryType, String period) {
        List<LedgerBook> rows = ledgerBookMapper.selectList(new LambdaQueryWrapper<LedgerBook>()
                .eq(LedgerBook::getBook, book)
                .eq(LedgerBook::getEntryType, entryType)
                .eq(LedgerBook::getPeriod, period));
        BigDecimal s = BigDecimal.ZERO;
        for (LedgerBook lb : rows) {
            s = s.add(lb.getAmount());
        }
        return s;
    }

    /** 留存下限 = max(20万, 未来3月供应商净应付)。未来3月 = bizDate 起 3 个月内到期的待付 payable 净额。 */
    private BigDecimal reserveFloor(LocalDate bizDate) {
        BigDecimal floor20w = safe("reserve_floor", bizDate, new BigDecimal("200000"));
        LocalDate to = bizDate.plusMonths(3);
        List<Payable> ps = payableMapper.selectList(new LambdaQueryWrapper<Payable>()
                .eq(Payable::getStatus, "待付")
                .ge(Payable::getDueDate, bizDate).le(Payable::getDueDate, to));
        BigDecimal net = BigDecimal.ZERO;
        for (Payable p : ps) {
            net = net.add(p.getAmount());   // 退款红字为负,天然净额
        }
        net = net.max(BigDecimal.ZERO);
        return floor20w.max(net).setScale(2, RoundingMode.HALF_UP);
    }

    /** 管理费阶梯选档:首个 return_rate &lt; maxReturn 的档(边界落上档·§13.1:25%→10%)。 */
    private BigDecimal ladderRate(BigDecimal returnRate, LocalDate bizDate) {
        try {
            JsonNode arr = JSON.readTree(rules.getJson("mgmt_fee_ladder", "", bizDate));
            for (JsonNode band : arr) {
                BigDecimal maxReturn = band.get("maxReturn").decimalValue();
                if (returnRate.compareTo(maxReturn) < 0) {
                    return band.get("rate").decimalValue();
                }
            }
            // 超出所有档 → 取最后一档
            if (arr.size() > 0) {
                return arr.get(arr.size() - 1).get("rate").decimalValue();
            }
        } catch (BizException be) {
            throw be;
        } catch (Exception e) {
            throw new BizException(500, "管理费阶梯 mgmt_fee_ladder 解析失败: " + e.getMessage());
        }
        throw new BizException(500, "管理费阶梯 mgmt_fee_ladder 无档位");
    }

    private DistributionDtos.DistributionItem toItem(Distribution d) {
        DistributionDtos.DistributionItem it = new DistributionDtos.DistributionItem();
        it.setId(d.getId());
        it.setDistributionNo(d.getDistributionNo());
        it.setPeriod(d.getPeriod());
        it.setBizDate(d.getBizDate());
        it.setTotalCapital(d.getTotalCapital());
        it.setProfitBefore(d.getProfitBefore());
        it.setReturnRate(d.getReturnRate());
        it.setMgmtFeeRate(d.getMgmtFeeRate());
        it.setMgmtFee(d.getMgmtFee());
        it.setDistributable(d.getDistributable());
        it.setCash50(d.getCash50());
        it.setRoll50(d.getRoll50());
        it.setReserveFloor(d.getReserveFloor());
        it.setReserveAfter(d.getReserveAfter());
        it.setReserveSufficient(Integer.valueOf(1).equals(d.getReserveSufficient()));
        it.setStatus(d.getStatus());
        it.setIsReversal(Integer.valueOf(1).equals(d.getIsReversal()));
        it.setReversesId(d.getReversesId());
        it.setCreateTime(d.getCreateTime());
        return it;
    }

    private List<String> steps(Distribution d) {
        List<String> s = new ArrayList<>();
        s.add("① 提取前净利(经营账)= " + d.getProfitBefore() + " 元");
        s.add("② 公司回报率 = 净利 / 实缴出资 " + d.getTotalCapital() + " = " + pct(d.getReturnRate()));
        s.add("③ 管理费阶梯档 = " + pct(d.getMgmtFeeRate()) + "(方案书§13.1 按回报率区间)");
        s.add("④ 管理费 = 净利 × 费率 = " + d.getMgmtFee() + " 元(先于分配提取)");
        s.add("⑤ 可分配利润 = 净利 − 管理费 = " + d.getDistributable() + " 元");
        s.add("⑥ 现金分配(每月5号) = " + d.getCash50() + " 元;滚存 = " + d.getRoll50() + " 元(50/50)");
        s.add("⑦ 留存下限 = max(20万, 未来3月供应商净应付) = " + d.getReserveFloor()
                + " 元;分配后留存 = " + d.getReserveAfter()
                + (Integer.valueOf(1).equals(d.getReserveSufficient()) ? " ✓达标" : " ⚠不足(已压减现金)"));
        s.add("⑧ 每人份额 = 现金/滚存 × 各出资比例(GP 数智云仓另得管理费)");
        return s;
    }

    private String pct(BigDecimal r) {
        return r.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP) + "%";
    }

    private Distribution activeOf(String period) {
        return distributionMapper.selectOne(new LambdaQueryWrapper<Distribution>()
                .eq(Distribution::getPeriod, period)
                .eq(Distribution::getStatus, ACTIVE)
                .eq(Distribution::getIsReversal, 0)
                .last("limit 1"));
    }

    private List<Investor> activeInvestors() {
        return investorMapper.selectList(new LambdaQueryWrapper<Investor>()
                .eq(Investor::getActive, 1)
                .orderByAsc(Investor::getId));
    }

    private String genNo(String period, boolean reversal) {
        String base = "FP-" + period.replace("-", "") + (reversal ? "-R" : "");
        String no = base;
        int n = 1;
        while (distributionMapper.selectCount(new LambdaQueryWrapper<Distribution>()
                .eq(Distribution::getDistributionNo, no)) > 0) {
            no = base + "-" + (++n);
        }
        return no;
    }

    private Distribution load(Long id) {
        Distribution d = distributionMapper.selectById(id);
        if (d == null || Integer.valueOf(1).equals(d.getIsDeleted())) {
            throw new BizException(404, "分配单不存在: id=" + id);
        }
        return d;
    }

    private BigDecimal safe(String key, LocalDate bizDate, BigDecimal dft) {
        try {
            return rules.getValue(key, "", bizDate);
        } catch (Exception e) {
            return dft;
        }
    }

    private Long currentUserId() {
        return UserContext.get() != null ? UserContext.get().getUserId() : null;
    }
}
