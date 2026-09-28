package top.aole.rent.modules.contract.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.dto.PaymentTermDtos;
import top.aole.rent.modules.contract.domain.ContractPaymentTerm;
import top.aole.rent.modules.contract.mapper.ContractPaymentTermMapper;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.mapper.PayableMapper;
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
 * 合同付款方式(@owner=本服务 · V118)。
 *
 * <p><b>唯一真相源</b>:付款条件挂在合同上,该合同下的台账设备与采购单全部继承,不再逐台维护。
 * 校验规则(名称不重、触发点合法、比例合计 100%)与「预计付款金额末段补差」原先在
 * {@code AssetPaymentService},V118 起校验上移到本服务,金额计算仍留在那边(它负责生成应付)。
 *
 * <p><b>已付保护</b>:某阶段已有「已付」应付时,该段的比例不能改、段不能删。
 * 修改付款方式只重算「待付」应付,已付的锁定不动(重算由 {@code AssetPaymentService} 执行)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractPaymentService {

    public static final String TRIGGER_ORDER = "下单";
    public static final String TRIGGER_RECEIVE = "入库";
    private static final Set<String> TRIGGERS = new HashSet<>(Arrays.asList(TRIGGER_ORDER, TRIGGER_RECEIVE));
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.0001");

    private final ContractPaymentTermMapper termMapper;
    private final PayableMapper payableMapper;
    private final AssetMapper assetMapper;
    private final RuleConfigService rules;
    private final AuditLogService auditLogService;

    // ============ 读 ============

    /** 合同的付款方式(按段序);没设过返回空列表。 */
    public List<ContractPaymentTerm> terms(Long contractId) {
        if (contractId == null) {
            return new ArrayList<>();
        }
        return termMapper.selectList(new LambdaQueryWrapper<ContractPaymentTerm>()
                .eq(ContractPaymentTerm::getContractId, contractId)
                .orderByAsc(ContractPaymentTerm::getSeq).orderByAsc(ContractPaymentTerm::getId));
    }

    /** 付款方式 → 入参格式(前端编辑与采购继承都用它)。 */
    public List<PaymentTermDtos.TermInput> termsAsInput(Long contractId) {
        List<PaymentTermDtos.TermInput> out = new ArrayList<>();
        for (ContractPaymentTerm t : terms(contractId)) {
            out.add(new PaymentTermDtos.TermInput(t.getStageName(), t.getRatio(),
                    t.getTriggerPoint(), t.getDueDays()));
        }
        return out;
    }

    /** 摘要:首付30%(下单) / 验收60%(入库) / 尾款10%(入库+90天)。没设过返回 null。 */
    public String describe(Long contractId) {
        List<ContractPaymentTerm> ts = terms(contractId);
        if (ts.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (ContractPaymentTerm t : ts) {
            parts.add(t.getStageName() + percent(t.getRatio()) + "(" + t.getTriggerPoint()
                    + (t.getDueDays() != null && t.getDueDays() > 0 ? "+" + t.getDueDays() + "天" : "") + ")");
        }
        return String.join(" / ", parts);
    }

    // ============ 校验 ============

    /** 校验并规范化:名称去空格且不重复、触发时点合法、比例>0 且合计=100%。 */
    public List<PaymentTermDtos.TermInput> normalize(List<PaymentTermDtos.TermInput> input) {
        if (input == null || input.isEmpty()) {
            throw new BizException(400, "至少设置一段付款条件");
        }
        Set<String> names = new HashSet<>();
        BigDecimal sum = BigDecimal.ZERO;
        List<PaymentTermDtos.TermInput> out = new ArrayList<>();
        for (PaymentTermDtos.TermInput t : input) {
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

    // ============ 写 ============

    /**
     * 整套替换合同付款方式。已付阶段锁定:该段的比例不能改、段不能删。
     * 返回替换后的段,调用方(合同服务)据此重算该合同下设备的待付应付。
     */
    @Transactional
    public List<ContractPaymentTerm> replaceAll(Long contractId, List<PaymentTermDtos.TermInput> input) {
        if (contractId == null) {
            throw new BizException(400, "缺少合同");
        }
        List<PaymentTermDtos.TermInput> normalized = normalize(input);
        Map<String, BigDecimal> locked = paidStages(contractId);
        for (Map.Entry<String, BigDecimal> e : locked.entrySet()) {
            PaymentTermDtos.TermInput match = normalized.stream()
                    .filter(t -> t.getStageName().equals(e.getKey())).findFirst().orElse(null);
            if (match == null) {
                throw new BizException(400, "阶段「" + e.getKey() + "」已付款,不能删除或修改比例");
            }
            if (e.getValue().signum() > 0 && match.getRatio().compareTo(e.getValue()) != 0) {
                throw new BizException(400, "阶段「" + e.getKey() + "」已付款,不能删除或修改比例");
            }
        }
        for (ContractPaymentTerm old : terms(contractId)) {
            termMapper.deleteById(old.getId());
        }
        List<ContractPaymentTerm> saved = insert(contractId, normalized);
        // 整套替换后旧段 id 已不存在,把本合同下应付的 term_id 按阶段名重挂到同名新段,
        // 否则段级汇总与「已付锁定」的追溯会断掉
        remapPayableTerms(contractId, saved);
        auditLogService.record("合同付款方式", "contract", contractId, AuditLogService.EXECUTED,
                describeInputs(normalized));
        log.info("[合同付款方式] 合同{} 保存 {} 段: {}", contractId, saved.size(), describeInputs(normalized));
        return saved;
    }

    /** 没设过付款方式时写入默认三段(比例取 rule_config);已设过则原样返回。 */
    @Transactional
    public List<ContractPaymentTerm> ensureDefault(Long contractId, BigDecimal firstPayRatio, Integer accountDays) {
        List<ContractPaymentTerm> exist = terms(contractId);
        if (!exist.isEmpty()) {
            return exist;
        }
        List<ContractPaymentTerm> saved = insert(contractId, normalize(defaultTerms(firstPayRatio, accountDays)));
        log.info("[合同付款方式] 合同{} 未设付款方式,按规则写入默认三段", contractId);
        return saved;
    }

    /** 默认三段:首付(下单) / 验收(入库) / 尾款(入库+账期)。 */
    public List<PaymentTermDtos.TermInput> defaultTerms(BigDecimal firstPayRatio, Integer accountDays) {
        BigDecimal first = firstPayRatio != null ? firstPayRatio
                : ruleValue("payable_stage_ratio", "首付", new BigDecimal("0.3"));
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

    // ============ 内部 ============

    private List<ContractPaymentTerm> insert(Long contractId, List<PaymentTermDtos.TermInput> terms) {
        List<ContractPaymentTerm> out = new ArrayList<>();
        int seq = 1;
        for (PaymentTermDtos.TermInput in : terms) {
            ContractPaymentTerm t = new ContractPaymentTerm();
            t.setContractId(contractId);
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

    /**
     * 该合同下已付款的阶段:阶段名 → 当前同名合同段的比例(同名段不存在时为 0,表示只锁名称)。
     *
     * <p><b>按阶段名而不是 term_id 判定</b>:付款方式整套替换时旧段行会被删,
     * 若按 term_id 去找已付应付,一旦保存过一次锁定就会静默失效。
     * 阶段名是业务键(同合同内不重),V118 迁移的 term_id 重映射也是按它做的。
     * 按合同下所有设备聚合 —— 任何一台设备的某阶段付过款,该阶段就锁定。
     */
    private Map<String, BigDecimal> paidStages(Long contractId) {
        Map<String, BigDecimal> ratioByStage = new HashMap<>();
        terms(contractId).forEach(t -> ratioByStage.put(t.getStageName(), t.getRatio()));
        Map<String, BigDecimal> out = new HashMap<>();
        List<Long> assetIds = contractAssetIds(contractId);
        if (assetIds.isEmpty()) {
            return out;
        }
        List<Payable> paid = payableMapper.selectList(new LambdaQueryWrapper<Payable>()
                .eq(Payable::getStatus, "已付")
                .in(Payable::getAssetId, assetIds));
        for (Payable p : paid) {
            BigDecimal ratio = ratioByStage.get(p.getStage());
            out.put(p.getStage(), ratio == null ? BigDecimal.ZERO : ratio);
        }
        return out;
    }

    /** 把本合同下应付的 term_id 按阶段名重挂到新段(整套替换后旧 id 已删)。 */
    private void remapPayableTerms(Long contractId, List<ContractPaymentTerm> saved) {
        List<Long> assetIds = contractAssetIds(contractId);
        if (assetIds.isEmpty() || saved.isEmpty()) {
            return;
        }
        Map<String, Long> idByStage = new HashMap<>();
        saved.forEach(t -> idByStage.put(t.getStageName(), t.getId()));
        for (Payable p : payableMapper.selectList(new LambdaQueryWrapper<Payable>()
                .in(Payable::getAssetId, assetIds))) {
            Long newId = idByStage.get(p.getStage());
            if (newId == null || newId.equals(p.getTermId())) {
                continue;
            }
            payableMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Payable>()
                    .eq(Payable::getId, p.getId()).set(Payable::getTermId, newId));
        }
    }

    /** 本合同名下的设备 id。 */
    private List<Long> contractAssetIds(Long contractId) {
        List<Long> out = new ArrayList<>();
        if (contractId == null) {
            return out;
        }
        for (Asset a : assetMapper.selectList(new LambdaQueryWrapper<Asset>()
                .eq(Asset::getContractId, contractId))) {
            out.add(a.getId());
        }
        return out;
    }

    private BigDecimal ruleValue(String key, String scope, BigDecimal fallback) {
        try {
            BigDecimal v = rules.getValue(key, scope, LocalDate.now());
            return v != null ? v : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String describeInputs(List<PaymentTermDtos.TermInput> terms) {
        List<String> parts = new ArrayList<>();
        for (PaymentTermDtos.TermInput t : terms) {
            parts.add(t.getStageName() + " " + percent(t.getRatio()) + " "
                    + t.getTriggerPoint() + "+" + t.getDueDays() + "天");
        }
        return String.join(" / ", parts);
    }

    private static String percent(BigDecimal ratio) {
        if (ratio == null) {
            return "";
        }
        return ratio.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
    }
}
