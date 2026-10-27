package top.aole.rent.modules.pcontract;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import top.aole.rent.modules.pcontract.service.PurchaseContractService;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 采购合同→付款→发票:节点核销、跨节点付款、超额拦截、流水/发票号去重、对账汇总、撤销付款红冲。
 * 全部使用虚构数据。仓储用内存列表模拟,QueryWrapper 的过滤条件不真实执行(单合同场景下等价)。
 */
class PurchaseContractServiceTest {

    private PurchaseContractMapper contractMapper;
    private PurchaseContractTermMapper termMapper;
    private SupplierPaymentMapper paymentMapper;
    private SupplierPaymentAllocMapper allocMapper;
    private PurchaseInvoiceMapper invoiceMapper;
    private SupplierMapper supplierMapper;
    private PayProofService proofService;
    private VoucherService voucherService;
    private AuditLogService auditLogService;
    private PurchaseContractService service;

    private final List<PurchaseContract> contracts = new ArrayList<>();
    private final List<PurchaseContractTerm> terms = new ArrayList<>();
    private final List<SupplierPayment> payments = new ArrayList<>();
    private final List<SupplierPaymentAlloc> allocs = new ArrayList<>();
    private final List<PurchaseInvoice> invoices = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);
    /** selectCount 的「已存在」开关:流水/发票号/合同号重复测试用 */
    private boolean serialExists;
    private boolean invoiceNoExists;
    private boolean contractNoExists;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(a, PurchaseContract.class);
        TableInfoHelper.initTableInfo(a, PurchaseContractTerm.class);
        TableInfoHelper.initTableInfo(a, SupplierPayment.class);
        TableInfoHelper.initTableInfo(a, SupplierPaymentAlloc.class);
        TableInfoHelper.initTableInfo(a, PurchaseInvoice.class);
    }

    @BeforeEach
    void setUp() {
        contractMapper = mock(PurchaseContractMapper.class);
        termMapper = mock(PurchaseContractTermMapper.class);
        paymentMapper = mock(SupplierPaymentMapper.class);
        allocMapper = mock(SupplierPaymentAllocMapper.class);
        invoiceMapper = mock(PurchaseInvoiceMapper.class);
        supplierMapper = mock(SupplierMapper.class);
        proofService = mock(PayProofService.class);
        voucherService = mock(VoucherService.class);
        auditLogService = mock(AuditLogService.class);
        service = new PurchaseContractService(contractMapper, termMapper, paymentMapper, allocMapper,
                invoiceMapper, supplierMapper, proofService, voucherService, auditLogService,
                mock(top.aole.rent.modules.purchase.mapper.PurchaseInMapper.class),
                mock(top.aole.rent.modules.purchase.mapper.PayableMapper.class),
                mock(top.aole.rent.modules.pcontract.mapper.SupplierPaymentPayableMapper.class),
                mock(top.aole.rent.modules.contract.mapper.ContractMapper.class),
                mock(top.aole.rent.modules.customer.mapper.CustomerMapper.class));
        UserContext.set(new CurrentUser(1005L, "财务", "财务", null));
        Supplier s = new Supplier();
        s.setId(7L);
        s.setName("虚构供应商甲");
        when(supplierMapper.selectById(7L)).thenReturn(s);
        when(contractMapper.selectCount(any())).thenAnswer(i -> contractNoExists ? 1L : 0L);
        when(paymentMapper.selectCount(any())).thenAnswer(i -> serialExists ? 1L : 0L);
        when(invoiceMapper.selectCount(any())).thenAnswer(i -> invoiceNoExists ? 1L : 0L);
        when(contractMapper.insert(any(PurchaseContract.class))).thenAnswer(i -> {
            PurchaseContract c = i.getArgument(0);
            c.setId(ids.incrementAndGet());
            contracts.add(c);
            return 1;
        });
        when(contractMapper.selectById(anyLong())).thenAnswer(i -> contracts.isEmpty() ? null : contracts.get(0));
        when(termMapper.insert(any(PurchaseContractTerm.class))).thenAnswer(i -> {
            PurchaseContractTerm t = i.getArgument(0);
            t.setId(ids.incrementAndGet());
            terms.add(t);
            return 1;
        });
        when(termMapper.selectList(any())).thenAnswer(i -> new ArrayList<>(terms));
        when(paymentMapper.insert(any(SupplierPayment.class))).thenAnswer(i -> {
            SupplierPayment p = i.getArgument(0);
            p.setId(ids.incrementAndGet());
            payments.add(p);
            return 1;
        });
        when(paymentMapper.selectById(anyLong())).thenAnswer(i -> {
            Long id = i.getArgument(0);
            return payments.stream().filter(p -> p.getId().equals(id)).findFirst().orElse(null);
        });
        when(paymentMapper.selectList(any())).thenAnswer(i -> new ArrayList<>(payments));
        when(allocMapper.insert(any(SupplierPaymentAlloc.class))).thenAnswer(i -> {
            SupplierPaymentAlloc a = i.getArgument(0);
            a.setId(ids.incrementAndGet());
            allocs.add(a);
            return 1;
        });
        when(allocMapper.selectList(any())).thenAnswer(i -> new ArrayList<>(allocs));
        when(invoiceMapper.insert(any(PurchaseInvoice.class))).thenAnswer(i -> {
            PurchaseInvoice v = i.getArgument(0);
            v.setId(ids.incrementAndGet());
            invoices.add(v);
            return 1;
        });
        when(invoiceMapper.selectById(anyLong())).thenAnswer(i -> invoices.isEmpty() ? null : invoices.get(0));
        when(invoiceMapper.selectList(any())).thenAnswer(i -> new ArrayList<>(invoices));
        when(voucherService.postSupplierPayment(anyLong(), any(), any(), anyString())).thenReturn(900L);
        when(proofService.countMap(anyString(), any())).thenReturn(Collections.emptyMap());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private PcDtos.TermInput term(String name, String amount) {
        PcDtos.TermInput t = new PcDtos.TermInput();
        t.setStageName(name);
        t.setAmount(new BigDecimal(amount));
        return t;
    }

    /** 虚构合同:含税 100000,节点 40000/30000/30000 */
    private Long createContract() {
        PcDtos.CreateRequest r = new PcDtos.CreateRequest();
        r.setContractNo("TEST-001");
        r.setSupplierId(7L);
        r.setTotalAmount(new BigDecimal("100000"));
        r.setTerms(Arrays.asList(term("首付", "40000"), term("验收", "30000"), term("尾款", "30000")));
        return service.create(r);
    }

    private PcDtos.PaymentRequest payReq(String amount, String serial) {
        PcDtos.PaymentRequest r = new PcDtos.PaymentRequest();
        r.setAmount(new BigDecimal(amount));
        r.setPaidDate(LocalDate.of(2026, 1, 5));
        r.setBankSerial(serial);
        r.setProofFileIds(Collections.singletonList(1L));
        return r;
    }

    private PcDtos.InvoiceRequest invReq(String no, String ex, String tax, String total) {
        PcDtos.InvoiceRequest r = new PcDtos.InvoiceRequest();
        r.setInvoiceNo(no);
        r.setInvoiceType("专票");
        r.setIssueDate(LocalDate.of(2026, 1, 6));
        r.setAmountExTax(new BigDecimal(ex));
        r.setTaxAmount(new BigDecimal(tax));
        r.setTotalAmount(new BigDecimal(total));
        return r;
    }

    @Test
    void 创建合同_节点合计须等于总价() {
        PcDtos.CreateRequest r = new PcDtos.CreateRequest();
        r.setContractNo("TEST-002");
        r.setSupplierId(7L);
        r.setTotalAmount(new BigDecimal("100000"));
        r.setTerms(Arrays.asList(term("首付", "40000"), term("尾款", "50000")));
        BizException e = assertThrows(BizException.class, () -> service.create(r));
        assertTrue(e.getMessage().contains("不一致"));
    }

    @Test
    void 创建合同_按比例算金额() {
        PcDtos.CreateRequest r = new PcDtos.CreateRequest();
        r.setContractNo("TEST-003");
        r.setSupplierId(7L);
        r.setTotalAmount(new BigDecimal("200000"));
        PcDtos.TermInput a = new PcDtos.TermInput();
        a.setStageName("首付");
        a.setRatio(new BigDecimal("0.4"));
        PcDtos.TermInput b = new PcDtos.TermInput();
        b.setStageName("尾款");
        b.setRatio(new BigDecimal("0.6"));
        r.setTerms(Arrays.asList(a, b));
        service.create(r);
        assertEquals(new BigDecimal("80000.00"), terms.get(0).getAmount());
        assertEquals(new BigDecimal("120000.00"), terms.get(1).getAmount());
    }

    @Test
    void 创建合同_合同号重复被拒() {
        contractNoExists = true;
        assertThrows(BizException.class, this::createContract);
    }

    @Test
    void 一笔付款跨节点自动核销() {
        Long pc = createContract();
        service.pay(pc, payReq("55000", "S1"));
        // 首付 40000 全核销,验收 15000
        assertEquals(2, allocs.size());
        assertEquals(new BigDecimal("40000.00"), allocs.get(0).getAmount());
        assertEquals(new BigDecimal("15000.00"), allocs.get(1).getAmount());
        PcDtos.Detail d = service.detail(pc);
        assertEquals(new BigDecimal("0.00"), d.getTerms().get(0).getUnpaid());
        assertEquals(new BigDecimal("15000.00"), d.getTerms().get(1).getUnpaid());
        assertEquals(new BigDecimal("45000.00"), d.getContract().getSummary().getUnpaid());
        assertEquals("执行中", d.getContract().getStatus());
        verify(voucherService).postSupplierPayment(anyLong(), eq(new BigDecimal("55000.00")), any(), anyString());
        assertEquals(900L, payments.get(0).getVoucherId());
    }

    @Test
    void 付清后合同结清() {
        Long pc = createContract();
        service.pay(pc, payReq("100000", "S1"));
        assertEquals("已结清", contracts.get(0).getStatus());
    }

    @Test
    void 累计付款不得超过合同总价() {
        Long pc = createContract();
        service.pay(pc, payReq("90000", "S1"));
        BizException e = assertThrows(BizException.class, () -> service.pay(pc, payReq("20000", "S2")));
        assertTrue(e.getMessage().contains("超过合同总价"));
    }

    @Test
    void 手工核销超节点额被拒() {
        Long pc = createContract();
        PcDtos.PaymentRequest r = payReq("50000", "S1");
        PcDtos.AllocInput a = new PcDtos.AllocInput();
        a.setTermId(terms.get(0).getId());
        a.setAmount(new BigDecimal("50000"));
        r.setAllocs(Collections.singletonList(a));
        BizException e = assertThrows(BizException.class, () -> service.pay(pc, r));
        assertTrue(e.getMessage().contains("会超额"));
    }

    @Test
    void 手工核销合计须等于付款额() {
        Long pc = createContract();
        PcDtos.PaymentRequest r = payReq("50000", "S1");
        PcDtos.AllocInput a = new PcDtos.AllocInput();
        a.setTermId(terms.get(0).getId());
        a.setAmount(new BigDecimal("30000"));
        r.setAllocs(Collections.singletonList(a));
        BizException e = assertThrows(BizException.class, () -> service.pay(pc, r));
        assertTrue(e.getMessage().contains("核销合计"));
    }

    @Test
    void 银行流水重复被拒() {
        Long pc = createContract();
        serialExists = true;
        BizException e = assertThrows(BizException.class, () -> service.pay(pc, payReq("10000", "S1")));
        assertTrue(e.getMessage().contains("流水号"));
    }

    @Test
    void 缺回单时付款失败且不过账() {
        Long pc = createContract();
        doThrow(new BizException(400, "请先上传回单")).when(proofService)
                .bind(anyString(), any(), anyString(), any());
        assertThrows(BizException.class, () -> service.pay(pc, payReq("10000", "S1")));
        // 回单校验先于核销与凭证:没有核销行、没有过账(付款行随事务回滚)
        assertEquals(0, allocs.size());
        verifyNoInteractions(voucherService);
    }

    @Test
    void 收票_价税必须相加相等() {
        Long pc = createContract();
        BizException e = assertThrows(BizException.class,
                () -> service.addInvoice(pc, invReq("INV1", "80000", "10000", "100000")));
        assertTrue(e.getMessage().contains("≠"));
    }

    @Test
    void 收票_号码重复被拒() {
        Long pc = createContract();
        invoiceNoExists = true;
        assertThrows(BizException.class,
                () -> service.addInvoice(pc, invReq("INV1", "88495.58", "11504.42", "100000")));
    }

    @Test
    void 收票_累计不得超过合同总价() {
        Long pc = createContract();
        service.addInvoice(pc, invReq("INV1", "70796.46", "9203.54", "80000"));
        BizException e = assertThrows(BizException.class,
                () -> service.addInvoice(pc, invReq("INV2", "26548.67", "3451.33", "30000")));
        assertTrue(e.getMessage().contains("超过合同总价"));
    }

    @Test
    void 三单对账_已付未收票() {
        Long pc = createContract();
        service.pay(pc, payReq("40000", "S1"));
        service.addInvoice(pc, invReq("INV1", "17699.12", "2300.88", "20000"));
        PcDtos.Summary s = service.detail(pc).getContract().getSummary();
        assertEquals(new BigDecimal("40000.00"), s.getPaid());
        assertEquals(new BigDecimal("20000.00"), s.getInvoiced());
        assertEquals(new BigDecimal("20000.00"), s.getPaidNotInvoiced());
        assertEquals(new BigDecimal("80000.00"), s.getUninvoiced());
    }

    @Test
    void 撤销付款_红冲凭证且不可重复撤销() {
        Long pc = createContract();
        Long pid = service.pay(pc, payReq("40000", "S1"));
        service.voidPayment(pid, null);
        verify(voucherService).reverseBySource(eq("supplier_payment"), eq(pid), anyString());
        assertEquals("撤销", payments.get(0).getStatus());
        assertThrows(BizException.class, () -> service.voidPayment(pid, null));
    }

    @Test
    void 作废发票() {
        Long pc = createContract();
        Long iid = service.addInvoice(pc, invReq("INV1", "88495.58", "11504.42", "100000"));
        service.voidInvoice(iid, null);
        assertEquals("作废", invoices.get(0).getStatus());
    }
}
