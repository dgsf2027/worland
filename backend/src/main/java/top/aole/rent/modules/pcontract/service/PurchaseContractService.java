package top.aole.rent.modules.pcontract.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.finance.service.VoucherService;
import top.aole.rent.modules.payproof.service.PayProofService;
import top.aole.rent.modules.pcontract.domain.PurchaseContract;
import top.aole.rent.modules.pcontract.domain.PurchaseContractTerm;
import top.aole.rent.modules.pcontract.domain.PurchaseInvoice;
import top.aole.rent.modules.pcontract.domain.SupplierPayment;
import top.aole.rent.modules.pcontract.domain.SupplierPaymentAlloc;
import top.aole.rent.modules.pcontract.dto.PcDtos;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractMapper;
import top.aole.rent.modules.pcontract.mapper.PurchaseContractTermMapper;
import top.aole.rent.modules.pcontract.mapper.PurchaseInvoiceMapper;
import top.aole.rent.modules.pcontract.mapper.SupplierPaymentAllocMapper;
import top.aole.rent.modules.pcontract.mapper.SupplierPaymentMapper;
import top.aole.rent.modules.pcontract.mapper.SupplierPaymentPayableMapper;
import top.aole.rent.modules.pcontract.domain.SupplierPaymentPayable;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.domain.PurchaseIn;
import top.aole.rent.modules.purchase.mapper.PayableMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 采购合同 → 付款 → 进项发票。
 * <p>付款节点属于与供应商签的采购合同;一笔银行付款可以核销多个节点;收票按合同累计,
 * 付款、收票都不得超过合同含税总价。付款必须先传回单并生成付款凭证(借应付/贷银行)。
 * 曜石为简易计税,进项税不抵扣,收票只登记不过账。
 */
@Service
@RequiredArgsConstructor
public class PurchaseContractService {

    public static final String PAYMENT_BIZ = "supplier_payment";
    public static final String CONTRACT_BIZ = "pc_contract";
    public static final String INVOICE_BIZ = "pc_invoice";
    private static final BigDecimal TOL = new BigDecimal("0.01");

    private final PurchaseContractMapper contractMapper;
    private final PurchaseContractTermMapper termMapper;
    private final SupplierPaymentMapper paymentMapper;
    private final SupplierPaymentAllocMapper allocMapper;
    private final PurchaseInvoiceMapper invoiceMapper;
    private final SupplierMapper supplierMapper;
    private final PayProofService payProofService;
    private final VoucherService voucherService;
    private final AuditLogService auditLogService;
    private final PurchaseInMapper purchaseInMapper;
    private final PayableMapper payableMapper;
    private final SupplierPaymentPayableMapper settleMapper;
    private final ContractMapper rentContractMapper;
    private final CustomerMapper customerMapper;

    // ---------------- 合同 ----------------

    @Transactional(rollbackFor = Exception.class)
    public Long create(PcDtos.CreateRequest req) {
        if (blank(req.getContractNo())) {
            throw new BizException(400, "请填写采购合同号");
        }
        if (req.getSupplierId() == null || supplierMapper.selectById(req.getSupplierId()) == null) {
            throw new BizException(400, "供应商不存在");
        }
        BigDecimal total = money(req.getTotalAmount());
        if (total.signum() <= 0) {
            throw new BizException(400, "合同含税总价须大于 0");
        }
        if (contractMapper.selectCount(new LambdaQueryWrapper<PurchaseContract>()
                .eq(PurchaseContract::getContractNo, req.getContractNo().trim())) > 0) {
            throw new BizException(400, "采购合同号已存在: " + req.getContractNo());
        }
        if (req.getTerms() == null || req.getTerms().isEmpty()) {
            throw new BizException(400, "请至少设置一个付款节点");
        }
        List<BigDecimal> amounts = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (PcDtos.TermInput t : req.getTerms()) {
            if (blank(t.getStageName())) {
                throw new BizException(400, "付款节点名称不能为空");
            }
            BigDecimal a;
            if (t.getAmount() != null) {
                a = money(t.getAmount());
            } else if (t.getRatio() != null) {
                a = total.multiply(t.getRatio()).setScale(2, RoundingMode.HALF_UP);
            } else {
                throw new BizException(400, "节点「" + t.getStageName() + "」须填比例或金额");
            }
            if (a.signum() <= 0) {
                throw new BizException(400, "节点「" + t.getStageName() + "」金额须大于 0");
            }
            amounts.add(a);
            sum = sum.add(a);
        }
        if (sum.subtract(total).abs().compareTo(TOL) > 0) {
            throw new BizException(400, "付款节点合计 " + sum + " 与合同总价 " + total + " 不一致");
        }
        CurrentUser u = UserContext.require();
        PurchaseContract c = new PurchaseContract();
        c.setContractNo(req.getContractNo().trim());
        c.setSupplierId(req.getSupplierId());
        c.setRentContractId(req.getRentContractId());
        c.setTotalAmount(total);
        c.setTaxRate(req.getTaxRate() == null ? new BigDecimal("0.13") : req.getTaxRate());
        c.setSignedDate(req.getSignedDate());
        c.setStatus("执行中");
        c.setRemark(req.getRemark());
        c.setCreateByName(u.getUserName());
        contractMapper.insert(c);
        int i = 1;
        for (PcDtos.TermInput t : req.getTerms()) {
            PurchaseContractTerm term = new PurchaseContractTerm();
            term.setPcId(c.getId());
            term.setSortNo(i);
            term.setStageName(t.getStageName().trim());
            term.setRatio(t.getRatio());
            term.setAmount(amounts.get(i - 1));
            term.setConditionText(t.getConditionText());
            termMapper.insert(term);
            i++;
        }
        if (req.getProofFileIds() != null && !req.getProofFileIds().isEmpty()) {
            payProofService.bind(CONTRACT_BIZ, Collections.singletonList(c.getId()), "合同", req.getProofFileIds());
        }
        auditLogService.record("采购合同创建", "purchase_contract", c.getId(), AuditLogService.EXECUTED,
                c.getContractNo() + " 含税 " + total + " · " + req.getTerms().size() + " 个付款节点");
        return c.getId();
    }

    public List<PcDtos.ContractView> list() {
        List<PurchaseContract> cs = contractMapper.selectList(new LambdaQueryWrapper<PurchaseContract>()
                .orderByDesc(PurchaseContract::getId));
        List<PcDtos.ContractView> out = new ArrayList<>();
        for (PurchaseContract c : cs) {
            out.add(view(c));
        }
        return out;
    }

    public PcDtos.Detail detail(Long id) {
        PurchaseContract c = require(id);
        PcDtos.Detail d = new PcDtos.Detail();
        d.setContract(view(c));
        Map<Long, BigDecimal> paidByTerm = paidByTerm(id);
        List<PcDtos.TermView> tvs = new ArrayList<>();
        for (PurchaseContractTerm t : terms(id)) {
            PcDtos.TermView v = new PcDtos.TermView();
            v.setId(t.getId());
            v.setSortNo(t.getSortNo());
            v.setStageName(t.getStageName());
            v.setRatio(t.getRatio());
            v.setAmount(t.getAmount());
            BigDecimal paid = paidByTerm.getOrDefault(t.getId(), BigDecimal.ZERO);
            v.setPaid(paid);
            v.setUnpaid(t.getAmount().subtract(paid));
            v.setConditionText(t.getConditionText());
            tvs.add(v);
        }
        d.setTerms(tvs);
        List<SupplierPayment> ps = paymentMapper.selectList(new LambdaQueryWrapper<SupplierPayment>()
                .eq(SupplierPayment::getPcId, id)
                .orderByAsc(SupplierPayment::getPaidDate).orderByAsc(SupplierPayment::getId));
        Map<Long, Integer> proofCount = payProofService.countMap(PAYMENT_BIZ,
                ps.stream().map(SupplierPayment::getId).collect(Collectors.toList()));
        List<PcDtos.PaymentView> pvs = new ArrayList<>();
        for (SupplierPayment p : ps) {
            PcDtos.PaymentView v = new PcDtos.PaymentView();
            v.setId(p.getId());
            v.setAmount(p.getAmount());
            v.setPaidDate(p.getPaidDate());
            v.setBankSerial(p.getBankSerial());
            v.setVoucherId(p.getVoucherId());
            v.setStatus(p.getStatus());
            v.setRemark(p.getRemark());
            v.setProofCount(proofCount.getOrDefault(p.getId(), 0));
            List<PcDtos.AllocInput> as = new ArrayList<>();
            for (SupplierPaymentAlloc a : allocMapper.selectList(new LambdaQueryWrapper<SupplierPaymentAlloc>()
                    .eq(SupplierPaymentAlloc::getPaymentId, p.getId()))) {
                PcDtos.AllocInput ai = new PcDtos.AllocInput();
                ai.setTermId(a.getTermId());
                ai.setAmount(a.getAmount());
                as.add(ai);
            }
            v.setAllocs(as);
            pvs.add(v);
        }
        d.setPayments(pvs);
        d.setPurchases(linkedPurchases(id));
        d.setContractProofCount(payProofService.countMap(CONTRACT_BIZ, Collections.singletonList(id)).getOrDefault(id, 0));
        d.setInvoices(invoiceMapper.selectList(new LambdaQueryWrapper<PurchaseInvoice>()
                .eq(PurchaseInvoice::getPcId, id)
                .orderByAsc(PurchaseInvoice::getIssueDate).orderByAsc(PurchaseInvoice::getId)));
        d.setInvoiceProofCounts(payProofService.countMap(INVOICE_BIZ,
                d.getInvoices().stream().map(PurchaseInvoice::getId).collect(Collectors.toList())));
        return d;
    }

    // ---------------- 付款 ----------------

    @Transactional(rollbackFor = Exception.class)
    public Long pay(Long pcId, PcDtos.PaymentRequest req) {
        PurchaseContract c = requireActive(pcId);
        BigDecimal amount = money(req.getAmount());
        if (amount.signum() <= 0) {
            throw new BizException(400, "付款金额须大于 0");
        }
        BigDecimal paidSoFar = paidTotal(pcId);
        if (paidSoFar.add(amount).compareTo(c.getTotalAmount()) > 0) {
            throw new BizException(400, "累计付款 " + paidSoFar.add(amount) + " 将超过合同总价 " + c.getTotalAmount());
        }
        String serial = blank(req.getBankSerial()) ? null : req.getBankSerial().trim();
        if (serial != null && paymentMapper.selectCount(new LambdaQueryWrapper<SupplierPayment>()
                .eq(SupplierPayment::getBankSerial, serial)) > 0) {
            throw new BizException(400, "银行流水号已登记过: " + serial);
        }
        List<PurchaseContractTerm> terms = terms(pcId);
        Map<Long, BigDecimal> paidByTerm = paidByTerm(pcId);
        List<PcDtos.AllocInput> allocs = req.getAllocs();
        if (allocs == null || allocs.isEmpty()) {
            allocs = autoAlloc(terms, paidByTerm, amount);
        }
        Map<Long, PurchaseContractTerm> termMap = terms.stream()
                .collect(Collectors.toMap(PurchaseContractTerm::getId, t -> t));
        Map<Long, BigDecimal> thisRound = new LinkedHashMap<>();
        BigDecimal allocSum = BigDecimal.ZERO;
        for (PcDtos.AllocInput a : allocs) {
            PurchaseContractTerm t = termMap.get(a.getTermId());
            if (t == null) {
                throw new BizException(400, "核销节点不属于本合同: id=" + a.getTermId());
            }
            BigDecimal av = money(a.getAmount());
            if (av.signum() <= 0) {
                throw new BizException(400, "核销金额须大于 0");
            }
            BigDecimal used = paidByTerm.getOrDefault(t.getId(), BigDecimal.ZERO)
                    .add(thisRound.getOrDefault(t.getId(), BigDecimal.ZERO));
            if (used.add(av).compareTo(t.getAmount()) > 0) {
                throw new BizException(400, "节点「" + t.getStageName() + "」应付 " + t.getAmount()
                        + ",已核销 " + used + ",本次再核销 " + av + " 会超额");
            }
            thisRound.merge(t.getId(), av, BigDecimal::add);
            allocSum = allocSum.add(av);
        }
        if (allocSum.compareTo(amount) != 0) {
            throw new BizException(400, "核销合计 " + allocSum + " 与付款金额 " + amount + " 不一致");
        }
        LocalDate paidDate = req.getPaidDate() != null ? req.getPaidDate() : LocalDate.now();
        CurrentUser u = UserContext.require();
        SupplierPayment p = new SupplierPayment();
        p.setPcId(pcId);
        p.setSupplierId(c.getSupplierId());
        p.setAmount(amount);
        p.setPaidDate(paidDate);
        p.setBankSerial(serial);
        p.setStatus("有效");
        p.setRemark(req.getRemark());
        p.setCreateByName(u.getUserName());
        paymentMapper.insert(p);
        // 回单必传:缺回单/文件不合规抛 400,整个事务(含上面的付款行)回滚
        payProofService.bind(PAYMENT_BIZ, Collections.singletonList(p.getId()), "付款", req.getProofFileIds());
        for (Map.Entry<Long, BigDecimal> e : thisRound.entrySet()) {
            SupplierPaymentAlloc a = new SupplierPaymentAlloc();
            a.setPaymentId(p.getId());
            a.setTermId(e.getKey());
            a.setAmount(e.getValue());
            allocMapper.insert(a);
        }
        p.setVoucherId(voucherService.postSupplierPayment(p.getId(), amount, paidDate,
                "付款 " + c.getContractNo() + " " + amount + "元"));
        paymentMapper.updateById(p);
        BigDecimal settled = settlePayables(c, p.getId(), amount, paidDate);
        refreshStatus(c);
        auditLogService.record("采购合同付款", "purchase_contract", pcId, AuditLogService.EXECUTED,
                c.getContractNo() + " 付 " + amount + " 于 " + paidDate + (serial == null ? "" : " 流水 " + serial)
                        + (settled.signum() > 0 ? " · 冲销关联采购单应付 " + settled : ""));
        return p.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidPayment(Long paymentId, PcDtos.VoidRequest req) {
        SupplierPayment p = paymentMapper.selectById(paymentId);
        if (p == null) {
            throw new BizException(404, "付款不存在: id=" + paymentId);
        }
        if (!"有效".equals(p.getStatus())) {
            throw new BizException(400, "该付款已撤销");
        }
        String reason = req == null || blank(req.getReason()) ? "撤销付款" : req.getReason().trim();
        revertPayables(paymentId);
        voucherService.reverseBySource("supplier_payment", paymentId, reason);
        p.setStatus("撤销");
        paymentMapper.updateById(p);
        refreshStatus(require(p.getPcId()));
        auditLogService.record("采购合同撤销付款", "purchase_contract", p.getPcId(), AuditLogService.EXECUTED,
                "付款#" + paymentId + " " + p.getAmount() + " · " + reason);
    }

    // ---------------- 发票 ----------------

    @Transactional(rollbackFor = Exception.class)
    public Long addInvoice(Long pcId, PcDtos.InvoiceRequest req) {
        PurchaseContract c = requireActive(pcId);
        if (blank(req.getInvoiceNo())) {
            throw new BizException(400, "请填写发票号码");
        }
        if (!"专票".equals(req.getInvoiceType()) && !"普通".equals(req.getInvoiceType())) {
            throw new BizException(400, "发票类型须为 专票 或 普通");
        }
        if (req.getIssueDate() == null) {
            throw new BizException(400, "请填写开票日期");
        }
        BigDecimal ex = money(req.getAmountExTax());
        BigDecimal tax = money(req.getTaxAmount());
        BigDecimal total = money(req.getTotalAmount());
        if (total.signum() <= 0 || ex.signum() < 0 || tax.signum() < 0) {
            throw new BizException(400, "发票金额须为正数");
        }
        if (ex.add(tax).subtract(total).abs().compareTo(TOL) > 0) {
            throw new BizException(400, "不含税 " + ex + " + 税额 " + tax + " ≠ 价税合计 " + total);
        }
        if (invoiceMapper.selectCount(new LambdaQueryWrapper<PurchaseInvoice>()
                .eq(PurchaseInvoice::getInvoiceNo, req.getInvoiceNo().trim())) > 0) {
            throw new BizException(400, "发票号码已登记过: " + req.getInvoiceNo());
        }
        BigDecimal invoiced = invoicedTotal(pcId);
        if (invoiced.add(total).compareTo(c.getTotalAmount()) > 0) {
            throw new BizException(400, "累计收票 " + invoiced.add(total) + " 将超过合同总价 " + c.getTotalAmount());
        }
        CurrentUser u = UserContext.require();
        PurchaseInvoice inv = new PurchaseInvoice();
        inv.setPcId(pcId);
        inv.setInvoiceNo(req.getInvoiceNo().trim());
        inv.setInvoiceType(req.getInvoiceType());
        inv.setIssueDate(req.getIssueDate());
        inv.setSellerName(req.getSellerName());
        inv.setAmountExTax(ex);
        inv.setTaxAmount(tax);
        inv.setTotalAmount(total);
        inv.setTaxRate(req.getTaxRate() == null ? c.getTaxRate() : req.getTaxRate());
        inv.setStatus("有效");
        inv.setRemark(req.getRemark());
        inv.setCreateByName(u.getUserName());
        invoiceMapper.insert(inv);
        if (req.getProofFileIds() != null && !req.getProofFileIds().isEmpty()) {
            payProofService.bind(INVOICE_BIZ, Collections.singletonList(inv.getId()), "发票", req.getProofFileIds());
        }
        auditLogService.record("采购合同收票", "purchase_contract", pcId, AuditLogService.EXECUTED,
                c.getContractNo() + " 发票 " + inv.getInvoiceNo() + " 价税合计 " + total);
        return inv.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidInvoice(Long invoiceId, PcDtos.VoidRequest req) {
        PurchaseInvoice inv = invoiceMapper.selectById(invoiceId);
        if (inv == null) {
            throw new BizException(404, "发票不存在: id=" + invoiceId);
        }
        if (!"有效".equals(inv.getStatus())) {
            throw new BizException(400, "该发票已作废");
        }
        inv.setStatus("作废");
        invoiceMapper.updateById(inv);
        auditLogService.record("采购合同发票作废", "purchase_contract", inv.getPcId(), AuditLogService.EXECUTED,
                "发票 " + inv.getInvoiceNo() + (req == null || blank(req.getReason()) ? "" : " · " + req.getReason()));
    }

    // ---------------- 关联采购入库单 ----------------

    @Transactional(rollbackFor = Exception.class)
    public void linkPurchases(Long pcId, PcDtos.LinkRequest req) {
        PurchaseContract c = requireActive(pcId);
        if (req == null || req.getPurchaseInIds() == null || req.getPurchaseInIds().isEmpty()) {
            throw new BizException(400, "请选择要关联的采购单");
        }
        for (Long id : req.getPurchaseInIds()) {
            PurchaseIn p = purchaseInMapper.selectById(id);
            if (p == null) {
                throw new BizException(404, "采购单不存在: id=" + id);
            }
            if ("已红冲".equals(p.getStatus())) {
                throw new BizException(400, "采购单 " + p.getNo() + " 已红冲,不能关联");
            }
            if (p.getPcId() != null && !p.getPcId().equals(pcId)) {
                throw new BizException(400, "采购单 " + p.getNo() + " 已关联其他采购合同");
            }
            if (p.getSupplierId() == null || !p.getSupplierId().equals(c.getSupplierId())) {
                throw new BizException(400, "采购单 " + p.getNo() + " 的供应商与合同 " + c.getContractNo() + " 不一致");
            }
            p.setPcId(pcId);
            purchaseInMapper.updateById(p);
            auditLogService.record("采购单关联采购合同", "purchase_in", id, AuditLogService.EXECUTED,
                    "采购单 " + p.getNo() + " → 合同 " + c.getContractNo());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void unlinkPurchase(Long pcId, Long purchaseInId) {
        PurchaseIn p = purchaseInMapper.selectById(purchaseInId);
        if (p == null || !pcId.equals(p.getPcId())) {
            throw new BizException(400, "该采购单未关联本合同");
        }
        List<Long> payableIds = payableMapper.selectList(new LambdaQueryWrapper<Payable>()
                        .eq(Payable::getPurchaseInId, purchaseInId))
                .stream().map(Payable::getId).collect(Collectors.toList());
        if (!payableIds.isEmpty()) {
            List<Long> linkedPayments = settleMapper.selectList(new LambdaQueryWrapper<SupplierPaymentPayable>()
                            .in(SupplierPaymentPayable::getPayableId, payableIds))
                    .stream().map(SupplierPaymentPayable::getPaymentId).distinct().collect(Collectors.toList());
            for (Long payId : linkedPayments) {
                SupplierPayment sp = paymentMapper.selectById(payId);
                if (sp != null && "有效".equals(sp.getStatus())) {
                    throw new BizException(400, "该采购单的应付已被付款#" + payId + " 冲销,请先撤销该付款再解除关联");
                }
            }
        }
        purchaseInMapper.update(null, new LambdaUpdateWrapper<PurchaseIn>()
                .eq(PurchaseIn::getId, purchaseInId).set(PurchaseIn::getPcId, null));
        auditLogService.record("采购单解除采购合同", "purchase_in", purchaseInId, AuditLogService.EXECUTED,
                "采购单 " + p.getNo() + " 解除关联");
    }

    private List<PcDtos.LinkedPurchase> linkedPurchases(Long pcId) {
        List<PcDtos.LinkedPurchase> out = new ArrayList<>();
        for (PurchaseIn p : purchaseInMapper.selectList(new LambdaQueryWrapper<PurchaseIn>()
                .eq(PurchaseIn::getPcId, pcId).orderByAsc(PurchaseIn::getId))) {
            PcDtos.LinkedPurchase lp = new PcDtos.LinkedPurchase();
            lp.setId(p.getId());
            lp.setNo(p.getNo());
            lp.setStatus(p.getStatus());
            lp.setTotalAmount(p.getTotalAmount());
            lp.setPendingPayable(pendingPayables(p.getId()).stream().map(Payable::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            out.add(lp);
        }
        return out;
    }

    private List<Payable> pendingPayables(Long purchaseInId) {
        return payableMapper.selectList(new LambdaQueryWrapper<Payable>()
                .eq(Payable::getPurchaseInId, purchaseInId).eq(Payable::getStatus, "待付")
                .gt(Payable::getAmount, BigDecimal.ZERO)
                .orderByAsc(Payable::getDueDate).orderByAsc(Payable::getId));
    }

    /** 付款按到期日先后冲销关联采购单的待付应付;不足一整行则拆行。返回实际冲销金额。 */
    private BigDecimal settlePayables(PurchaseContract c, Long paymentId, BigDecimal amount, LocalDate paidDate) {
        List<Payable> rows = new ArrayList<>();
        for (PurchaseIn p : purchaseInMapper.selectList(new LambdaQueryWrapper<PurchaseIn>()
                .eq(PurchaseIn::getPcId, c.getId()).ne(PurchaseIn::getStatus, "已红冲"))) {
            rows.addAll(pendingPayables(p.getId()));
        }
        rows.sort(java.util.Comparator
                .comparing(Payable::getDueDate, java.util.Comparator.nullsLast(java.util.Comparator.<LocalDate>naturalOrder()))
                .thenComparing(Payable::getId));
        BigDecimal left = amount;
        BigDecimal settled = BigDecimal.ZERO;
        for (Payable row : rows) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal due = row.getAmount();
            BigDecimal use = due.min(left);
            SupplierPaymentPayable link = new SupplierPaymentPayable();
            link.setPaymentId(paymentId);
            link.setPayableId(row.getId());
            link.setPaidAmount(use);
            link.setOrigAmount(due);
            if (use.compareTo(due) < 0) {
                Payable remain = new Payable();
                remain.setPurchaseInId(row.getPurchaseInId());
                remain.setAssetId(row.getAssetId());
                remain.setPurchaseItemId(row.getPurchaseItemId());
                remain.setTermId(row.getTermId());
                remain.setStage(row.getStage());
                remain.setDueDate(row.getDueDate());
                remain.setDueProvisional(row.getDueProvisional());
                remain.setAmount(due.subtract(use));
                remain.setAmountManual(1);
                remain.setStatus("待付");
                remain.setRemark("采购合同部分付款差额(原应付 " + due + ",已付 " + use + ")");
                payableMapper.insert(remain);
                link.setRemainderId(remain.getId());
                row.setAmount(use);
                row.setAmountManual(1);
            }
            row.setStatus("已付");
            row.setPaidDate(paidDate);
            payableMapper.updateById(row);
            settleMapper.insert(link);
            left = left.subtract(use);
            settled = settled.add(use);
        }
        return settled;
    }

    /** 撤销付款:把被冲销的应付行精确还原为待付;拆出的差额行若已被后续付款动过则拒绝。 */
    private void revertPayables(Long paymentId) {
        List<SupplierPaymentPayable> links = settleMapper.selectList(new LambdaQueryWrapper<SupplierPaymentPayable>()
                .eq(SupplierPaymentPayable::getPaymentId, paymentId));
        for (SupplierPaymentPayable l : links) {
            Payable row = payableMapper.selectById(l.getPayableId());
            if (row == null) {
                continue;
            }
            if (l.getRemainderId() != null) {
                Payable rem = payableMapper.selectById(l.getRemainderId());
                BigDecimal expect = l.getOrigAmount().subtract(l.getPaidAmount());
                if (rem != null) {
                    if (!"待付".equals(rem.getStatus()) || rem.getAmount().compareTo(expect) != 0) {
                        throw new BizException(400, "本次付款拆出的差额应付已被后续付款使用,请先撤销后续付款");
                    }
                    payableMapper.deleteById(rem.getId());
                }
            }
            payableMapper.update(null, new LambdaUpdateWrapper<Payable>()
                    .eq(Payable::getId, row.getId())
                    .set(Payable::getAmount, l.getOrigAmount())
                    .set(Payable::getStatus, "待付")
                    .set(Payable::getPaidDate, null));
            settleMapper.deleteById(l.getId());
        }
    }

    // ---------------- 内部 ----------------

    private List<PcDtos.AllocInput> autoAlloc(List<PurchaseContractTerm> terms, Map<Long, BigDecimal> paidByTerm,
                                              BigDecimal amount) {
        List<PcDtos.AllocInput> out = new ArrayList<>();
        BigDecimal left = amount;
        for (PurchaseContractTerm t : terms) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal room = t.getAmount().subtract(paidByTerm.getOrDefault(t.getId(), BigDecimal.ZERO));
            if (room.signum() <= 0) {
                continue;
            }
            BigDecimal use = room.min(left);
            PcDtos.AllocInput a = new PcDtos.AllocInput();
            a.setTermId(t.getId());
            a.setAmount(use);
            out.add(a);
            left = left.subtract(use);
        }
        return out;
    }

    private PcDtos.ContractView view(PurchaseContract c) {
        PcDtos.ContractView v = new PcDtos.ContractView();
        v.setId(c.getId());
        v.setContractNo(c.getContractNo());
        v.setSupplierId(c.getSupplierId());
        Supplier s = supplierMapper.selectById(c.getSupplierId());
        v.setSupplierName(s == null ? null : s.getName());
        v.setRentContractId(c.getRentContractId());
        if (c.getRentContractId() != null) {
            Contract rc = rentContractMapper.selectById(c.getRentContractId());
            if (rc != null) {
                v.setRentContractNo(rc.getNo());
                Customer cu = rc.getCustomerId() == null ? null : customerMapper.selectById(rc.getCustomerId());
                v.setCustomerName(cu == null ? null : cu.getName());
                v.setRentTermMonths(rc.getTermMonths());
                v.setRentMonthRent(rc.getMonthRent());
                v.setRentStartDate(rc.getStartDate());
                v.setRentStatus(rc.getStatus());
            }
        }
        v.setTotalAmount(c.getTotalAmount());
        v.setTaxRate(c.getTaxRate());
        v.setSignedDate(c.getSignedDate());
        v.setStatus(c.getStatus());
        v.setRemark(c.getRemark());
        PcDtos.Summary sm = new PcDtos.Summary();
        BigDecimal paid = paidTotal(c.getId());
        BigDecimal inv = invoicedTotal(c.getId());
        sm.setTotal(c.getTotalAmount());
        sm.setPaid(paid);
        sm.setInvoiced(inv);
        sm.setUnpaid(c.getTotalAmount().subtract(paid));
        sm.setUninvoiced(c.getTotalAmount().subtract(inv));
        sm.setPaidNotInvoiced(paid.subtract(inv).max(BigDecimal.ZERO));
        v.setSummary(sm);
        return v;
    }

    private void refreshStatus(PurchaseContract c) {
        if ("作废".equals(c.getStatus())) {
            return;
        }
        String next = paidTotal(c.getId()).compareTo(c.getTotalAmount()) >= 0 ? "已结清" : "执行中";
        if (!next.equals(c.getStatus())) {
            c.setStatus(next);
            contractMapper.updateById(c);
        }
    }

    private BigDecimal paidTotal(Long pcId) {
        return paymentMapper.selectList(new LambdaQueryWrapper<SupplierPayment>()
                        .eq(SupplierPayment::getPcId, pcId).eq(SupplierPayment::getStatus, "有效"))
                .stream().map(SupplierPayment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal invoicedTotal(Long pcId) {
        return invoiceMapper.selectList(new LambdaQueryWrapper<PurchaseInvoice>()
                        .eq(PurchaseInvoice::getPcId, pcId).eq(PurchaseInvoice::getStatus, "有效"))
                .stream().map(PurchaseInvoice::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<Long, BigDecimal> paidByTerm(Long pcId) {
        List<Long> payIds = paymentMapper.selectList(new LambdaQueryWrapper<SupplierPayment>()
                        .eq(SupplierPayment::getPcId, pcId).eq(SupplierPayment::getStatus, "有效"))
                .stream().map(SupplierPayment::getId).collect(Collectors.toList());
        Map<Long, BigDecimal> m = new HashMap<>();
        if (payIds.isEmpty()) {
            return m;
        }
        for (SupplierPaymentAlloc a : allocMapper.selectList(new LambdaQueryWrapper<SupplierPaymentAlloc>()
                .in(SupplierPaymentAlloc::getPaymentId, payIds))) {
            m.merge(a.getTermId(), a.getAmount(), BigDecimal::add);
        }
        return m;
    }

    private List<PurchaseContractTerm> terms(Long pcId) {
        return termMapper.selectList(new LambdaQueryWrapper<PurchaseContractTerm>()
                .eq(PurchaseContractTerm::getPcId, pcId).orderByAsc(PurchaseContractTerm::getSortNo));
    }

    private PurchaseContract require(Long id) {
        PurchaseContract c = contractMapper.selectById(id);
        if (c == null) {
            throw new BizException(404, "采购合同不存在: id=" + id);
        }
        return c;
    }

    private PurchaseContract requireActive(Long id) {
        PurchaseContract c = require(id);
        if ("作废".equals(c.getStatus())) {
            throw new BizException(400, "采购合同已作废");
        }
        return c;
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
