package top.aole.rent.modules.purchase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.asset.dto.PaymentTermDtos;
import top.aole.rent.modules.asset.service.AssetPaymentService;
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.billing.dto.RentCoverageDto;
import top.aole.rent.modules.billing.service.RentCoverageService;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.finance.service.VoucherService;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.domain.PurchaseIn;
import top.aole.rent.modules.purchase.domain.PurchaseItem;
import top.aole.rent.modules.purchase.dto.PurchaseDetailResponse;
import top.aole.rent.modules.purchase.dto.PurchaseEditDtos;
import top.aole.rent.modules.purchase.dto.PurchaseListItem;
import top.aole.rent.modules.purchase.dto.PurchaseOrderRequest;
import top.aole.rent.modules.purchase.dto.ReturnRequest;
import top.aole.rent.modules.purchase.mapper.PayableMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseItemMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 采购服务(M1-12/13)。下单(先签约后采购校验)→入库(逐件生成 asset)→应付计划;退货红冲(设备报废释放+应付红字)。
 *
 * <p><b>单一真相源(§4.24)</b>:
 * <ul>
 *   <li>设备状态机 owner={@link AssetService}:入库生成设备/退货报废均调其方法,本服务不直写 asset.status。</li>
 *   <li>应付计划 {@code payable} 待付=层级②"负债"口径(M3 兑付缺口扫描读此·为其留字段)。</li>
 *   <li>先签约后采购:下单必绑一份存续合同(草稿/生效),已作废合同拒绝建单。</li>
 * </ul>
 * <p>应付按设备逐台生成({@link AssetPaymentService}):每台设备的付款条件自定义多段(下单/入库触发 + 到期天数),
 * 各段合计=该设备集采价;未指定条件时默认 首付(下单)/验收(入库)/尾款(入库+账期),比例走 rule_config[payable_stage_ratio]。
 * 敏感成本(集采价/应付金额)对 GP/LP 打码。
 * <p>收租对照({@link RentCoverageService}):采购与收租都挂在同一份合同上,按 {@code contract_id} 反查
 * 「这笔货款靠哪些租金还」—— 已收/待收/逾期/下一期到期/开启中的逾期案,不新建绑定关系表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseService {

    private final PurchaseInMapper purchaseInMapper;
    private final PurchaseItemMapper purchaseItemMapper;
    private final PayableMapper payableMapper;
    private final ContractMapper contractMapper;
    private final CustomerMapper customerMapper;
    private final SupplierMapper supplierMapper;
    private final AssetMapper assetMapper;
    private final AssetService assetService;
    private final AssetPaymentService paymentService;
    private final VoucherService voucherService;
    private final RuleConfigService rules;
    private final AuditLogService auditLogService;
    private final RentCoverageService rentCoverageService;
    private final top.aole.rent.modules.contract.service.ContractPaymentService contractPaymentService;

    // ============ 列表 ============

    public PageResult<PurchaseListItem> list(String status, Long contractId, String keyword, int page, int size) {
        boolean seeCost = DataScope.canSeeCost(UserContext.getRole());
        LambdaQueryWrapper<PurchaseIn> qw = new LambdaQueryWrapper<PurchaseIn>()
                .eq(status != null && !status.isEmpty(), PurchaseIn::getStatus, status)
                .eq(contractId != null, PurchaseIn::getContractId, contractId)
                .and(keyword != null && !keyword.trim().isEmpty(), w -> w.like(PurchaseIn::getNo, keyword.trim()))
                .orderByDesc(PurchaseIn::getId);
        List<PurchaseIn> all = purchaseInMapper.selectList(qw);
        // 收租对照一次性批量查完(两条 in 查询),避免逐单反查收租单造成 N+1
        Map<Long, RentCoverageDto> coverage = rentCoverageService.byContract(all.stream()
                .map(PurchaseIn::getContractId).filter(java.util.Objects::nonNull)
                .distinct().collect(Collectors.toList()));

        List<PurchaseListItem> items = new ArrayList<>();
        for (PurchaseIn p : all) {
            PurchaseListItem it = new PurchaseListItem();
            it.setId(p.getId());
            it.setNo(p.getNo());
            it.setStatus(p.getStatus());
            it.setContractId(p.getContractId());
            it.setContractNo(contractNo(p.getContractId()));
            it.setCustomerName(customerNameOfContract(p.getContractId()));
            it.setSupplierId(p.getSupplierId());
            it.setSupplierName(supplierName(p.getSupplierId()));
            it.setTotalAmount(seeCost ? p.getTotalAmount() : null);
            it.setItemCount(itemsOf(p.getId()).size());
            it.setOrderDate(p.getOrderDate());
            it.setReceiveDate(p.getReceiveDate());
            it.setPayableOutstanding(seeCost ? outstanding(p.getId()) : null);
            RentCoverageDto rc = coverage.get(p.getContractId());
            if (rc != null) {
                it.setRentCollected(rc.getCollectedAmount());
                it.setRentOverdueAmount(rc.getOverdueAmount());
                it.setRentOverdueCount(rc.getOverdueCount());
            }
            it.setSensitiveMasked(!seeCost);
            items.add(it);
        }
        long total = items.size();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(items.size(), from + size);
        List<PurchaseListItem> records = from >= items.size() ? new ArrayList<>() : items.subList(from, to);
        return new PageResult<>(total, page, size, records);
    }

    // ============ 下单(先签约后采购) ============

    @Transactional
    public Long order(PurchaseOrderRequest req) {
        // 先签约后采购:合同必存在且未作废
        Contract c = contractMapper.selectById(req.getContractId());
        if (c == null || Integer.valueOf(1).equals(c.getIsDeleted())) {
            throw new BizException(404, "合同不存在,不允许建采购单(先签约后采购): contractId=" + req.getContractId());
        }
        if ("已作废".equals(c.getStatus())) {
            throw new BizException(400, "合同已作废,不允许建采购单: " + c.getNo());
        }
        PurchaseIn dup = purchaseInMapper.selectOne(new LambdaQueryWrapper<PurchaseIn>()
                .eq(PurchaseIn::getNo, req.getNo().trim()));
        if (dup != null) {
            throw new BizException(400, "采购单号已存在: " + req.getNo());
        }
        // 明细 = 勾选该合同下设备租赁台账里的设备(供应商/价格/付款条件都从台账带出)
        List<Asset> assets = new ArrayList<>();
        List<Long> pickedIds = new ArrayList<>();
        for (PurchaseOrderRequest.Item item : req.getItems()) {
            Long assetId = item.getAssetId();
            if (pickedIds.contains(assetId)) {
                throw new BizException(400, "同一台设备在本单里勾了多次: assetId=" + assetId);
            }
            pickedIds.add(assetId);
            Asset a = assetMapper.selectById(assetId);
            if (a == null || Integer.valueOf(1).equals(a.getIsDeleted())) {
                throw new BizException(404, "设备不存在: id=" + assetId);
            }
            if (!req.getContractId().equals(a.getContractId())) {
                throw new BizException(400, "设备「" + assetLabel(a) + "」不属于合同 " + c.getNo() + ",不能采购");
            }
            if (a.getPurchaseInId() != null) {
                PurchaseIn other = purchaseInMapper.selectById(a.getPurchaseInId());
                throw new BizException(400, "设备「" + assetLabel(a) + "」已在采购单 "
                        + (other == null ? a.getPurchaseInId() : other.getNo()) + " 里,不能重复采购");
            }
            if (a.getPurchasePrice() == null) {
                throw new BizException(400, "设备「" + assetLabel(a) + "」没有合同价,请先在合同清单里填单价");
            }
            assets.add(a);
        }

        LocalDate orderDate = req.getOrderDate() != null ? req.getOrderDate() : LocalDate.now();
        BigDecimal total = BigDecimal.ZERO;
        for (Asset a : assets) {
            total = total.add(a.getPurchasePrice());
        }
        total = total.setScale(2, RoundingMode.HALF_UP);

        // 单头供应商:入参优先;没给就取设备台账上的供应商(全单同一家时)
        Long headSupplier = req.getSupplierId();
        if (headSupplier == null) {
            List<Long> distinct = assets.stream().map(Asset::getSupplierId)
                    .filter(java.util.Objects::nonNull).distinct().collect(java.util.stream.Collectors.toList());
            headSupplier = distinct.size() == 1 ? distinct.get(0) : null;
        }

        PurchaseIn p = new PurchaseIn();
        p.setNo(req.getNo().trim());
        p.setContractId(req.getContractId());
        p.setSupplierId(headSupplier);
        p.setStatus("已下单");
        p.setTotalAmount(total);
        p.setFirstPayRatio(req.getFirstPayRatio());
        p.setAccountDays(req.getAccountDays());
        p.setOrderDate(orderDate);
        p.setExpectReceiveDate(req.getExpectReceiveDate() != null ? req.getExpectReceiveDate()
                : orderDate.plusDays(purchaseLeadDays()));
        p.setRemark(req.getRemark());
        purchaseInMapper.insert(p);

        // 付款条件:明细级 > 整单级 > 默认三段(首付取整单首付比例);逐台生成 触发=下单 的应付
        // 合同还没设付款方式时按规则写入默认三段(以合同为唯一真相源)
        contractPaymentService.ensureDefault(req.getContractId(), req.getFirstPayRatio(), req.getAccountDays());
        for (int i = 0; i < req.getItems().size(); i++) {
            PurchaseOrderRequest.Item item = req.getItems().get(i);
            Asset a = assets.get(i);
            PurchaseItem pi = new PurchaseItem();
            pi.setPurchaseInId(p.getId());
            pi.setAssetId(a.getId());
            // 快照设备台账字段(单据留痕用,页面不再展示)
            pi.setSerialNo(a.getSerialNo());
            pi.setCategory(a.getCategory());
            pi.setModel(a.getModel());
            pi.setMarketPrice(a.getMarketPrice());
            pi.setPurchasePrice(a.getPurchasePrice());
            pi.setSupplierId(a.getSupplierId() != null ? a.getSupplierId() : req.getSupplierId());
            pi.setMonthlyLaborValue(a.getMonthlyLaborValue());
            pi.setReplaceHeadcount(a.getReplaceHeadcount());
            pi.setRemark(item.getRemark());
            purchaseItemMapper.insert(pi);
            assetService.bindPurchase(a.getId(), p.getId(), p.getNo());
            // 付款条件以合同为准(V118):不再接收明细级/整单级条件,
            // 下单即按合同付款方式一次生成全部阶段的应付
            paymentService.onOrder(p, pi, a.getId(), req.getContractId());
        }

        log.info("采购下单: no={}, id={}, contract={}, 勾选设备{}台, total={}, 下单应付={}",
                p.getNo(), p.getId(), c.getNo(), assets.size(), total, outstanding(p.getId()));
        return p.getId();
    }

    // ============ 整单编辑(V120) ============

    /**
     * 编辑单头。已红冲的单只读;其余字段传什么改什么。
     *
     * <p>改预计入库日会联动重算本单「到期日为预估」的待付应付 ——
     * 不重算的话现金流驾驶舱的到期分层就和单据对不上。
     */
    @Transactional
    public void editHeader(Long id, PurchaseEditDtos.HeaderRequest req) {
        PurchaseIn p = load(id);
        requireEditable(p);
        List<String> changes = new ArrayList<>();
        String no = req.getNo() == null ? null : req.getNo().trim();
        if (no != null && !no.isEmpty() && !no.equals(p.getNo())) {
            PurchaseIn dup = purchaseInMapper.selectOne(new LambdaQueryWrapper<PurchaseIn>()
                    .eq(PurchaseIn::getNo, no));
            if (dup != null && !dup.getId().equals(id)) {
                throw new BizException(400, "采购单号已存在: " + no);
            }
            changes.add("单号 " + p.getNo() + " → " + no);
            p.setNo(no);
        }
        if (req.getSupplierId() != null && !req.getSupplierId().equals(p.getSupplierId())) {
            changes.add("供应商 " + supplierName(p.getSupplierId()) + " → " + supplierName(req.getSupplierId()));
            p.setSupplierId(req.getSupplierId());
        }
        if (req.getOrderDate() != null && !req.getOrderDate().equals(p.getOrderDate())) {
            changes.add("下单日 " + p.getOrderDate() + " → " + req.getOrderDate());
            p.setOrderDate(req.getOrderDate());
        }
        LocalDate oldExpect = p.getExpectReceiveDate();
        if (req.getExpectReceiveDate() != null && !req.getExpectReceiveDate().equals(oldExpect)) {
            changes.add("预计入库日 " + oldExpect + " → " + req.getExpectReceiveDate());
            p.setExpectReceiveDate(req.getExpectReceiveDate());
        }
        if (req.getReceiveDate() != null && "已入库".equals(p.getStatus())
                && !req.getReceiveDate().equals(p.getReceiveDate())) {
            changes.add("入库日 " + p.getReceiveDate() + " → " + req.getReceiveDate());
            p.setReceiveDate(req.getReceiveDate());
        }
        if (req.getRemark() != null) {
            p.setRemark(req.getRemark());
        }
        purchaseInMapper.updateById(p);
        int rescheduled = resyncProvisionalDueDates(p);
        auditLogService.record("采购单编辑", "purchase_in", id, AuditLogService.EXECUTED,
                (changes.isEmpty() ? "仅改备注" : String.join(" · ", changes))
                        + (rescheduled > 0 ? " · 重算预估到期 " + rescheduled + " 笔" : ""));
    }

    /**
     * 整体替换本单采购的设备。移除的设备会解除采购关系并删掉它的待付应付;
     * 已付过款的设备不允许移除(先去应付行撤销付款)。
     */
    @Transactional
    public void replaceItems(Long id, PurchaseEditDtos.ItemsRequest req) {
        PurchaseIn p = load(id);
        requireEditable(p);
        Contract c = contractMapper.selectById(p.getContractId());
        if (c == null || Integer.valueOf(1).equals(c.getIsDeleted())) {
            throw new BizException(404, "本单所属合同不存在,不能改明细");
        }
        List<Long> want = new ArrayList<>(new java.util.LinkedHashSet<>(req.getAssetIds()));
        want.removeIf(java.util.Objects::isNull);
        if (want.isEmpty()) {
            throw new BizException(400, "至少保留 1 台设备");
        }
        List<PurchaseItem> current = itemsOf(id);
        List<Long> have = current.stream().map(PurchaseItem::getAssetId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toList());

        // 移除
        int removed = 0;
        for (PurchaseItem pi : current) {
            Long assetId = pi.getAssetId();
            if (assetId != null && want.contains(assetId)) {
                continue;
            }
            if (assetId != null && hasPaid(assetId)) {
                Asset a = assetMapper.selectById(assetId);
                throw new BizException(400, "设备「" + (a == null ? "#" + assetId : assetLabel(a))
                        + "」已有付过款的应付,不能从本单移除;请先在应付行上撤销付款");
            }
            if (assetId != null) {
                for (Payable pay : payableMapper.selectList(new LambdaQueryWrapper<Payable>()
                        .eq(Payable::getAssetId, assetId).eq(Payable::getPurchaseInId, id))) {
                    payableMapper.deleteById(pay.getId());
                }
                assetService.releaseOnPurchaseReturn(assetId, id);
            }
            purchaseItemMapper.deleteById(pi.getId());
            removed++;
        }

        // 新增
        int added = 0;
        for (Long assetId : want) {
            if (have.contains(assetId)) {
                continue;
            }
            Asset a = assetMapper.selectById(assetId);
            if (a == null || Integer.valueOf(1).equals(a.getIsDeleted())) {
                throw new BizException(404, "设备不存在: id=" + assetId);
            }
            if (!p.getContractId().equals(a.getContractId())) {
                throw new BizException(400, "设备「" + assetLabel(a) + "」不属于合同 " + c.getNo());
            }
            if (a.getPurchaseInId() != null && !a.getPurchaseInId().equals(id)) {
                PurchaseIn other = purchaseInMapper.selectById(a.getPurchaseInId());
                throw new BizException(400, "设备「" + assetLabel(a) + "」已在采购单 "
                        + (other == null ? a.getPurchaseInId() : other.getNo()) + " 里");
            }
            if (a.getPurchasePrice() == null) {
                throw new BizException(400, "设备「" + assetLabel(a) + "」没有合同价,请先在合同清单里填单价");
            }
            PurchaseItem pi = new PurchaseItem();
            pi.setPurchaseInId(id);
            pi.setAssetId(a.getId());
            pi.setSerialNo(a.getSerialNo());
            pi.setCategory(a.getCategory());
            pi.setModel(a.getModel());
            pi.setMarketPrice(a.getMarketPrice());
            pi.setPurchasePrice(a.getPurchasePrice());
            pi.setSupplierId(a.getSupplierId() != null ? a.getSupplierId() : p.getSupplierId());
            pi.setMonthlyLaborValue(a.getMonthlyLaborValue());
            pi.setReplaceHeadcount(a.getReplaceHeadcount());
            purchaseItemMapper.insert(pi);
            assetService.bindPurchase(a.getId(), id, p.getNo());
            paymentService.onOrder(p, pi, a.getId(), p.getContractId());
            if ("已入库".equals(p.getStatus())) {
                // 已入库的单新增设备:直接走到入库态(入库留痕 + 到期日兑现)
                assetService.markPurchaseReceived(a.getId(), id, p.getNo());
                paymentService.onReceive(p, pi, a.getId());
            }
            added++;
        }

        syncTotalAmount(p);
        auditLogService.record("采购明细编辑", "purchase_in", id, AuditLogService.EXECUTED,
                "新增 " + added + " 台 · 移除 " + removed + " 台 · 现共 "
                        + itemsOf(id).size() + " 台 · 总额 " + p.getTotalAmount());
    }

    /** 编辑单条应付(阶段名/金额/到期日/备注);改金额会标记为手工调整。 */
    @Transactional
    public void editPayable(Long payableId, PurchaseEditDtos.PayableRequest req) {
        Payable pay = requirePayable(payableId);
        PurchaseIn p = load(pay.getPurchaseInId());
        requireEditable(p);
        if ("红冲".equals(pay.getStatus())) {
            throw new BizException(400, "已红冲的应付行不可编辑");
        }
        List<String> changes = new ArrayList<>();
        if (req.getStage() != null && !req.getStage().trim().isEmpty()
                && !req.getStage().trim().equals(pay.getStage())) {
            if ("退款红字".equals(req.getStage().trim())) {
                throw new BizException(400, "「退款红字」为系统保留阶段名");
            }
            changes.add("阶段 " + pay.getStage() + " → " + req.getStage().trim());
            pay.setStage(req.getStage().trim());
        }
        if (req.getAmount() != null && req.getAmount().compareTo(nz(pay.getAmount())) != 0) {
            if (req.getAmount().signum() < 0) {
                throw new BizException(400, "应付金额不能为负(红字由退货红冲生成)");
            }
            changes.add("金额 " + pay.getAmount() + " → " + req.getAmount() + "(手工调整)");
            pay.setAmount(req.getAmount().setScale(2, RoundingMode.HALF_UP));
            pay.setAmountManual(1);
        }
        if (req.getDueDate() != null && !req.getDueDate().equals(pay.getDueDate())) {
            changes.add("到期日 " + pay.getDueDate() + " → " + req.getDueDate());
            pay.setDueDate(req.getDueDate());
            // 手工指定了到期日,就不再是“按预计入库日推算”的预估值
            pay.setDueProvisional(0);
        }
        if (req.getDueProvisional() != null) {
            pay.setDueProvisional(Boolean.TRUE.equals(req.getDueProvisional()) ? 1 : 0);
        }
        if (req.getRemark() != null) {
            pay.setRemark(req.getRemark());
        }
        payableMapper.updateById(pay);
        if (!changes.isEmpty()) {
            auditLogService.record("应付编辑", "payable", payableId, AuditLogService.EXECUTED,
                    "采购单 " + p.getNo() + " · " + String.join(" · ", changes));
        }
    }

    /**
     * 登记付款。实付=应付 → 整行置已付;
     * 实付&lt;应付 → 本行改为已付(实付额) 并新增一行待付(差额),差额继续算负债。
     */
    @Transactional
    public void payPayable(Long payableId, PurchaseEditDtos.PayRequest req) {
        Payable pay = requirePayable(payableId);
        PurchaseIn p = load(pay.getPurchaseInId());
        if ("已红冲".equals(p.getStatus())) {
            throw new BizException(400, "采购单已红冲,不可登记付款");
        }
        if (!"待付".equals(pay.getStatus())) {
            throw new BizException(400, "本笔应付状态为" + pay.getStatus() + ",仅待付可登记付款");
        }
        BigDecimal due = nz(pay.getAmount());
        BigDecimal paid = req.getPaidAmount().setScale(2, RoundingMode.HALF_UP);
        if (paid.signum() <= 0) {
            throw new BizException(400, "实付金额须大于 0");
        }
        if (paid.compareTo(due) > 0) {
            throw new BizException(400, "实付 " + paid + " 超过本笔应付 " + due
                    + ";若确实要多付,请先把应付金额改大");
        }
        LocalDate paidDate = req.getPaidDate() != null ? req.getPaidDate() : LocalDate.now();
        BigDecimal rest = due.subtract(paid);
        if (rest.signum() > 0) {
            // 部分付款:差额拆一行继续待付
            Payable remain = new Payable();
            remain.setPurchaseInId(pay.getPurchaseInId());
            remain.setAssetId(pay.getAssetId());
            remain.setPurchaseItemId(pay.getPurchaseItemId());
            remain.setTermId(pay.getTermId());
            remain.setStage(pay.getStage());
            remain.setDueDate(pay.getDueDate());
            remain.setDueProvisional(pay.getDueProvisional());
            remain.setAmount(rest);
            remain.setAmountManual(1);
            remain.setStatus("待付");
            remain.setRemark("部分付款差额(原应付 " + due + ",已付 " + paid + ")");
            payableMapper.insert(remain);
            pay.setAmount(paid);
            pay.setAmountManual(1);
        }
        pay.setStatus("已付");
        pay.setPaidDate(paidDate);
        if (req.getRemark() != null && !req.getRemark().trim().isEmpty()) {
            pay.setRemark(appendRemark(pay.getRemark(), req.getRemark().trim()));
        }
        payableMapper.updateById(pay);
        auditLogService.record("应付登记付款", "payable", payableId, AuditLogService.EXECUTED,
                "采购单 " + p.getNo() + " · " + pay.getStage() + " 实付 " + paid
                        + "/" + due + " 于 " + paidDate
                        + (rest.signum() > 0 ? " · 差额 " + rest + " 拆行继续待付" : ""));
    }

    /** 撤销付款(误登记用):退回待付并清实付日。 */
    @Transactional
    public void unpayPayable(Long payableId) {
        Payable pay = requirePayable(payableId);
        PurchaseIn p = load(pay.getPurchaseInId());
        if (!"已付".equals(pay.getStatus())) {
            throw new BizException(400, "本笔应付状态为" + pay.getStatus() + ",仅已付可撤销");
        }
        LocalDate old = pay.getPaidDate();
        payableMapper.update(null, new LambdaUpdateWrapper<Payable>()
                .eq(Payable::getId, payableId)
                .set(Payable::getStatus, "待付")
                .set(Payable::getPaidDate, null));
        auditLogService.record("应付撤销付款", "payable", payableId, AuditLogService.EXECUTED,
                "采购单 " + p.getNo() + " · " + pay.getStage() + " " + pay.getAmount()
                        + " 原实付日 " + old + " → 退回待付");
    }

    // ---- 编辑用到的内部方法 ----

    private void requireEditable(PurchaseIn p) {
        if ("已红冲".equals(p.getStatus())) {
            throw new BizException(400, "采购单已退货红冲,不可再编辑");
        }
    }

    private Payable requirePayable(Long payableId) {
        Payable pay = payableMapper.selectById(payableId);
        if (pay == null || Integer.valueOf(1).equals(pay.getIsDeleted())) {
            throw new BizException(404, "应付不存在: id=" + payableId);
        }
        return pay;
    }

    /** 该设备是否已有付过款的应付。 */
    private boolean hasPaid(Long assetId) {
        return payableMapper.selectCount(new LambdaQueryWrapper<Payable>()
                .eq(Payable::getAssetId, assetId).eq(Payable::getStatus, "已付")) > 0;
    }

    /** 重算本单「到期日为预估」的待付应付(改过预计入库日后调)。返回改了多少笔。 */
    private int resyncProvisionalDueDates(PurchaseIn p) {
        if (p.getExpectReceiveDate() == null || p.getReceiveDate() != null) {
            return 0;
        }
        int n = 0;
        for (Payable pay : payablesOf(p.getId())) {
            if (!"待付".equals(pay.getStatus()) || !Integer.valueOf(1).equals(pay.getDueProvisional())) {
                continue;
            }
            int days = paymentService.dueDaysOfTerm(pay.getTermId());
            payableMapper.update(null, new LambdaUpdateWrapper<Payable>()
                    .eq(Payable::getId, pay.getId())
                    .set(Payable::getDueDate, p.getExpectReceiveDate().plusDays(days)));
            n++;
        }
        return n;
    }

    /** 总额 = С 明细集采价。 */
    private void syncTotalAmount(PurchaseIn p) {
        BigDecimal total = BigDecimal.ZERO;
        for (PurchaseItem pi : itemsOf(p.getId())) {
            total = total.add(nz(pi.getPurchasePrice()));
        }
        p.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        purchaseInMapper.updateById(p);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String appendRemark(String base, String add) {
        return (base == null || base.isEmpty()) ? add : base + " | " + add;
    }

    // ============ 入库(逐件生成 asset + 验收/尾款应付) ============

    @Transactional
    public void receive(Long id) {
        PurchaseIn p = load(id);
        if (!"已下单".equals(p.getStatus())) {
            throw new BizException(400, "采购单状态为" + p.getStatus() + ",仅已下单可入库");
        }
        LocalDate receiveDate = LocalDate.now();
        List<PurchaseItem> items = itemsOf(id);
        if (items.isEmpty()) {
            throw new BizException(400, "采购单无明细,不可入库");
        }
        p.setStatus("已入库");
        p.setReceiveDate(receiveDate);
        purchaseInMapper.updateById(p);

        // 明细已关联台账设备:入库只回填留痕;老单据(改造前手填的明细)仍按原逻辑逐件建档。
        // 付款条件回填设备并逐台生成 触发=入库 的应付(到期=入库日+N 天),各段合计=该设备合同价
        for (PurchaseItem pi : items) {
            Long assetId = pi.getAssetId();
            if (assetId == null) {
                assetId = assetService.createForPurchase(
                        pi.getSerialNo(), pi.getCategory(), pi.getModel(),
                        pi.getMarketPrice(), pi.getPurchasePrice(), pi.getSupplierId(),
                        pi.getMonthlyLaborValue(), pi.getReplaceHeadcount(), id, "采购入库");
                pi.setAssetId(assetId);
                purchaseItemMapper.updateById(pi);
            } else {
                assetService.markPurchaseReceived(assetId, id, p.getNo());
            }
            paymentService.onReceive(p, pi, assetId);
        }
        BigDecimal total = p.getTotalAmount() != null ? p.getTotalAmount() : BigDecimal.ZERO;

        // M3-01 钩子:采购入库 → 应付凭证(税务账·dr 固定资产 / cr 应付账款·借贷平衡·幂等)
        voucherService.postPayable(id, total, receiveDate,
                "采购入库应付 " + p.getNo() + " " + total + "元");
        log.info("采购入库: id={}, 设备{}台, 待付应付={}", id, items.size(), outstanding(id));
    }

    // ============ 退货红冲(整单红冲·设备报废释放·应付红字) ============

    @Transactional
    public void returnOrder(Long id, ReturnRequest req) {
        PurchaseIn p = load(id);
        if ("已红冲".equals(p.getStatus())) {
            throw new BizException(400, "采购单已红冲");
        }
        String reason = req != null && req.getReason() != null ? req.getReason() : "采购退货红冲";

        // 设备处理:合同清单生成的台账设备只解除采购关系(设备留在台账,可重新采购);
        // 老单据里由采购入库建的设备仍按原口径报废释放(在租设备由 AssetService 拒绝)
        int scrapped = 0;
        int released = 0;
        for (PurchaseItem pi : itemsOf(id)) {
            if (pi.getAssetId() == null) {
                continue;
            }
            Asset a = assetMapper.selectById(pi.getAssetId());
            if (a != null && a.getBoqLineId() != null) {
                assetService.releaseOnPurchaseReturn(pi.getAssetId(), id);
                released++;
            } else {
                assetService.scrapOnPurchaseReturn(pi.getAssetId(), id);
                scrapped++;
            }
        }
        // 应付红字:未付置红冲;已生成的应付整体红字冲销(插等额负数行),原行标红冲
        BigDecimal redSum = BigDecimal.ZERO;
        List<Payable> payables = payablesOf(id);
        for (Payable pay : payables) {
            if ("红冲".equals(pay.getStatus())) {
                continue;
            }
            redSum = redSum.add(pay.getAmount());
            pay.setStatus("红冲");
            pay.setRemark((pay.getRemark() == null ? "" : pay.getRemark() + " | ") + "退货红冲");
            payableMapper.updateById(pay);
        }
        if (redSum.signum() != 0) {
            insertPayable(id, "退款红字", LocalDate.now(), redSum.negate().setScale(2, RoundingMode.HALF_UP),
                    "退货整单红字冲销: " + reason);
        }

        p.setStatus("已红冲");
        p.setRemark((p.getRemark() == null ? "" : p.getRemark() + " | ") + "退货红冲: " + reason);
        purchaseInMapper.updateById(p);

        auditLogService.record("采购退货", "purchase_in", id, AuditLogService.EXECUTED,
                "整单红冲 · 报废设备" + scrapped + "件 · 应付红字" + redSum.negate() + " · " + reason);
        log.info("采购退货红冲: id={}, 报废{}件, 红字={}, by={}", id, scrapped, redSum.negate(), UserContext.getUserId());
    }

    // ============ 详情 ============

    public PurchaseDetailResponse detail(Long id) {
        PurchaseIn p = load(id);
        boolean seeCost = DataScope.canSeeCost(UserContext.getRole());
        PurchaseDetailResponse r = new PurchaseDetailResponse();
        r.setId(p.getId());
        r.setNo(p.getNo());
        r.setStatus(p.getStatus());
        r.setContractId(p.getContractId());
        r.setContractNo(contractNo(p.getContractId()));
        r.setCustomerName(customerNameOfContract(p.getContractId()));
        r.setSupplierId(p.getSupplierId());
        r.setSupplierName(supplierName(p.getSupplierId()));
        r.setTotalAmount(seeCost ? p.getTotalAmount() : null);
        r.setOrderDate(p.getOrderDate());
        r.setReceiveDate(p.getReceiveDate());
        r.setExpectReceiveDate(p.getExpectReceiveDate());
        r.setContractPaymentTerms(contractPaymentService.describe(p.getContractId()));
        r.setRemark(p.getRemark());
        r.setSensitiveMasked(!seeCost);
        r.setPayableOutstanding(seeCost ? outstanding(id) : null);

        List<PurchaseDetailResponse.ItemLine> itemLines = new ArrayList<>();
        for (PurchaseItem pi : itemsOf(id)) {
            PurchaseDetailResponse.ItemLine il = new PurchaseDetailResponse.ItemLine();
            il.setId(pi.getId());
            il.setSerialNo(pi.getSerialNo());
            il.setCategory(pi.getCategory());
            il.setModel(pi.getModel());
            il.setMarketPrice(pi.getMarketPrice());
            il.setPurchasePrice(seeCost ? pi.getPurchasePrice() : null);
            il.setSupplierName(supplierName(pi.getSupplierId()));
            il.setAssetId(pi.getAssetId());
            il.setRemark(pi.getRemark());
            Asset a = pi.getAssetId() == null ? null : assetMapper.selectById(pi.getAssetId());
            if (a != null) {
                il.setAssetStatus(a.getStatus());
                il.setAssetLabel(assetLabel(a));
                if (a.getSupplierId() != null) {
                    il.setSupplierName(supplierName(a.getSupplierId()));
                }
                il.setPaymentTerms(paymentService.describeTerms(p.getContractId()));
                il.setExpectedAmount(seeCost ? a.getPurchasePrice() : null);
            } else {
                il.setAssetLabel(assetLabel(pi.getCategory(), pi.getModel(), pi.getSerialNo()));
                il.setExpectedAmount(seeCost ? pi.getPurchasePrice() : null);
            }
            // 本件已生成的应付与待付
            BigDecimal itemPayable = BigDecimal.ZERO;
            BigDecimal itemOutstanding = BigDecimal.ZERO;
            for (Payable pay : payablesOf(id)) {
                if (!pi.getId().equals(pay.getPurchaseItemId())
                        && !(pi.getAssetId() != null && pi.getAssetId().equals(pay.getAssetId()))) {
                    continue;
                }
                if ("红冲".equals(pay.getStatus())) {
                    continue;
                }
                itemPayable = itemPayable.add(pay.getAmount());
                if ("待付".equals(pay.getStatus())) {
                    itemOutstanding = itemOutstanding.add(pay.getAmount());
                }
            }
            il.setPayableAmount(seeCost ? itemPayable : null);
            il.setPayableOutstanding(seeCost ? itemOutstanding : null);
            itemLines.add(il);
        }
        r.setItems(itemLines);

        java.util.Map<Long, String> serialByItem = new java.util.HashMap<>();
        itemsOf(id).forEach(pi -> serialByItem.put(pi.getId(), pi.getSerialNo()));
        List<PurchaseDetailResponse.PayableLine> payLines = new ArrayList<>();
        for (Payable pay : payablesOf(id)) {
            PurchaseDetailResponse.PayableLine pl = new PurchaseDetailResponse.PayableLine();
            pl.setId(pay.getId());
            pl.setAssetId(pay.getAssetId());
            pl.setSerialNo(serialByItem.get(pay.getPurchaseItemId()));
            Asset pa = pay.getAssetId() == null ? null : assetMapper.selectById(pay.getAssetId());
            if (pa != null) {
                pl.setAssetLabel(assetLabel(pa));
                pl.setSupplierName(supplierName(pa.getSupplierId() != null ? pa.getSupplierId() : p.getSupplierId()));
            } else {
                pl.setSupplierName(supplierName(p.getSupplierId()));
            }
            pl.setStage(pay.getStage());
            pl.setDueDate(pay.getDueDate());
            pl.setDueProvisional(Integer.valueOf(1).equals(pay.getDueProvisional()));
            pl.setAmountManual(Integer.valueOf(1).equals(pay.getAmountManual()));
            pl.setAmount(seeCost ? pay.getAmount() : null);
            pl.setStatus(pay.getStatus());
            pl.setPaidDate(pay.getPaidDate());
            pl.setRemark(pay.getRemark());
            payLines.add(pl);
        }
        r.setPayables(payLines);
        r.setRentCoverage(buildCoverage(p, seeCost));
        return r;
    }

    /**
     * 收租对照:左边本单货款(总额/已付/待付),右边同一份合同的租金(已收/待收/逾期/下一期/逾期案)。
     * 覆盖率=已收租金÷货款总额,和货款一样属成本口径,对 GP/LP 打码(否则能由覆盖率反推出货款)。
     */
    private PurchaseDetailResponse.RentCoverage buildCoverage(PurchaseIn p, boolean seeCost) {
        RentCoverageDto rc = rentCoverageService.of(p.getContractId());
        if (rc == null) {
            return null;
        }
        PurchaseDetailResponse.RentCoverage c = new PurchaseDetailResponse.RentCoverage();
        c.setContractId(p.getContractId());
        c.setContractNo(contractNo(p.getContractId()));
        c.setCustomerName(customerNameOfContract(p.getContractId()));
        c.setCollectedAmount(rc.getCollectedAmount());
        c.setPendingAmount(rc.getPendingAmount());
        c.setOverdueAmount(rc.getOverdueAmount());
        c.setOverdueCount(rc.getOverdueCount());
        c.setBillCount(rc.getBillCount());
        c.setNextDueDate(rc.getNextDueDate());
        c.setNextDueAmount(rc.getNextDueAmount());
        c.setOpenCaseCount(rc.getOpenCaseCount());
        c.setOpenCaseStep(rc.getOpenCaseStep());
        if (!seeCost) {
            return c;
        }
        BigDecimal total = p.getTotalAmount() != null ? p.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal paid = payablesOf(p.getId()).stream()
                .filter(pay -> "已付".equals(pay.getStatus()))
                .map(Payable::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
        c.setPurchaseTotal(total.setScale(2, RoundingMode.HALF_UP));
        c.setPaidAmount(paid);
        c.setUnpaidAmount(outstanding(p.getId()));
        // 已红冲的单子货款已整单冲销,再算覆盖率没有意义(会得出 575% 这种数)
        if (total.signum() != 0 && !"已红冲".equals(p.getStatus())) {
            c.setCoverageRatio(rc.getCollectedAmount().divide(total, 4, RoundingMode.HALF_UP));
        }
        return c;
    }

    // ============ 工具 ============

    private void insertPayable(Long purchaseInId, String stage, LocalDate due, BigDecimal amount, String remark) {
        Payable pay = new Payable();
        pay.setPurchaseInId(purchaseInId);
        pay.setStage(stage);
        pay.setDueDate(due);
        pay.setAmount(amount);
        pay.setStatus("待付");
        pay.setRemark(remark);
        payableMapper.insert(pay);
    }

    /** 采购提前期(天):rule_config[purchase_lead_days],缺配置按 30 天。 */
    private int purchaseLeadDays() {
        try {
            BigDecimal v = rules.getValue("purchase_lead_days", "", LocalDate.now());
            return v != null && v.intValue() > 0 ? v.intValue() : 30;
        } catch (Exception e) {
            return 30;
        }
    }

    /** 待付应付合计(负债口径:排除红冲)。 */
    private BigDecimal outstanding(Long purchaseInId) {
        return payablesOf(purchaseInId).stream()
                .filter(p -> "待付".equals(p.getStatus()))
                .map(Payable::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private List<PurchaseItem> itemsOf(Long purchaseInId) {
        return purchaseItemMapper.selectList(new LambdaQueryWrapper<PurchaseItem>()
                .eq(PurchaseItem::getPurchaseInId, purchaseInId).orderByAsc(PurchaseItem::getId));
    }

    private List<Payable> payablesOf(Long purchaseInId) {
        return payableMapper.selectList(new LambdaQueryWrapper<Payable>()
                .eq(Payable::getPurchaseInId, purchaseInId).orderByAsc(Payable::getId));
    }

    private PurchaseIn load(Long id) {
        PurchaseIn p = purchaseInMapper.selectById(id);
        if (p == null || Integer.valueOf(1).equals(p.getIsDeleted())) {
            throw new BizException(404, "采购单不存在: id=" + id);
        }
        return p;
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

    /** 设备显示名:品类 · 型号(型号为空时退回序列号)。 */
    static String assetLabel(Asset a) {
        return assetLabel(a.getCategory(), a.getModel(), a.getSerialNo());
    }

    static String assetLabel(String category, String model, String serialNo) {
        String head = category == null ? "" : category;
        String tail = model != null && !model.trim().isEmpty() ? model.trim() : serialNo;
        if (head.isEmpty()) {
            return tail;
        }
        return tail == null || tail.isEmpty() ? head : head + " · " + tail;
    }

    private String supplierName(Long supplierId) {
        if (supplierId == null) {
            return null;
        }
        Supplier s = supplierMapper.selectById(supplierId);
        return s != null ? s.getName() : ("供应商#" + supplierId);
    }
}
