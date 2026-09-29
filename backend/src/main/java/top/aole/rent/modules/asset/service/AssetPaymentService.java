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
import top.aole.rent.modules.contract.domain.ContractPaymentTerm;
import top.aole.rent.modules.contract.mapper.ContractPaymentTermMapper;
import top.aole.rent.modules.contract.service.ContractPaymentService;
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
 * 逐台应付(@owner=本服务)。
 *
 * <ul>
 *   <li><b>付款条件来源:合同</b>(V118 起)。条件挂在 {@code yc_rent_contract_payment_term},
 *       本服务只消费不维护;{@code yc_rent_asset_payment_term} 停止写入,只为历史应付保留读取。</li>
 *   <li>预计付款金额 = 该设备合同价 × 比例,前 N-1 段四舍五入到分,末段补差保证合计 = 合同价。</li>
 *   <li><b>应付一次性全量生成</b>(V118 起):采购下单时把合同付款方式的所有阶段都生成出来,
 *       任一条件挂上即计入累计应付与未付。{@code 触发=下单} 的到期 = 下单日+N;
 *       {@code 触发=入库} 的在入库前按采购单的「预计入库日」+N 推算并标 {@code due_provisional=1},
 *       实际入库时改写为真实入库日+N 并清标记 —— 每笔应付都有到期日,现金流分层与兑付缺口口径不变。</li>
 *   <li>条件或合同价变更时,重算本设备 待付 应付;已付 的阶段锁定不动。</li>
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
    private final ContractPaymentService contractPaymentService;
    private final ContractPaymentTermMapper contractTermMapper;

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

    /**
     * 下单:按合同付款方式一次生成该设备的全部阶段应付。
     *
     * <p>{@code 触发=下单} 的到期 = 下单日+N;{@code 触发=入库} 的在入库前按采购单的预计入库日+N 推算,
     * 并标 {@code due_provisional=1}。合同没设付款方式时直接拒绝 —— 付款方式是合同的必备要素。
     */
    @Transactional
    public void onOrder(PurchaseIn p, PurchaseItem item, Long assetId, Long contractId) {
        List<ContractPaymentTerm> terms = contractPaymentService.terms(contractId);
        if (terms.isEmpty()) {
            throw new BizException(400, "合同还没设置付款方式,请先到合同里设置后再下单");
        }
        generate(p, assetId, item.getId(), item.getSerialNo(), item.getPurchasePrice(), terms, new HashSet<>());
    }

    /** 付款条件摘要:首付30%(下单)/验收60%(入库)…(取该设备所属合同的付款方式) */
    public String describeTerms(Long contractId) {
        return contractPaymentService.describe(contractId);
    }

    private static String percent(BigDecimal ratio) {
        if (ratio == null) {
            return "";
        }
        return ratio.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
    }

    /**
     * 入库:把该设备「到期日为预估」的待付应付改写为真实入库日 + 账期,并清掉预估标记。
     *
     * <p>V118 起应付在下单时已全量生成,入库不再新增应付 —— 只做到期日兑现。
     * 老单据(下单时明细没挂设备)会把已生成应付回填 asset_id;若该设备一条应付都没有
     * (V118 之前的历史单),按合同付款方式补生成一次。
     */
    @Transactional
    public void onReceive(PurchaseIn p, PurchaseItem item, Long assetId) {
        // 老单据:应付当时只挂了采购明细,入库生成设备后回填
        payableMapper.update(null, new LambdaUpdateWrapper<Payable>()
                .eq(Payable::getPurchaseItemId, item.getId()).set(Payable::getAssetId, assetId));

        LocalDate receiveDate = p.getReceiveDate() != null ? p.getReceiveDate() : LocalDate.now();
        List<Payable> payables = payablesOf(assetId);
        if (payables.isEmpty()) {
            // V118 之前的历史单:该设备没有任何应付,按合同付款方式补一次
            List<ContractPaymentTerm> terms = contractPaymentService.terms(p.getContractId());
            if (!terms.isEmpty()) {
                generate(p, assetId, item.getId(), item.getSerialNo(), item.getPurchasePrice(),
                        terms, new HashSet<>());
            }
            return;
        }
        int fixed = 0;
        for (Payable pay : payables) {
            if (!"待付".equals(pay.getStatus()) || !Integer.valueOf(1).equals(pay.getDueProvisional())) {
                continue;
            }
            int days = dueDaysOf(pay.getTermId());
            payableMapper.update(null, new LambdaUpdateWrapper<Payable>()
                    .eq(Payable::getId, pay.getId())
                    .set(Payable::getDueDate, receiveDate.plusDays(days))
                    .set(Payable::getDueProvisional, 0));
            fixed++;
        }
        if (fixed > 0) {
            log.info("[应付] 入库兑现到期日: 设备{} 共{}笔按入库日 {} 重算", assetId, fixed, receiveDate);
        }
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
        plan.setDefaultTemplate(contractPaymentService.defaultTerms(null, null));

        List<ContractPaymentTerm> terms = contractPaymentService.terms(a.getContractId());
        plan.setContractId(a.getContractId());
        plan.setInherited(true);
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
            ContractPaymentTerm t = terms.get(i);
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
                line.setDueProvisional(Integer.valueOf(1).equals(pay.getDueProvisional()));
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
        if (terms.isEmpty()) {
            plan.setNote("所属合同还没设置付款方式,请到合同里设置(付款条件以合同为准,不在设备上单独维护)");
        } else if (p == null) {
            plan.setNote("该设备不是采购入库建档,付款条件只计算预计付款,不生成应付");
        } else if ("已红冲".equals(p.getStatus())) {
            plan.setNote("采购单已退货红冲,不再生成应付");
        } else if (hasLegacy(p.getId(), "首付", "验收", "尾款")) {
            plan.setNote("该采购单含旧版整单应付,已整单生成的阶段不再逐台生成,避免重复");
        }
        return plan;
    }

    /**
     * 合同价或合同付款方式变化后,重算本设备的 待付 应付(已付阶段锁定不动)。
     * 付款方式本身由 {@link ContractPaymentService} 维护,本方法只负责把变化落到应付上。
     */
    @Transactional
    public void resyncPending(Asset a) {
        List<ContractPaymentTerm> terms = contractPaymentService.terms(a.getContractId());
        if (terms.isEmpty()) {
            return;
        }
        regenerate(a, terms);
    }

    // ============ 内部 ============

    private void regenerate(Asset a, List<ContractPaymentTerm> terms) {
        List<Payable> existing = payablesOf(a.getId());
        // 采购单优先取设备上回填的;老数据没回填时退回从已有应付上找,否则改了合同付款方式
        // 这台设备的应付金额会停在旧比例上,和合同对不上
        Long purchaseInId = a.getPurchaseInId() != null ? a.getPurchaseInId()
                : existing.stream().map(Payable::getPurchaseInId)
                        .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        PurchaseIn p = purchaseInId == null ? null : purchaseInMapper.selectById(purchaseInId);
        if (p == null || "已红冲".equals(p.getStatus())) {
            return;
        }
        Set<String> paidNames = paidStages(a.getId()).keySet();
        Long purchaseItemId = existing.stream().map(Payable::getPurchaseItemId)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        // 手工调整过金额的行不动 —— 人填的值不能被重算静默冲掉;
        // 它们所属的阶段也跳过生成,否则同一阶段会出两条
        Set<String> manualStages = new HashSet<>();
        for (Payable pay : payablesOf(a.getId())) {
            if (!"待付".equals(pay.getStatus())) {
                continue;
            }
            if (Integer.valueOf(1).equals(pay.getAmountManual())) {
                manualStages.add(pay.getStage());
                continue;
            }
            payableMapper.deleteById(pay.getId());
        }
        Set<String> skip = new HashSet<>(paidNames);
        skip.addAll(manualStages);
        generate(p, a.getId(), purchaseItemId, a.getSerialNo(), a.getPurchasePrice(), terms, skip);
    }

    /**
     * 按合同付款方式一次生成全部阶段的应付。
     *
     * <p>到期日:{@code 触发=下单} → 下单日+N;{@code 触发=入库} → 已入库用真实入库日+N,
     * 未入库用预计入库日(缺失则退回下单日)+N 并标 {@code due_provisional=1}。
     * {@code skipNames} 里的阶段(已付)跳过;旧版整单应付已覆盖的阶段也跳过,避免重复计负债。
     */
    private void generate(PurchaseIn p, Long assetId, Long purchaseItemId, String serialNo, BigDecimal price,
                          List<ContractPaymentTerm> terms, Set<String> skipNames) {
        if (price == null || "已红冲".equals(p.getStatus())) {
            return;
        }
        boolean legacyOrder = hasLegacy(p.getId(), "首付");
        boolean legacyReceive = hasLegacy(p.getId(), "验收", "尾款");
        List<BigDecimal> ratios = new ArrayList<>();
        terms.forEach(t -> ratios.add(t.getRatio()));
        List<BigDecimal> amounts = expectedAmounts(price, ratios);
        for (int i = 0; i < terms.size(); i++) {
            ContractPaymentTerm t = terms.get(i);
            if (skipNames.contains(t.getStageName())) {
                continue;
            }
            boolean orderStage = TRIGGER_ORDER.equals(t.getTriggerPoint());
            if (orderStage && legacyOrder) {
                continue;
            }
            if (!orderStage && legacyReceive) {
                continue;
            }
            LocalDate base;
            boolean provisional = false;
            if (orderStage) {
                base = p.getOrderDate() != null ? p.getOrderDate() : LocalDate.now();
            } else if (p.getReceiveDate() != null) {
                base = p.getReceiveDate();
            } else {
                // 未入库:按预计入库日推算,到期日标为预估,入库时兑现
                base = p.getExpectReceiveDate() != null ? p.getExpectReceiveDate()
                        : (p.getOrderDate() != null ? p.getOrderDate() : LocalDate.now());
                provisional = true;
            }
            Payable pay = new Payable();
            pay.setPurchaseInId(p.getId());
            pay.setAssetId(assetId);
            pay.setPurchaseItemId(purchaseItemId);
            pay.setTermId(t.getId());
            pay.setStage(t.getStageName());
            pay.setDueDate(base.plusDays(t.getDueDays() == null ? 0 : t.getDueDays()));
            pay.setDueProvisional(provisional ? 1 : 0);
            pay.setAmount(amounts.get(i));
            pay.setStatus("待付");
            pay.setRemark(t.getStageName() + " " + pct(t.getRatio()) + " · 设备 " + serialNo
                    + (provisional ? "(到期日按预计入库日推算)" : ""));
            payableMapper.insert(pay);
        }
    }

    /** 某条应付对应合同段的账期天数(供采购模块改预计入库日后重算到期调用)。 */
    public int dueDaysOfTerm(Long termId) {
        return dueDaysOf(termId);
    }

    /** 某条应付对应合同段的账期天数;段已不存在返回 0。 */
    private int dueDaysOf(Long termId) {
        if (termId == null) {
            return 0;
        }
        ContractPaymentTerm t = contractTermMapper.selectById(termId);
        return t == null || t.getDueDays() == null ? 0 : t.getDueDays();
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
        Map<String, BigDecimal> out = new HashMap<>();
        for (Payable pay : payablesOf(assetId)) {
            if ("已付".equals(pay.getStatus())) {
                ContractPaymentTerm t = pay.getTermId() == null ? null
                        : contractTermMapper.selectById(pay.getTermId());
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
