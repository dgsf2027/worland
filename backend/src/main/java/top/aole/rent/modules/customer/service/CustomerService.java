package top.aole.rent.modules.customer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.domain.CustomerFollowup;
import top.aole.rent.modules.customer.domain.Opportunity;
import top.aole.rent.modules.customer.dto.AdmissionRequest;
import top.aole.rent.modules.customer.dto.CustomerDetailResponse;
import top.aole.rent.modules.customer.dto.CustomerPoolItem;
import top.aole.rent.modules.customer.dto.CustomerSaveRequest;
import top.aole.rent.modules.customer.dto.FollowupRequest;
import top.aole.rent.modules.customer.dto.PipelineResponse;
import top.aole.rent.modules.customer.mapper.CustomerFollowupMapper;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.customer.mapper.OpportunityMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 客户 CRM 服务(M1-03/04/05/09)。客户池/销售管道/详情/跟进/风控准入。
 *
 * <p><b>P0-E 隔离(服务端强制,非前端隐藏)</b>:
 * <ul>
 *   <li>行级:业务(BD)角色只见 owner_user=自己 或 公海(null);老板/财务/供应链见全量。
 *       占位期隔离键=UserContext 解析的真实 userId(非 0)。</li>
 *   <li>字段级:授信额度/目标IRR/累计利润LTV 等敏感字段,GP/LP 投资人角色打码(置 null + masked=true)。</li>
 * </ul>
 * <p><b>派生字段(§4.17/§4.24)</b>:评级/加权信用分/集中度 即时算不落库;
 * 准入建议由 rule_config[customer_admission_matrix] 出,禁硬编码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerService {

    private final CustomerMapper customerMapper;
    private final CustomerFollowupMapper followupMapper;
    private final OpportunityMapper opportunityMapper;
    private final RuleConfigService rules;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Set<String> VALID_PHASE =
            new HashSet<>(Arrays.asList("线索", "跟进", "商机", "成交", "在租", "流失"));
    /** 未成交阶段:评级为"预估" */
    private static final Set<String> PRE_DEAL = new HashSet<>(Arrays.asList("线索", "跟进", "商机"));

    // ============ 客户池 ============

    public PageResult<CustomerPoolItem> pool(String phase, String rating, Long owner,
                                             String keyword, int page, int size) {
        CurrentUser me = UserContext.require();
        String role = me.getRole();
        boolean seeCost = DataScope.canSeeCost(role);
        boolean ownerScoped = DataScope.isOwnerScoped(role);

        LambdaQueryWrapper<Customer> qw = new LambdaQueryWrapper<Customer>()
                .eq(phase != null && !phase.isEmpty(), Customer::getPhase, phase)
                .eq(owner != null, Customer::getOwnerUser, owner)
                .and(keyword != null && !keyword.trim().isEmpty(), w -> w
                        .like(Customer::getName, keyword.trim())
                        .or().like(Customer::getContact, keyword.trim())
                        .or().like(Customer::getPhone, keyword.trim()))
                .orderByAsc(Customer::getId);
        // 行级隔离:业务只见自己名下 + 公海
        if (ownerScoped) {
            Long myId = me.getUserId();
            qw.and(w -> w.eq(Customer::getOwnerUser, myId).or().isNull(Customer::getOwnerUser));
        }

        List<Customer> all = customerMapper.selectList(qw);
        List<CustomerPoolItem> items = new ArrayList<>();
        for (Customer c : all) {
            String r = rating(c);
            if (rating != null && !rating.isEmpty() && !rating.equalsIgnoreCase(r)) {
                continue;
            }
            CustomerPoolItem it = new CustomerPoolItem();
            it.setId(c.getId());
            it.setName(c.getName());
            it.setPhase(c.getPhase());
            it.setOwnerUser(c.getOwnerUser());
            it.setOwnerName(resolveUserName(c.getOwnerUser()));
            it.setValueTier(c.getValueTier());
            it.setRating(r);
            it.setRatingPredicted(r != null && PRE_DEAL.contains(c.getPhase()));
            it.setInPublicPool(c.getOwnerUser() == null);
            // 敏感财务字段投影
            BigDecimal amount = pickAmount(c);
            it.setExposureOrOppAmount(seeCost ? amount : null);
            it.setReceivableOverdue(seeCost ? c.getReceivableOverdue() : null);
            it.setSensitiveMasked(!seeCost);
            it.setNextFollowDate(c.getNextFollowDate());
            it.setFollowStatus(followStatus(c.getNextFollowDate()));
            items.add(it);
        }

        long total = items.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(items.size(), from + size);
        List<CustomerPoolItem> records = from >= items.size() ? new ArrayList<>() : items.subList(from, to);
        return new PageResult<>(total, page, size, records);
    }

    // ============ 销售管道看板 ============

    public PipelineResponse pipeline() {
        CurrentUser me = UserContext.require();
        boolean seeCost = DataScope.canSeeCost(me.getRole());
        boolean ownerScoped = DataScope.isOwnerScoped(me.getRole());

        LambdaQueryWrapper<Customer> qw = new LambdaQueryWrapper<Customer>().orderByAsc(Customer::getId);
        if (ownerScoped) {
            Long myId = me.getUserId();
            qw.and(w -> w.eq(Customer::getOwnerUser, myId).or().isNull(Customer::getOwnerUser));
        }
        List<Customer> all = customerMapper.selectList(qw);

        // 加权预测:open 商机 est×prob(受行级隔离范围约束)
        Set<Long> visibleIds = all.stream().map(Customer::getId).collect(Collectors.toSet());
        List<Opportunity> opps = opportunityMapper.selectList(new LambdaQueryWrapper<Opportunity>()
                .eq(Opportunity::getStatus, "open"));
        double forecast = opps.stream()
                .filter(o -> visibleIds.contains(o.getCustomerId()))
                .mapToDouble(o -> o.getEstAmount().doubleValue() * o.getWinProb().doubleValue())
                .sum();

        String[] order = {"线索", "跟进", "商机", "成交", "在租", "流失"};
        Map<String, List<Customer>> byPhase = new LinkedHashMap<>();
        for (String p : order) {
            byPhase.put(p, new ArrayList<>());
        }
        for (Customer c : all) {
            byPhase.computeIfAbsent(c.getPhase(), k -> new ArrayList<>()).add(c);
        }

        List<PipelineResponse.Column> columns = new ArrayList<>();
        for (String p : order) {
            List<Customer> list = byPhase.get(p);
            PipelineResponse.Column col = new PipelineResponse.Column();
            col.setPhase(p);
            col.setCount(list.size());
            List<PipelineResponse.Card> cards = new ArrayList<>();
            for (Customer c : list) {
                PipelineResponse.Card card = new PipelineResponse.Card();
                card.setCustomerId(c.getId());
                card.setName(c.getName());
                card.setOwnerName(resolveUserName(c.getOwnerUser()));
                card.setAmount(seeCost ? pickAmount(c) : null);
                card.setTag(c.getValueTier() != null ? c.getValueTier() : c.getPhase());
                cards.add(card);
            }
            col.setCards(cards);
            columns.add(col);
        }

        PipelineResponse resp = new PipelineResponse();
        resp.setWeightedForecast(seeCost ? money(forecast) : null);
        resp.setColumns(columns);
        return resp;
    }

    // ============ 客户详情 ============

    public CustomerDetailResponse detail(Long id) {
        Customer c = load(id);
        CurrentUser me = UserContext.require();
        boolean seeCost = DataScope.canSeeCost(me.getRole());
        // 行级:业务不可越权看别人非公海客户
        if (DataScope.isOwnerScoped(me.getRole())
                && c.getOwnerUser() != null && !c.getOwnerUser().equals(me.getUserId())) {
            throw new BizException(403, "无权限:该客户属其他业务名下,不在你的可见域");
        }

        CustomerDetailResponse r = new CustomerDetailResponse();
        r.setId(c.getId());
        r.setName(c.getName());
        r.setContact(c.getContact());
        r.setPhone(c.getPhone());
        r.setIndustry(c.getIndustry());
        r.setPhase(c.getPhase());
        r.setValueTier(c.getValueTier());
        r.setOwnerName(resolveUserName(c.getOwnerUser()));
        r.setSensitiveMasked(!seeCost);

        // 信用画像 + 评级
        if (c.getScoreProfit() != null) {
            CustomerDetailResponse.CreditProfile cp = new CustomerDetailResponse.CreditProfile();
            cp.setProfit(c.getScoreProfit());
            cp.setCashflow(c.getScoreCashflow());
            cp.setStability(c.getScoreStability());
            cp.setHistory(c.getScoreHistory());
            cp.setIndustry(c.getScoreIndustry());
            cp.setCompositeScore(compositeScore(c));
            cp.setRating(rating(c));
            r.setCreditProfile(cp);
        }

        // 价值 & 敞口
        CustomerDetailResponse.ValueExposure ve = new CustomerDetailResponse.ValueExposure();
        ve.setContractCount(c.getContractCount());
        ve.setCumulativeRent(c.getCumulativeRent());
        ve.setCumulativeProfit(seeCost ? c.getCumulativeProfit() : null);
        ve.setRenewRate(c.getRenewRate());
        ve.setExposureAmount(c.getExposureAmount());
        ve.setReceivableOverdue(c.getReceivableOverdue());
        ve.setConcentration(concentration(c));
        r.setValueExposure(ve);

        // 跟进时间线
        List<CustomerFollowup> fus = followupMapper.selectList(new LambdaQueryWrapper<CustomerFollowup>()
                .eq(CustomerFollowup::getCustomerId, id)
                .orderByDesc(CustomerFollowup::getFollowTime));
        List<CustomerDetailResponse.FollowupItem> timeline = new ArrayList<>();
        for (CustomerFollowup f : fus) {
            CustomerDetailResponse.FollowupItem fi = new CustomerDetailResponse.FollowupItem();
            fi.setMethod(f.getMethod());
            fi.setContent(f.getContent());
            fi.setResult(f.getResult());
            fi.setUserName(f.getUserName());
            fi.setFollowTime(f.getFollowTime());
            fi.setNextFollowDate(f.getNextFollowDate());
            timeline.add(fi);
        }
        r.setTimeline(timeline);

        // 风控准入结论(建议 + 已落定)
        CustomerDetailResponse.Admission ad = new CustomerDetailResponse.Admission();
        String rt = rating(c);
        if (rt != null) {
            JsonNode m = readJson("customer_admission_matrix").path(rt);
            if (!m.isMissingNode()) {
                ad.setSuggestCreditLimit(seeCost ? money(m.path("credit").asDouble()) : null);
                ad.setSuggestDepositMonths(BigDecimal.valueOf(m.path("deposit").asDouble()));
                ad.setSuggestTargetIrr(seeCost ? rate(m.path("irr").asDouble()) : null);
            }
        }
        ad.setApprovedCreditLimit(seeCost ? c.getCreditLimit() : null);
        ad.setApprovedDepositMonths(c.getDepositMonths());
        ad.setApprovedTargetIrr(seeCost ? c.getTargetIrr() : null);
        ad.setNote(c.getAdmissionNote());
        r.setAdmission(ad);
        return r;
    }

    // ============ 新增 / 编辑 ============

    @Transactional
    public Long create(CustomerSaveRequest req) {
        Customer c = new Customer();
        applySave(c, req);
        c.setPhase(normalizePhase(req.getPhase(), "线索"));
        customerMapper.insert(c);
        return c.getId();
    }

    @Transactional
    public void update(Long id, CustomerSaveRequest req) {
        Customer c = load(id);
        applySave(c, req);
        if (req.getPhase() != null && !req.getPhase().isEmpty()) {
            c.setPhase(normalizePhase(req.getPhase(), c.getPhase()));
        }
        customerMapper.updateById(c);
    }

    private void applySave(Customer c, CustomerSaveRequest req) {
        c.setName(req.getName().trim());
        c.setContact(req.getContact());
        c.setPhone(req.getPhone());
        c.setIndustry(req.getIndustry());
        c.setValueTier(req.getValueTier());
        c.setOwnerUser(req.getOwnerUser());
        c.setScoreProfit(req.getScoreProfit());
        c.setScoreCashflow(req.getScoreCashflow());
        c.setScoreStability(req.getScoreStability());
        c.setScoreHistory(req.getScoreHistory());
        c.setScoreIndustry(req.getScoreIndustry());
    }

    // ============ 记一次跟进 ============

    @Transactional
    public Long addFollowup(Long customerId, FollowupRequest req) {
        Customer c = load(customerId);
        CurrentUser me = UserContext.require();

        CustomerFollowup f = new CustomerFollowup();
        f.setCustomerId(customerId);
        f.setUserId(me.getUserId());
        f.setUserName(me.getUserName());
        f.setMethod(req.getMethod() == null || req.getMethod().isEmpty() ? "电话" : req.getMethod());
        f.setContent(req.getContent());
        f.setResult(req.getResult());
        f.setFollowTime(LocalDateTime.now());
        f.setNextFollowDate(req.getNextFollowDate());
        followupMapper.insert(f);

        // 同步客户 next_follow_date(followup 是该派生字段的写手)
        c.setNextFollowDate(req.getNextFollowDate());
        customerMapper.updateById(c);
        return f.getId();
    }

    // ============ 风控准入结论 ============

    @Transactional
    public CustomerDetailResponse.Admission admission(Long customerId, AdmissionRequest req) {
        Customer c = load(customerId);
        String rt = rating(c);
        if (rt == null) {
            throw new BizException(400, "客户信用画像未评分,无法出准入结论(先补五维评分)");
        }
        JsonNode m = readJson("customer_admission_matrix").path(rt);
        if (m.isMissingNode()) {
            throw new BizException(500, "准入矩阵缺评级档: " + rt);
        }
        BigDecimal suggestCredit = money(m.path("credit").asDouble());
        BigDecimal suggestDeposit = BigDecimal.valueOf(m.path("deposit").asDouble());
        BigDecimal suggestIrr = rate(m.path("irr").asDouble());

        boolean approved = req.getApproved() == null || req.getApproved();
        if (approved) {
            c.setCreditLimit(req.getCreditLimit() != null ? req.getCreditLimit() : suggestCredit);
            c.setDepositMonths(req.getDepositMonths() != null ? req.getDepositMonths() : suggestDeposit);
            c.setTargetIrr(req.getTargetIrr() != null ? req.getTargetIrr() : suggestIrr);
            c.setAdmissionNote(req.getNote() != null ? req.getNote() : ("准入通过·评级" + rt));
        } else {
            // 拒绝:不落授信,只留痕
            c.setAdmissionNote("准入拒绝·评级" + rt + (req.getNote() != null ? "·" + req.getNote() : ""));
        }
        customerMapper.updateById(c);
        log.info("风控准入: customerId={}, rating={}, approved={}, by={}", customerId, rt, approved, UserContext.getUserId());

        boolean seeCost = DataScope.canSeeCost(UserContext.getRole());
        CustomerDetailResponse.Admission ad = new CustomerDetailResponse.Admission();
        ad.setSuggestCreditLimit(seeCost ? suggestCredit : null);
        ad.setSuggestDepositMonths(suggestDeposit);
        ad.setSuggestTargetIrr(seeCost ? suggestIrr : null);
        ad.setApprovedCreditLimit(seeCost ? c.getCreditLimit() : null);
        ad.setApprovedDepositMonths(c.getDepositMonths());
        ad.setApprovedTargetIrr(seeCost ? c.getTargetIrr() : null);
        ad.setNote(c.getAdmissionNote());
        return ad;
    }

    // ============ 派生计算 ============

    /** 加权信用分(即时算):五维齐全才算,缺任一→null。 */
    private Integer compositeScore(Customer c) {
        if (c.getScoreProfit() == null || c.getScoreCashflow() == null || c.getScoreStability() == null
                || c.getScoreHistory() == null || c.getScoreIndustry() == null) {
            return null;
        }
        JsonNode w = readJson("customer_credit_weights");
        double v = c.getScoreProfit() * w.path("profit").asDouble()
                + c.getScoreCashflow() * w.path("cashflow").asDouble()
                + c.getScoreStability() * w.path("stability").asDouble()
                + c.getScoreHistory() * w.path("history").asDouble()
                + c.getScoreIndustry() * w.path("industry").asDouble();
        return (int) Math.round(v);
    }

    /** 评级 A/B/C(即时算):加权信用分落 rule_config 阶梯。 */
    private String rating(Customer c) {
        Integer score = compositeScore(c);
        if (score == null) {
            return null;
        }
        JsonNode ladder = readJson("customer_rating_ladder");
        for (JsonNode step : ladder) {
            if (score >= step.path("min").asInt()) {
                return step.path("rating").asText();
            }
        }
        return "C";
    }

    /** 集中度(即时算):本客户在租敞口 / 全量在租敞口。 */
    private BigDecimal concentration(Customer c) {
        if (c.getExposureAmount() == null || c.getExposureAmount().signum() == 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        List<Customer> all = customerMapper.selectList(new LambdaQueryWrapper<>());
        double totalExposure = all.stream()
                .map(Customer::getExposureAmount)
                .filter(x -> x != null)
                .mapToDouble(BigDecimal::doubleValue).sum();
        if (totalExposure <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(c.getExposureAmount().doubleValue() / totalExposure)
                .setScale(4, RoundingMode.HALF_UP);
    }

    /** 列表金额:在租看敞口,否则看名下 open 商机额合计。 */
    private BigDecimal pickAmount(Customer c) {
        if (c.getExposureAmount() != null && c.getExposureAmount().signum() > 0) {
            return c.getExposureAmount();
        }
        List<Opportunity> opps = opportunityMapper.selectList(new LambdaQueryWrapper<Opportunity>()
                .eq(Opportunity::getCustomerId, c.getId())
                .eq(Opportunity::getStatus, "open"));
        double sum = opps.stream().mapToDouble(o -> o.getEstAmount().doubleValue()).sum();
        return sum > 0 ? money(sum) : null;
    }

    private String followStatus(LocalDate next) {
        if (next == null) {
            return "无";
        }
        LocalDate today = LocalDate.now();
        if (next.isBefore(today)) {
            return "逾期";
        }
        if (next.isEqual(today)) {
            return "今天";
        }
        if (next.isEqual(today.plusDays(1))) {
            return "明天";
        }
        return "正常";
    }

    // ============ 工具 ============

    private Customer load(Long id) {
        Customer c = customerMapper.selectById(id);
        if (c == null || Integer.valueOf(1).equals(c.getIsDeleted())) {
            throw new BizException(404, "客户不存在: id=" + id);
        }
        return c;
    }

    private JsonNode readJson(String ruleKey) {
        try {
            return objectMapper.readTree(rules.getJson(ruleKey, "", LocalDate.now()));
        } catch (Exception e) {
            throw new BizException(500, "规则 JSON 解析失败: " + ruleKey + " · " + e.getMessage());
        }
    }

    private String normalizePhase(String phase, String fallback) {
        if (phase == null || phase.trim().isEmpty()) {
            return fallback;
        }
        String t = phase.trim();
        if (!VALID_PHASE.contains(t)) {
            throw new BizException(400, "非法阶段: " + t + ",应为 线索/跟进/商机/成交/在租/流失");
        }
        return t;
    }

    /** 占位期 user 主键 → 显示名(复用 UserContextFilter 种子;未知返回主键串)。 */
    private String resolveUserName(Long userId) {
        if (userId == null) {
            return "公海";
        }
        String name = SEED_NAMES.get(userId);
        return name != null ? name : ("用户#" + userId);
    }

    private static final Map<Long, String> SEED_NAMES = new LinkedHashMap<>();
    static {
        SEED_NAMES.put(1001L, "老板");
        SEED_NAMES.put(1002L, "刘总");
        SEED_NAMES.put(1003L, "小洪");
        SEED_NAMES.put(1004L, "李工");
        SEED_NAMES.put(1005L, "财务");
        SEED_NAMES.put(1006L, "供应链");
        SEED_NAMES.put(1007L, "业务");
    }

    private BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal rate(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }
}
