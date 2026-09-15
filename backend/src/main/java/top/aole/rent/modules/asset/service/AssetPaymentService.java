package top.aole.rent.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.domain.AssetPaymentTerm;
import top.aole.rent.modules.asset.dto.PaymentTermDtos;
import top.aole.rent.modules.asset.mapper.AssetPaymentTermMapper;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.domain.PurchaseIn;
import top.aole.rent.modules.purchase.domain.PurchaseItem;
import top.aole.rent.modules.purchase.mapper.PayableMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设备合同付款条件 + 逐台应付(@owner=本服务)。
 *
 * <ul>
 *   <li>付款条件:自定义多段(名称/比例/触发时点/到期天数),各段比例合计必须 100%。</li>
 *   <li>预计付款金额 = 集采价 × 比例,前 N-1 段四舍五入到分,末段补差保证合计 = 集采价。</li>
 *   <li>应付:触发时点=下单 的阶段在采购下单时生成(到期=下单日+N 天),=入库 的在入库时生成(到期=入库日+N 天),
 *       每条应付挂设备/采购明细/条件。条件或集采价变更时,重算本设备 待付 应付;已付 的阶段锁定不动。</li>
 *   <li>兼容旧版整单应付(asset_id/purchase_item_id 为空):已有整单「首付」则不再逐台生成下单阶段,
 *       已有整单「验收/尾款」则不再逐台生成入库阶段,避免重复计负债。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetPaymentService {

    public static final String TRIGGER_ORDER = "下单";
    public static final String TRIGGER_RECEIVE = "入库";
    private static final Set<String> TRIGGERS = new HashSet<>(Arrays.asList(TRIGGER_ORDER, TRIGGER_RECEIVE));
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.0001");

    private final AssetPaymentTermMapper termMapper;
    private final PayableMapper payableMapper;
    private final PurchaseInMapper purchaseInMapper;
    private final RuleConfigService rules;
    private final AuditLogService auditLogService;

    // ============ 默认条件 / 校验 ============

    /** 默认三段:首付(下单) / 验收(入库) / 尾款(入库+账期);比例取入参或 rule_config[payable_stage_ratio]。 */
    public List<PaymentTermDtos.TermInput> defaultTerms(BigDecimal firstPayRatio, Integer accountDays) {
        BigDecimal first = firstPayRatio != null ? firstPayRatio : ruleValue("payable_stage_ratio", "首付", new BigDecimal("0.3"));
        BigDecimal accept = ruleValue("payable_stage_ratio", "验收", new BigDecimal("0.6"));
        if (first.add(accept).compareTo(ONE) > 0) {
            accept = ONE.subtract(first);
        }
        BigDecimal tail = ONE.subtract(first).subtract(accept);
        int days = accountDays != null && accountDays > 0 ? accountDays
                : ruleValue("payable_tail_days", "", BigDecimal.valueOf(90)).intValue();
        List<PaymentTermDtos.TermInput> out = new ArrayList<>();
        out.add(new PaymentTermDtos.TermInput("首付", first, TRIGGER_ORDER, 0));
        if (accept.signum() > 0) {
            out.add(new PaymentTermDtos.TermInput("验收", accept, TRIGGER_RECEIVE, 0));
        }
        if (tail.signum() > 0) {
            out.add(new PaymentTermDtos.TermInput("尾款", tail, TRIGGER_RECEIVE, days));
        }
        return out;
    }

    /** 校验并规范化:名称去空格且不重复、触发时点合法、比例>0 且合计=100%。 */
    public List<PaymentTermDtos.TermInput> normalize(List<PaymentTermDtos.TermInput> terms) {
        if (terms == null || terms.isEmpty()) {
            throw new BizException(400, "至少设置一段付款条件");
        }
        Set<String> names = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        List<PaymentTermDtos.TermInput> out = new ArrayList<>();
        for (PaymentTermDtos.TermInput t : terms) {
            String name = t.getStageName() == null ? "" : t.getStageName().trim();
            if (name.isEmpty()) {
                throw new BizException(400, "付款阶段名称不能为空");
            }
            if (name.length() > 16) {
                throw new BizException(400, "付款阶段名称最长 16 字: " + name);
            }
            if (!names.add(name)) {
                throw new BizException(400, "付款阶段名称重复: " + name);
            }
            if ("退款红字".equals(name)) {
                throw new BizException(400, "「退款红字」为系统保留阶段名");
            }
            if (t.getRatio() == null || t.getRatio().signum() <= 0 || t.getRatio().compareTo(ONE) > 0) {
                throw new BizException(400, "阶段「" + name + "」比例须在 0-100% 之间且大于 0");
            }
            String trigger = t.getTriggerPoint() == null || t.getTriggerPoint().trim().isEmpty()
                    ? TRIGGER_RECEIVE : t.getTriggerPoint().trim();
            if (!TRIGGERS.contains(trigger)) {
                throw new BizException(400, "阶段「" + name + "」触发时点应为 下单 或 入库");
            }
            int days = t.getDueDays() == null ? 0 : t.getDueDays();
            if (days < 0) {
                throw new BizException(400, "阶段「" + name + "」到期天数不能为负");
            }
            BigDecimal ratio = t.getRatio().setScale(8, RoundingMode.HALF_UP);
            sum = sum.add(ratio);
            out.add(new PaymentTermDtos.TermInput(name, ratio, trigger, days));
        }
        if (sum.subtract(ONE).abs().compareTo(TOLERANCE) > 0) {
            throw new BizException(400, "各段付款比例合计须为 100%,当前为 "
                    + sum.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%");
        }
        return out;
    }

    /** 预计付款金额:前 N-1 段四舍五入到分,末段补差。价格为空返回全 null。 */
    public List<BigDecimal> expectedAmounts(BigDecimal price, List<BigDecimal> ratios) {
        List<BigDecimal> out = new ArrayList<>();
        if (price == null) {
            ratios.forEach(r -> out.add(null));
            return out;
        }
        BigDecimal acc = BigDecimal.ZERO;
        for (int i = 0; i < ratios.size(); i++) {
            BigDecimal v = i == ratios.size() - 1
                    ? price.subtract(acc).setScale(2, RoundingMode.HALF_UP)
                    : price.multiply(ratios.get(i)).setScale(2, RoundingMode.HALF_UP);
            acc = acc.add(v);
            out.add(v);
        }
        return out;
    }

    // ============ 采购下单 / 入库 ============

    /** 下单:为采购明细保存付款条件,并生成 触发=下单 的逐台应付。 */
    @Transactional
    public void onOrder(PurchaseIn p, PurchaseItem item, List<PaymentTermDtos.TermInput> terms) {
        List<PaymentTermDtos.TermInput> normalized = normalize(terms);
        List<AssetPaymentTerm> saved = insertTerms(null, item.getId(), normalized);
        generate(p, null, item.getId(), item.getSerialNo(), item.getPurchasePrice(), saved,
                new HashSet<>(), true, false);
    }

    /** 入库:付款条件回填设备(旧单无条件则按默认模板补齐),已生成应付回填设备,并生成 触发=入库 的逐台应付。 */
    @Transactional
    public void onReceive(PurchaseIn p, PurchaseItem item, Long assetId) {
        List<AssetPaymentTerm> terms = termMapper.selectList(new LambdaQueryWrapper<AssetPaymentTerm>()
                .eq(AssetPaymentTerm::getPurchaseItemId, item.getId()).orderByAsc(AssetPaymentTerm::getSeq));
        if (terms.isEmpty()) {
            terms = insertTerms(assetId, item.getId(), defaultTerms(p.getFirstPayRatio(), p.getAccountDays()));
        } else {
            termMapper.update(null, new LambdaUpdateWrapper<AssetPaymentTerm>()
                    .eq(AssetPaymentTerm::getPurchaseItemId, item.getId()).set(AssetPaymentTerm::getAssetId, assetId));
        }
        payableMapper.update(null, new LambdaUpdateWrapper<Payable>()
                .eq(Payable::getPurchaseItemId, item.getId()).set(Payable::getAssetId, assetId));
        boolean legacyReceive = hasLegacy(p.getId(), "验收", "尾款");
        generate(p, assetId, item.getId(), item.getSerialNo(), item.getPurchasePrice(), terms,
                new HashSet<>(), false, !legacyReceive);
    }

    // ============ 设备详情 / 编辑 ============

    public PaymentTermDtos.PaymentPlan plan(Asset a, boolean seeCost) {
        PaymentTermDtos.PaymentPlan plan = new PaymentTermDtos.PaymentPlan();
        PurchaseIn p = a.getPurchaseInId() == null ? null : purchaseInMapper.selectById(a.getPurchaseInId());
        if (p != null) {
            plan.setPurchaseInId(p.getId());
            plan.setPurchaseNo(p.getNo());
            plan.setPurchaseStatus(p.getStatus());
        }
        plan.setBasePrice(seeCost ? a.getPurchasePrice() : null);
        plan.setDefaultTemplate(defaultTerms(null, null));

        List<AssetPaymentTerm> terms = termsOf(a.getId());
        Map<Long, Payable> payableByTerm = new HashMap<>();
        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        for (Payable pay : payablesOf(a.getId())) {
            if ("待付".equals(pay.getStatus())) {
                pending = pending.add(pay.getAmount());
            } else if ("已付".equals(pay.getStatus())) {
                paid = paid.add(pay.getAmount());
            }
            if (pay.getTermId() != null && !"红冲".equals(pay.getStatus())) {
                payableByTerm.put(pay.getTermId(), pay);
            }
        }
        List<BigDecimal> ratios = new ArrayList<>();
        terms.forEach(t -> ratios.add(t.getRatio()));
        List<BigDecimal> amounts = expectedAmounts(a.getPurchasePrice(), ratios);
        BigDecimal ratioTotal = BigDecimal.ZERO;
        BigDecimal expectedTotal = BigDecimal.ZERO;
        for (int i = 0; i < terms.size(); i++) {
            AssetPaymentTerm t = terms.get(i);
            PaymentTermDtos.TermLine line = new PaymentTermDtos.TermLine();
            line.setId(t.getId());
            line.setSeq(t.getSeq());
            line.setStageName(t.getStageName());
            line.setRatio(t.getRatio());
            line.setTriggerPoint(t.getTriggerPoint());
            line.setDueDays(t.getDueDays());
            line.setExpectedAmount(seeCost ? amounts.get(i) : null);
            Payable pay = payableByTerm.get(t.getId());
            if (pay != null) {
                line.setPayableId(pay.getId());
                line.setPayableAmount(seeCost ? pay.getAmount() : null);
                line.setPayableDueDate(pay.getDueDate());
                line.setPayableStatus(pay.getStatus());
            }
            ratioTotal = ratioTotal.add(t.getRatio());
            if (amounts.get(i) != null) {
                expectedTotal = expectedTotal.add(amounts.get(i));
            }
            plan.getTerms().add(line);
        }
        plan.setRatioTotal(ratioTotal);
        plan.setExpectedTotal(seeCost && a.getPurchasePrice() != null ? expectedTotal : null);
        plan.setPendingTotal(seeCost ? pending.setScale(2, RoundingMode.HALF_UP) : null);
        plan.setPaidTotal(seeCost ? paid.setScale(2, RoundingMode.HALF_UP) : null);
        if (p == null) {
            plan.setNote("该设备不是采购入库建档,付款条件只计算预计付款,不生成应付");
        } else if ("已红冲".equals(p.getStatus())) {
            plan.setNote("采购单已退货红冲,不再生成应付");
        } else if (hasLegacy(p.getId(), "首付", "验收", "尾款")) {
            plan.setNote("该采购单含旧版整单应付,已整单生成的阶段不再逐台生成,避免重复");
        }
        return plan;
    }

    /** 编辑设备付款条件:已付阶段锁定;其余 待付 应付按新条件与当前集采价重生成。 */
    @Transactional
    public void updateTerms(Asset a, List<PaymentTermDtos.TermInput> input) {
        List<PaymentTermDtos.TermInput> normalized = normalize(input);
        Map<String, BigDecimal> locked = paidStages(a.getId());
        for (Map.Entry<String, BigDecimal> e : locked.entrySet()) {
            PaymentTermDtos.TermInput match = normalized.stream()
                    .filter(t -> t.getStageName().equals(e.getKey())).findFirst().orElse(null);
            if (match == null || match.getRatio().compareTo(e.getValue()) != 0) {
                throw new BizException(400, "阶段「" + e.getKey() + "」已付款,不能删除或修改比例");
            }
        }
        List<AssetPaymentTerm> old = termsOf(a.getId());
        Long purchaseItemId = old.stream().map(AssetPaymentTerm::getPurchaseItemId)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        old.forEach(t -> termMapper.deleteById(t.getId()));
        List<AssetPaymentTerm> saved = insertTerms(a.getId(), purchaseItemId, normalized);
        regenerate(a, saved, purchaseItemId);
        auditLogService.record("设备付款条件", "asset", a.getId(), AuditLogService.EXECUTED, describe(normalized));
    }

    /** 集采价变化后重算本设备 待付 应付(条件不变)。 */
    @Transactional
    public void resyncPending(Asset a) {
        List<AssetPaymentTerm> terms = termsOf(a.getId());
        if (terms.isEmpty()) {
            return;
        }
        Long purchaseItemId = terms.stream().map(AssetPaymentTerm::getPurchaseItemId)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        regenerate(a, terms, purchaseItemId);
    }

    // ============ 内部 ============

    private void regenerate(Asset a, List<AssetPaymentTerm> terms, Long purchaseItemId) {
        PurchaseIn p = a.getPurchaseInId() == null ? null : purchaseInMapper.selectById(a.getPurchaseInId());
        if (p == null || "已红冲".equals(p.getStatus())) {
            return;
        }
        Set<String> paidNames = paidStages(a.getId()).keySet();
        for (Payable pay : payablesOf(a.getId())) {
            if ("待付".equals(pay.getStatus())) {
                payableMapper.deleteById(pay.getId());
            }
        }
        boolean orderAllowed = !hasLegacy(p.getId(), "首付");
        boolean receiveAllowed = p.getReceiveDate() != null && !hasLegacy(p.getId(), "验收", "尾款");
        generate(p, a.getId(), purchaseItemId, a.getSerialNo(), a.getPurchasePrice(), terms, paidNames,
                orderAllowed, receiveAllowed);
    }

    private void generate(PurchaseIn p, Long assetId, Long purchaseItemId, String serialNo, BigDecimal price,
                          List<AssetPaymentTerm> terms, Set<String> skipNames, boolean orderStages, boolean receiveStages) {
        if (price == null || "已红冲".equals(p.getStatus())) {
            return;
        }
        List<BigDecimal> ratios = new ArrayList<>();
        terms.forEach(t -> ratios.add(t.getRatio()));
        List<BigDecimal> amounts = expectedAmounts(price, ratios);
        for (int i = 0; i < terms.size(); i++) {
            AssetPaymentTerm t = terms.get(i);
            if (skipNames.contains(t.getStageName())) {
                continue;
            }
            LocalDate base;
            if (TRIGGER_ORDER.equals(t.getTriggerPoint())) {
                if (!orderStages) {
                    continue;
                }
                base = p.getOrderDate() != null ? p.getOrderDate() : LocalDate.now();
            } else {
                if (!receiveStages || p.getReceiveDate() == null) {
                    continue;
                }
                base = p.getReceiveDate();
            }
            Payable pay = new Payable();
            pay.setPurchaseInId(p.getId());
            pay.setAssetId(assetId);
            pay.setPurchaseItemId(purchaseItemId);
            pay.setTermId(t.getId());
            pay.setStage(t.getStageName());
            pay.setDueDate(base.plusDays(t.getDueDays() == null ? 0 : t.getDueDays()));
            pay.setAmount(amounts.get(i));
            pay.setStatus("待付");
            pay.setRemark(t.getStageName() + " " + pct(t.getRatio()) + " · 设备 " + serialNo);
            payableMapper.insert(pay);
        }
    }

    private List<AssetPaymentTerm> insertTerms(Long assetId, Long purchaseItemId, List<PaymentTermDtos.TermInput> terms) {
        List<AssetPaymentTerm> out = new ArrayList<>();
        int seq = 1;
        for (PaymentTermDtos.TermInput in : terms) {
            AssetPaymentTerm t = new AssetPaymentTerm();
            t.setAssetId(assetId);
            t.setPurchaseItemId(purchaseItemId);
            t.setSeq(seq++);
            t.setStageName(in.getStageName());
            t.setRatio(in.getRatio());
            t.setTriggerPoint(in.getTriggerPoint());
            t.setDueDays(in.getDueDays() == null ? 0 : in.getDueDays());
            termMapper.insert(t);
            out.add(t);
        }
        return out;
    }

    private List<AssetPaymentTerm> termsOf(Long assetId) {
        return termMapper.selectList(new LambdaQueryWrapper<AssetPaymentTerm>()
                .eq(AssetPaymentTerm::getAssetId, assetId)
                .orderByAsc(AssetPaymentTerm::getSeq).orderByAsc(AssetPaymentTerm::getId));
    }

    private List<Payable> payablesOf(Long assetId) {
        return payableMapper.selectList(new LambdaQueryWrapper<Payable>()
                .eq(Payable::getAssetId, assetId).orderByAsc(Payable::getId));
    }

    /** 已付阶段:阶段名 → 该阶段对应条件的比例(取当前条件,无则 0 表示仅锁名称)。 */
    private Map<String, BigDecimal> paidStages(Long assetId) {
        Map<Long, AssetPaymentTerm> termById = new HashMap<>();
        termsOf(assetId).forEach(t -> termById.put(t.getId(), t));
        Map<String, BigDecimal> out = new HashMap<>();
        for (Payable pay : payablesOf(assetId)) {
            if ("已付".equals(pay.getStatus())) {
                AssetPaymentTerm t = pay.getTermId() == null ? null : termById.get(pay.getTermId());
                out.put(pay.getStage(), t == null ? BigDecimal.ZERO : t.getRatio());
            }
        }
        return out;
    }

    /** 采购单是否有旧版整单应付(无设备/明细归属)且阶段在给定范围内。 */
    private boolean hasLegacy(Long purchaseInId, String... stages) {
        return payableMapper.selectCount(new LambdaQueryWrapper<Payable>()
                .eq(Payable::getPurchaseInId, purchaseInId)
                .isNull(Payable::getAssetId)
                .isNull(Payable::getPurchaseItemId)
                .in(Payable::getStage, Arrays.asList(stages))
                .ne(Payable::getStatus, "红冲")) > 0;
    }

    private BigDecimal ruleValue(String key, String scope, BigDecimal fallback) {
        try {
            BigDecimal v = rules.getValue(key, scope, LocalDate.now());
            return v != null ? v : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private String describe(List<PaymentTermDtos.TermInput> terms) {
        StringBuilder sb = new StringBuilder();
        for (PaymentTermDtos.TermInput t : terms) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(t.getStageName()).append(' ').append(pct(t.getRatio())).append(' ')
                    .append(t.getTriggerPoint()).append('+').append(t.getDueDays()).append('天');
        }
        return sb.toString();
    }

    private static String pct(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }
}
