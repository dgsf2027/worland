package top.aole.rent.modules.transfer.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.domain.ContractAsset;
import top.aole.rent.modules.contract.mapper.ContractAssetMapper;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.contract.service.ContractService;
import top.aole.rent.modules.finance.service.VoucherService;
import top.aole.rent.modules.rule.service.RuleConfigService;
import top.aole.rent.modules.transfer.domain.TransferOrder;
import top.aole.rent.modules.transfer.domain.TransferOrderLine;
import top.aole.rent.modules.transfer.dto.TransferDtos;
import top.aole.rent.modules.transfer.mapper.TransferOrderLineMapper;
import top.aole.rent.modules.transfer.mapper.TransferOrderMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 转让/处置服务(M4-01/02/03 · P0-A 拆头+逐台行)。
 *
 * <p><b>到期转让</b>:挂合同 N 台 → 逐台账面快照(不回写)→ gain=transfer_price-book_value →
 * 残值凭证(billing.postResidual·并入分期收款销售计税 + 经营口径处置损益)+ 资产出账(→已转让)→ 合同关闭。
 * <p><b>名义价硬阈值守卫(P1-19)</b>:transfer_price&lt;book_value 或 &lt;市场价×下限(rule_config
 * {@code nominal_price_floor_rate})→ need_approval=1 + status=待审批 + 强制录理由,财务/老板审批通过才过账
 * (非仅亮灯)。
 * <p><b>复投飞轮(M4-03)</b>:收回待处置 → 再投放(回在租池·走 asset deliver)/ 二手(转让出账)/ 报废(损益结转)。
 * <p><b>单一真相源</b>:设备状态机归 {@link AssetService};合同状态归 {@link ContractService};
 * 凭证/账套归 {@link VoucherService};本服务只拥有 transfer_order(头+行)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final TransferOrderMapper orderMapper;
    private final TransferOrderLineMapper lineMapper;
    private final ContractMapper contractMapper;
    private final ContractAssetMapper contractAssetMapper;
    private final ContractService contractService;
    private final AssetService assetService;
    private final VoucherService voucherService;
    private final RuleConfigService rules;
    private final AuditLogService auditLogService;

    // ============ M4-01/02 到期转让 ============

    /**
     * 到期转让:挂合同在租设备逐台建行(账面快照 + 名义价守卫),未触发守卫立即过账;触发则待审批。
     */
    @Transactional
    public TransferDtos.TransferResult createExpiryTransfer(TransferDtos.ExpiryTransferRequest req) {
        if (req == null || req.getContractId() == null) {
            throw new BizException(400, "到期转让需指定 contractId");
        }
        Contract c = contractService.requireTransferable(req.getContractId());

        // 目标设备:req.lines 指定则用之,否则默认取合同全部在租设备
        Map<Long, BigDecimal> priceByAsset = new HashMap<>();
        List<Long> assetIds = new ArrayList<>();
        if (req.getLines() != null && !req.getLines().isEmpty()) {
            for (TransferDtos.LineInput li : req.getLines()) {
                if (li.getAssetId() == null) {
                    continue;
                }
                assetIds.add(li.getAssetId());
                priceByAsset.put(li.getAssetId(), li.getTransferPrice());
            }
        } else {
            List<ContractAsset> links = contractAssetMapper.selectList(new LambdaQueryWrapper<ContractAsset>()
                    .eq(ContractAsset::getContractId, c.getId()));
            for (ContractAsset ca : links) {
                Asset a = assetService.requireAsset(ca.getAssetId());
                if ("在租".equals(a.getStatus()) || "待转让".equals(a.getStatus())) {
                    assetIds.add(ca.getAssetId());
                }
            }
        }
        if (assetIds.isEmpty()) {
            throw new BizException(400, "合同 " + c.getNo() + " 无可转让在租设备");
        }

        // 默认转让价:endTransferPrice 按台均摊;缺则逐台残值(市场价×品类转让率)
        BigDecimal defaultEach = null;
        if (c.getEndTransferPrice() != null && c.getEndTransferPrice().signum() > 0) {
            defaultEach = c.getEndTransferPrice().divide(BigDecimal.valueOf(assetIds.size()), 2, RoundingMode.HALF_UP);
        }

        TransferOrder order = new TransferOrder();
        order.setNo(genNo("TR-" + c.getNo()));
        order.setContractId(c.getId());
        order.setType("转让");
        order.setStatus("待过账");
        order.setNeedApproval(0);
        order.setApprovalReason(req.getApprovalReason());
        order.setOperatorId(currentUserId());
        order.setBizTime(LocalDateTime.now());
        order.setRemark(req.getRemark());
        orderMapper.insert(order);

        List<String> nominalHits = new ArrayList<>();
        int count = 0;
        for (Long assetId : assetIds) {
            Asset a = assetService.requireAsset(assetId);
            BigDecimal price = priceByAsset.get(assetId);
            if (price == null) {
                price = defaultEach != null ? defaultEach : residualValue(a);
            }
            if (price == null) {
                price = BigDecimal.ZERO;
            }
            BigDecimal book = assetService.snapshotBookValue(assetId);
            BigDecimal gain = price.subtract(book == null ? BigDecimal.ZERO : book).setScale(2, RoundingMode.HALF_UP);
            boolean nominal = isNominal(price, book, a.getMarketPrice());

            TransferOrderLine line = new TransferOrderLine();
            line.setTransferOrderId(order.getId());
            line.setAssetId(assetId);
            line.setBookValue(book);
            line.setTransferPrice(price);
            line.setGain(gain);
            line.setNominalFlag(nominal ? 1 : 0);
            lineMapper.insert(line);
            if (nominal) {
                nominalHits.add(a.getSerialNo() + "(转让价" + price + " vs 账面" + book
                        + "/市场×下限" + floorPrice(a.getMarketPrice()) + ")");
            }
            count++;
        }

        boolean needApproval = !nominalHits.isEmpty();
        order.setAssetCount(count);
        order.setNeedApproval(needApproval ? 1 : 0);

        TransferDtos.TransferResult r = new TransferDtos.TransferResult();
        r.setTransferOrderId(order.getId());
        r.setNo(order.getNo());
        r.setType(order.getType());
        r.setNominalGuardHits(nominalHits);

        if (needApproval) {
            // 名义价守卫:强制升级审批 + 记录理由(非仅亮灯)
            if (req.getApprovalReason() == null || req.getApprovalReason().trim().isEmpty()) {
                throw new BizException(400, "名义价守卫触发(" + nominalHits.size()
                        + " 台低于账面价/市场价下限),须录低价转让理由(approvalReason)并走财务/老板审批(P1-19)");
            }
            order.setStatus("待审批");
            orderMapper.updateById(order);
            auditLogService.record("到期转让·名义价守卫", "transfer_order", order.getId(),
                    AuditLogService.EXECUTED,
                    "转让单 " + order.getNo() + " 触发名义价守卫 " + nominalHits.size() + " 台,待审批 · " + req.getApprovalReason());
            r.setStatus("待审批");
            r.setNeedApproval(true);
            List<String> impact = new ArrayList<>();
            impact.add("名义价守卫拦截:" + nominalHits.size() + " 台转让价低于账面价/市场价下限,强制升级审批(P1-19)");
            impact.add("已录理由:" + req.getApprovalReason() + " → 待财务/老板 /approve 后过账");
            r.setImpact(impact);
            log.info("[到期转让] 单 {} 名义价守卫触发 {} 台,待审批", order.getNo(), nominalHits.size());
            return r;
        }

        // 未触发守卫:立即过账
        orderMapper.updateById(order);
        List<String> impact = postOrder(order);
        r.setStatus("已完成");
        r.setNeedApproval(false);
        r.setTotalPrice(order.getTotalPrice());
        r.setTotalGain(order.getTotalGain());
        r.setAssetCount(order.getAssetCount());
        r.setImpact(impact);
        return r;
    }

    /**
     * 审批通过 → 过账(财务/老板)。待审批单校验,记审批人,执行 postOrder。
     */
    @Transactional
    public TransferDtos.TransferDetail approve(Long id, TransferDtos.ApproveRequest req) {
        TransferOrder order = load(id);
        if (!"待审批".equals(order.getStatus())) {
            throw new BizException(400, "转让单当前 " + order.getStatus() + " 非待审批,不可审批过账");
        }
        order.setApprovedBy(currentUserId());
        order.setApprovedAt(LocalDateTime.now());
        if (req != null && req.getReason() != null) {
            order.setApprovalReason(appendRemark(order.getApprovalReason(), "审批:" + req.getReason()));
        }
        orderMapper.updateById(order);
        List<String> impact = postOrder(order);
        auditLogService.record("到期转让·审批过账", "transfer_order", id, AuditLogService.EXECUTED,
                "转让单 " + order.getNo() + " 审批通过并过账 · " + impact.size() + " 项影响");
        log.info("[到期转让] 单 {} 审批过账 by {}", order.getNo(), currentUserId());
        return detail(id);
    }

    /**
     * 过账内核:逐台残值凭证 + 资产出账(→已转让/报废)+ 聚合总额 + 合同关闭。返回影响清单。
     * 幂等:已完成单直接返回(postResidual/markTransferred 各自幂等/状态校验兜底)。
     */
    private List<String> postOrder(TransferOrder order) {
        List<String> impact = new ArrayList<>();
        List<TransferOrderLine> lines = lineMapper.selectList(new LambdaQueryWrapper<TransferOrderLine>()
                .eq(TransferOrderLine::getTransferOrderId, order.getId()).orderByAsc(TransferOrderLine::getId));
        BigDecimal totalPrice = BigDecimal.ZERO, totalGain = BigDecimal.ZERO;
        boolean scrap = "报废".equals(order.getType());
        String eventType = "二手".equals(order.getType()) ? "二手" : "转让";
        for (TransferOrderLine line : lines) {
            Asset a = assetService.requireAsset(line.getAssetId());
            // 残值/处置凭证(双账),回填 line.voucher_id
            Long voucherId = voucherService.postResidual(line.getId(), line.getTransferPrice(),
                    line.getBookValue(), LocalDate.now());
            line.setVoucherId(voucherId);
            lineMapper.updateById(line);
            // 资产出账
            if (scrap) {
                assetService.markScrapped(line.getAssetId(), order.getId(), "处置报废·转让单" + order.getNo());
                impact.add(a.getSerialNo() + " 报废出账 · 损益" + line.getGain() + " · 凭证#" + voucherId);
            } else {
                assetService.markTransferred(line.getAssetId(), order.getId(), eventType,
                        order.getType() + "出账·转让单" + order.getNo());
                impact.add(a.getSerialNo() + " → 已转让 · 转让价" + line.getTransferPrice()
                        + " 账面" + line.getBookValue() + " 损益" + line.getGain() + " · 凭证#" + voucherId);
            }
            totalPrice = totalPrice.add(line.getTransferPrice() == null ? BigDecimal.ZERO : line.getTransferPrice());
            totalGain = totalGain.add(line.getGain() == null ? BigDecimal.ZERO : line.getGain());
        }
        order.setTotalPrice(totalPrice.setScale(2, RoundingMode.HALF_UP));
        order.setTotalGain(totalGain.setScale(2, RoundingMode.HALF_UP));
        order.setStatus("已完成");
        orderMapper.updateById(order);
        impact.add("合计:转让价" + order.getTotalPrice() + " 处置损益" + order.getTotalGain()
                + "(逐台残值凭证已生成·并入分期收款销售计税)");

        // 到期转让 → 合同关闭(合同状态 owner=ContractService)
        if (order.getContractId() != null && "转让".equals(order.getType())) {
            contractService.closeOnTransfer(order.getContractId(), order.getNo());
            impact.add("合同 " + contractNo(order.getContractId()) + " → 到期转让(关闭)");
        }
        return impact;
    }

    // ============ M4-03 复投飞轮:收回待处置 → 再投放/二手/报废 ============

    /**
     * 单台处置(复投飞轮)。仅收回待处置设备可处置流转:
     * <ul>
     *   <li>再投放:走 {@link AssetService#deliver}(收回待处置→投放·回在租池,老板审批切面已卡);</li>
     *   <li>二手:建 type=二手 转让单 → 残值凭证 + 资产出账(→已转让);</li>
     *   <li>报废:建 type=报废 转让单 → 处置损益凭证 + 资产报废。</li>
     * </ul>
     */
    @Transactional
    public TransferDtos.TransferResult dispose(TransferDtos.DisposeRequest req) {
        if (req == null || req.getAssetId() == null || req.getAction() == null) {
            throw new BizException(400, "处置需指定 assetId 与 action(再投放/二手/报废)");
        }
        Asset a = assetService.requireAsset(req.getAssetId());
        String action = req.getAction().trim();

        if ("再投放".equals(action)) {
            assetService.deliver(req.getAssetId(), req.getRemark() == null ? "复投飞轮·再投放回在租池" : req.getRemark());
            TransferDtos.TransferResult r = new TransferDtos.TransferResult();
            r.setType("再投放");
            r.setStatus("已完成");
            r.setAssetCount(1);
            List<String> impact = new ArrayList<>();
            impact.add(a.getSerialNo() + " 收回待处置 → 投放(再投放·回在租池)");
            r.setImpact(impact);
            log.info("[复投飞轮] {} 再投放", a.getSerialNo());
            return r;
        }

        if (!"收回待处置".equals(a.getStatus())) {
            throw new BizException(400, "设备 " + a.getSerialNo() + " 当前 " + a.getStatus()
                    + " 不可" + action + "处置(需 收回待处置)");
        }
        boolean scrap = "报废".equals(action);
        if (!scrap && !"二手".equals(action)) {
            throw new BizException(400, "非法处置动作: " + action + "(允许 再投放/二手/报废)");
        }

        BigDecimal price = scrap ? BigDecimal.ZERO
                : (req.getTransferPrice() != null ? req.getTransferPrice() : residualValueOrZero(a));
        BigDecimal book = assetService.snapshotBookValue(req.getAssetId());
        BigDecimal gain = price.subtract(book == null ? BigDecimal.ZERO : book).setScale(2, RoundingMode.HALF_UP);
        boolean nominal = !scrap && isNominal(price, book, a.getMarketPrice());

        TransferOrder order = new TransferOrder();
        order.setNo(genNo("TR-" + action + "-A" + req.getAssetId()));
        order.setContractId(null);
        order.setType(action);
        order.setAssetCount(1);
        order.setStatus("待过账");
        order.setNeedApproval(nominal ? 1 : 0);
        order.setApprovalReason(req.getApprovalReason());
        order.setOperatorId(currentUserId());
        order.setBizTime(LocalDateTime.now());
        order.setRemark(req.getRemark());
        orderMapper.insert(order);

        TransferOrderLine line = new TransferOrderLine();
        line.setTransferOrderId(order.getId());
        line.setAssetId(req.getAssetId());
        line.setBookValue(book);
        line.setTransferPrice(price);
        line.setGain(gain);
        line.setNominalFlag(nominal ? 1 : 0);
        lineMapper.insert(line);

        TransferDtos.TransferResult r = new TransferDtos.TransferResult();
        r.setTransferOrderId(order.getId());
        r.setNo(order.getNo());
        r.setType(action);
        List<String> hits = new ArrayList<>();
        if (nominal) {
            hits.add(a.getSerialNo() + "(转让价" + price + " vs 账面" + book + "/市场×下限" + floorPrice(a.getMarketPrice()) + ")");
            if (req.getApprovalReason() == null || req.getApprovalReason().trim().isEmpty()) {
                throw new BizException(400, "二手处置名义价守卫触发,须录理由(approvalReason)并走审批(P1-19)");
            }
            order.setStatus("待审批");
            orderMapper.updateById(order);
            r.setStatus("待审批");
            r.setNeedApproval(true);
            r.setNominalGuardHits(hits);
            List<String> guardImpact = new ArrayList<>();
            guardImpact.add("名义价守卫拦截·待审批 /approve");
            r.setImpact(guardImpact);
            return r;
        }
        r.setNominalGuardHits(hits);
        List<String> impact = postOrder(order);
        r.setStatus("已完成");
        r.setNeedApproval(false);
        r.setTotalPrice(order.getTotalPrice());
        r.setTotalGain(order.getTotalGain());
        r.setAssetCount(1);
        r.setImpact(impact);
        return r;
    }

    // ============ 列表 / 详情 ============

    public PageResult<TransferDtos.TransferItem> list(String type, String status, Long contractId, int page, int size) {
        LambdaQueryWrapper<TransferOrder> qw = new LambdaQueryWrapper<TransferOrder>()
                .eq(type != null && !type.isEmpty(), TransferOrder::getType, type)
                .eq(status != null && !status.isEmpty(), TransferOrder::getStatus, status)
                .eq(contractId != null, TransferOrder::getContractId, contractId)
                .orderByDesc(TransferOrder::getId);
        List<TransferOrder> all = orderMapper.selectList(qw);
        List<TransferDtos.TransferItem> items = new ArrayList<>();
        for (TransferOrder o : all) {
            items.add(toItem(o));
        }
        long total = items.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(items.size(), from + size);
        List<TransferDtos.TransferItem> records = from >= items.size() ? new ArrayList<>() : items.subList(from, to);
        return new PageResult<>(total, page, size, records);
    }

    public TransferDtos.TransferDetail detail(Long id) {
        TransferOrder o = load(id);
        TransferDtos.TransferDetail d = new TransferDtos.TransferDetail();
        d.setOrder(toItem(o));
        List<TransferOrderLine> lines = lineMapper.selectList(new LambdaQueryWrapper<TransferOrderLine>()
                .eq(TransferOrderLine::getTransferOrderId, id).orderByAsc(TransferOrderLine::getId));
        List<TransferDtos.TransferLineItem> lis = new ArrayList<>();
        for (TransferOrderLine l : lines) {
            Asset a = assetService.requireAsset(l.getAssetId());
            TransferDtos.TransferLineItem li = new TransferDtos.TransferLineItem();
            li.setId(l.getId());
            li.setAssetId(l.getAssetId());
            li.setSerialNo(a.getSerialNo());
            li.setCategory(a.getCategory());
            li.setBookValue(l.getBookValue());
            li.setMarketPrice(a.getMarketPrice());
            li.setTransferPrice(l.getTransferPrice());
            li.setGain(l.getGain());
            li.setNominalFlag(Integer.valueOf(1).equals(l.getNominalFlag()));
            li.setVoucherId(l.getVoucherId());
            li.setRemark(l.getRemark());
            lis.add(li);
        }
        d.setLines(lis);
        return d;
    }

    // ============ 工具 ============

    private TransferDtos.TransferItem toItem(TransferOrder o) {
        TransferDtos.TransferItem it = new TransferDtos.TransferItem();
        it.setId(o.getId());
        it.setNo(o.getNo());
        it.setContractId(o.getContractId());
        it.setContractNo(contractNo(o.getContractId()));
        it.setType(o.getType());
        it.setAssetCount(o.getAssetCount());
        it.setTotalPrice(o.getTotalPrice());
        it.setTotalGain(o.getTotalGain());
        it.setStatus(o.getStatus());
        it.setNeedApproval(Integer.valueOf(1).equals(o.getNeedApproval()));
        it.setApprovalReason(o.getApprovalReason());
        it.setApprovedByName(userName(o.getApprovedBy()));
        it.setApprovedAt(o.getApprovedAt());
        it.setBizTime(o.getBizTime());
        it.setRemark(o.getRemark());
        return it;
    }

    /** 名义价守卫判定:转让价 &lt; 账面价 或 转让价 &lt; 市场价×下限。 */
    private boolean isNominal(BigDecimal price, BigDecimal book, BigDecimal marketPrice) {
        if (price == null) {
            return true;
        }
        if (book != null && price.compareTo(book) < 0) {
            return true;
        }
        BigDecimal floor = floorPrice(marketPrice);
        return floor != null && price.compareTo(floor) < 0;
    }

    /** 市场价下限 = 市场价 × nominal_price_floor_rate(rule_config)。缺市场价/配置 → null(该分支不判)。 */
    private BigDecimal floorPrice(BigDecimal marketPrice) {
        if (marketPrice == null) {
            return null;
        }
        BigDecimal rate = safeValue("nominal_price_floor_rate", "");
        if (rate == null) {
            return null;
        }
        return marketPrice.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /** 残值 = 市场价 × 品类转让率;缺则 null。 */
    private BigDecimal residualValue(Asset a) {
        if (a.getMarketPrice() == null) {
            return null;
        }
        BigDecimal rate = safeValue("transfer_rate", a.getCategory());
        if (rate == null) {
            return null;
        }
        return a.getMarketPrice().multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal residualValueOrZero(Asset a) {
        BigDecimal v = residualValue(a);
        return v != null ? v : BigDecimal.ZERO;
    }

    private String genNo(String prefix) {
        String base = prefix + "-" + (System.currentTimeMillis() % 1000000);
        String no = base;
        int n = 1;
        while (orderMapper.selectCount(new LambdaQueryWrapper<TransferOrder>().eq(TransferOrder::getNo, no)) > 0) {
            no = base + "-" + (++n);
        }
        return no;
    }

    private TransferOrder load(Long id) {
        TransferOrder o = orderMapper.selectById(id);
        if (o == null || Integer.valueOf(1).equals(o.getIsDeleted())) {
            throw new BizException(404, "转让单不存在: id=" + id);
        }
        return o;
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

    private Long currentUserId() {
        return UserContext.get() != null ? UserContext.get().getUserId() : null;
    }

    private String appendRemark(String base, String add) {
        if (add == null) {
            return base;
        }
        return (base == null || base.isEmpty()) ? add : base + " | " + add;
    }

    private static final Map<Long, String> SEED_NAMES = new HashMap<>();
    static {
        SEED_NAMES.put(1001L, "老板");
        SEED_NAMES.put(1005L, "财务");
    }

    private String userName(Long userId) {
        if (userId == null) {
            return null;
        }
        return SEED_NAMES.getOrDefault(userId, "用户#" + userId);
    }
}
