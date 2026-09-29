package top.aole.rent.modules.purchase;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.asset.service.AssetPaymentService;
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.billing.service.RentCoverageService;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.contract.service.ContractPaymentService;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.finance.service.VoucherService;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.domain.PurchaseIn;
import top.aole.rent.modules.purchase.domain.PurchaseItem;
import top.aole.rent.modules.purchase.dto.PurchaseEditDtos;
import top.aole.rent.modules.purchase.mapper.PayableMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseItemMapper;
import top.aole.rent.modules.purchase.service.PurchaseService;
import top.aole.rent.modules.rule.service.RuleConfigService;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购应付整单编辑:单头改动与预估到期日联动、应付金额手工调整留标记、
 * 登记付款(含部分付款拆行)与撤销、已付款设备不允许从单上移除、已红冲只读。
 */
class PurchaseEditTest {

    private PurchaseInMapper purchaseInMapper;
    private PurchaseItemMapper purchaseItemMapper;
    private PayableMapper payableMapper;
    private ContractMapper contractMapper;
    private AssetMapper assetMapper;
    private AssetService assetService;
    private AssetPaymentService paymentService;
    private PurchaseService service;
    private final List<Payable> payables = new ArrayList<>();

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(a, PurchaseIn.class);
        TableInfoHelper.initTableInfo(a, PurchaseItem.class);
        TableInfoHelper.initTableInfo(a, Payable.class);
    }

    @BeforeEach
    void setUp() {
        purchaseInMapper = mock(PurchaseInMapper.class);
        purchaseItemMapper = mock(PurchaseItemMapper.class);
        payableMapper = mock(PayableMapper.class);
        contractMapper = mock(ContractMapper.class);
        assetMapper = mock(AssetMapper.class);
        assetService = mock(AssetService.class);
        paymentService = mock(AssetPaymentService.class);
        service = new PurchaseService(purchaseInMapper, purchaseItemMapper, payableMapper, contractMapper,
                mock(CustomerMapper.class), mock(SupplierMapper.class), assetMapper, assetService,
                paymentService, mock(VoucherService.class), mock(RuleConfigService.class),
                mock(AuditLogService.class), mock(RentCoverageService.class), mock(ContractPaymentService.class));
        payables.clear();
        when(payableMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(payables));
        when(payableMapper.selectCount(any())).thenReturn(0L);
        when(purchaseItemMapper.selectList(any())).thenReturn(new ArrayList<>());
        doAnswer(inv -> {
            ((Payable) inv.getArgument(0)).setId(900L + payables.size());
            payables.add(inv.getArgument(0));
            return 1;
        }).when(payableMapper).insert(any(Payable.class));
    }

    private PurchaseIn purchase(String status, String orderDate, String expectReceive, String receiveDate) {
        PurchaseIn p = new PurchaseIn();
        p.setId(1L);
        p.setNo("CG-2026-001");
        p.setContractId(60L);
        p.setStatus(status);
        p.setTotalAmount(new BigDecimal("219036.00"));
        p.setOrderDate(orderDate == null ? null : LocalDate.parse(orderDate));
        p.setExpectReceiveDate(expectReceive == null ? null : LocalDate.parse(expectReceive));
        p.setReceiveDate(receiveDate == null ? null : LocalDate.parse(receiveDate));
        when(purchaseInMapper.selectById(1L)).thenReturn(p);
        return p;
    }

    private Payable payable(long id, String stage, String amount, String status, String due, int provisional) {
        Payable pay = new Payable();
        pay.setId(id);
        pay.setPurchaseInId(1L);
        pay.setAssetId(501L);
        pay.setPurchaseItemId(11L);
        pay.setTermId(201L);
        pay.setStage(stage);
        pay.setAmount(new BigDecimal(amount));
        pay.setStatus(status);
        pay.setDueDate(LocalDate.parse(due));
        pay.setDueProvisional(provisional);
        pay.setAmountManual(0);
        when(payableMapper.selectById(id)).thenReturn(pay);
        payables.add(pay);
        return pay;
    }

    // ============ 单头 ============

    @Test
    void 改单号会查重() {
        purchase("已下单", "2026-03-01", "2026-03-31", null);
        PurchaseIn other = new PurchaseIn();
        other.setId(2L);
        other.setNo("CG-DUP");
        when(purchaseInMapper.selectOne(any())).thenReturn(other);

        PurchaseEditDtos.HeaderRequest req = new PurchaseEditDtos.HeaderRequest();
        req.setNo("CG-DUP");
        BizException e = assertThrows(BizException.class, () -> service.editHeader(1L, req));
        assertTrue(e.getMessage().contains("采购单号已存在"), e.getMessage());
    }

    @Test
    void 改预计入库日会重算预估到期的待付应付() {
        purchase("已下单", "2026-03-01", "2026-03-31", null);
        payable(1L, "首付", "65710.80", "待付", "2026-03-01", 0);      // 不是预估,不动
        payable(2L, "验收", "131421.60", "待付", "2026-03-31", 1);     // 预估,应重算
        payable(3L, "尾款", "21903.60", "待付", "2026-06-29", 1);      // 预估,账期 90 天
        when(paymentService.dueDaysOfTerm(201L)).thenReturn(0, 90);
        when(purchaseInMapper.selectOne(any())).thenReturn(null);

        PurchaseEditDtos.HeaderRequest req = new PurchaseEditDtos.HeaderRequest();
        req.setExpectReceiveDate(LocalDate.parse("2026-05-10"));
        service.editHeader(1L, req);

        // 两笔预估的被改写(update 调用 2 次),首付那笔不动
        verify(payableMapper, times(2)).update(any(), any());
    }

    @Test
    void 已红冲的单不可编辑() {
        purchase("已红冲", "2026-03-01", "2026-03-31", null);
        PurchaseEditDtos.HeaderRequest req = new PurchaseEditDtos.HeaderRequest();
        req.setRemark("改一下");
        BizException e = assertThrows(BizException.class, () -> service.editHeader(1L, req));
        assertTrue(e.getMessage().contains("已退货红冲,不可再编辑"), e.getMessage());
    }

    // ============ 应付编辑 ============

    @Test
    void 改应付金额会打上手工调整标记() {
        purchase("已下单", "2026-03-01", "2026-03-31", null);
        payable(1L, "首付", "65710.80", "待付", "2026-03-01", 0);

        PurchaseEditDtos.PayableRequest req = new PurchaseEditDtos.PayableRequest();
        req.setAmount(new BigDecimal("60000"));
        service.editPayable(1L, req);

        ArgumentCaptor<Payable> cap = ArgumentCaptor.forClass(Payable.class);
        verify(payableMapper).updateById(cap.capture());
        assertEquals(new BigDecimal("60000.00"), cap.getValue().getAmount());
        assertEquals(Integer.valueOf(1), cap.getValue().getAmountManual());
    }

    @Test
    void 手工指定到期日会清掉预估标记() {
        purchase("已下单", "2026-03-01", "2026-03-31", null);
        payable(2L, "验收", "131421.60", "待付", "2026-03-31", 1);

        PurchaseEditDtos.PayableRequest req = new PurchaseEditDtos.PayableRequest();
        req.setDueDate(LocalDate.parse("2026-04-15"));
        service.editPayable(2L, req);

        ArgumentCaptor<Payable> cap = ArgumentCaptor.forClass(Payable.class);
        verify(payableMapper).updateById(cap.capture());
        assertEquals(LocalDate.parse("2026-04-15"), cap.getValue().getDueDate());
        assertEquals(Integer.valueOf(0), cap.getValue().getDueProvisional());
    }

    @Test
    void 应付金额不能改成负数() {
        purchase("已下单", "2026-03-01", "2026-03-31", null);
        payable(1L, "首付", "65710.80", "待付", "2026-03-01", 0);
        PurchaseEditDtos.PayableRequest req = new PurchaseEditDtos.PayableRequest();
        req.setAmount(new BigDecimal("-100"));
        BizException e = assertThrows(BizException.class, () -> service.editPayable(1L, req));
        assertTrue(e.getMessage().contains("不能为负"), e.getMessage());
    }

    @Test
    void 已红冲的应付行不可编辑() {
        purchase("已下单", "2026-03-01", "2026-03-31", null);
        payable(9L, "退款红字", "-65710.80", "红冲", "2026-03-01", 0);
        PurchaseEditDtos.PayableRequest req = new PurchaseEditDtos.PayableRequest();
        req.setRemark("x");
        BizException e = assertThrows(BizException.class, () -> service.editPayable(9L, req));
        assertTrue(e.getMessage().contains("已红冲的应付行不可编辑"), e.getMessage());
    }

    // ============ 登记付款 ============

    @Test
    void 全额付款整行置已付() {
        purchase("已下单", "2026-03-01", "2026-03-31", null);
        payable(1L, "首付", "65710.80", "待付", "2026-03-01", 0);

        PurchaseEditDtos.PayRequest req = new PurchaseEditDtos.PayRequest();
        req.setPaidAmount(new BigDecimal("65710.80"));
        req.setPaidDate(LocalDate.parse("2026-03-05"));
        service.payPayable(1L, req);

        verify(payableMapper, never()).insert(any());   // 全额付,不拆行
        ArgumentCaptor<Payable> cap = ArgumentCaptor.forClass(Payable.class);
        verify(payableMapper).updateById(cap.capture());
        assertEquals("已付", cap.getValue().getStatus());
        assertEquals(LocalDate.parse("2026-03-05"), cap.getValue().getPaidDate());
    }

    @Test
    void 部分付款把差额拆成一行继续待付() {
        purchase("已下单", "2026-03-01", "2026-03-31", null);
        payable(1L, "首付", "65710.80", "待付", "2026-03-01", 0);

        PurchaseEditDtos.PayRequest req = new PurchaseEditDtos.PayRequest();
        req.setPaidAmount(new BigDecimal("50000"));
        service.payPayable(1L, req);

        ArgumentCaptor<Payable> ins = ArgumentCaptor.forClass(Payable.class);
        verify(payableMapper).insert(ins.capture());
        assertEquals(new BigDecimal("15710.80"), ins.getValue().getAmount());
        assertEquals("待付", ins.getValue().getStatus());
        assertEquals("首付", ins.getValue().getStage());
        assertEquals(Integer.valueOf(1), ins.getValue().getAmountManual());

        ArgumentCaptor<Payable> upd = ArgumentCaptor.forClass(Payable.class);
        verify(payableMapper).updateById(upd.capture());
        assertEquals(new BigDecimal("50000.00"), upd.getValue().getAmount());
        assertEquals("已付", upd.getValue().getStatus());
    }

    @Test
    void 实付超过应付被拒() {
        purchase("已下单", "2026-03-01", "2026-03-31", null);
        payable(1L, "首付", "65710.80", "待付", "2026-03-01", 0);
        PurchaseEditDtos.PayRequest req = new PurchaseEditDtos.PayRequest();
        req.setPaidAmount(new BigDecimal("70000"));
        BizException e = assertThrows(BizException.class, () -> service.payPayable(1L, req));
        assertTrue(e.getMessage().contains("超过本笔应付"), e.getMessage());
    }

    @Test
    void 已付的不能重复登记付款() {
        purchase("已下单", "2026-03-01", "2026-03-31", null);
        payable(1L, "首付", "65710.80", "已付", "2026-03-01", 0);
        PurchaseEditDtos.PayRequest req = new PurchaseEditDtos.PayRequest();
        req.setPaidAmount(new BigDecimal("100"));
        BizException e = assertThrows(BizException.class, () -> service.payPayable(1L, req));
        assertTrue(e.getMessage().contains("仅待付可登记付款"), e.getMessage());
    }

    @Test
    void 撤销付款退回待付并清实付日() {
        purchase("已下单", "2026-03-01", "2026-03-31", null);
        Payable pay = payable(1L, "首付", "65710.80", "已付", "2026-03-01", 0);
        pay.setPaidDate(LocalDate.parse("2026-03-05"));

        service.unpayPayable(1L);
        verify(payableMapper).update(any(), any());
    }

    @Test
    void 未付的不能撤销付款() {
        purchase("已下单", "2026-03-01", "2026-03-31", null);
        payable(1L, "首付", "65710.80", "待付", "2026-03-01", 0);
        BizException e = assertThrows(BizException.class, () -> service.unpayPayable(1L));
        assertTrue(e.getMessage().contains("仅已付可撤销"), e.getMessage());
    }

    // ============ 明细增减 ============

    @Test
    void 移除已付款的设备被拒() {
        PurchaseIn p = purchase("已下单", "2026-03-01", "2026-03-31", null);
        Contract c = new Contract();
        c.setId(60L);
        c.setNo("HT-2026-001");
        when(contractMapper.selectById(60L)).thenReturn(c);
        PurchaseItem pi = new PurchaseItem();
        pi.setId(11L);
        pi.setPurchaseInId(1L);
        pi.setAssetId(501L);
        when(purchaseItemMapper.selectList(any())).thenReturn(Collections.singletonList(pi));
        Asset a = new Asset();
        a.setId(501L);
        a.setSerialNo("AS-501");
        a.setCategory("播种墙");
        when(assetMapper.selectById(501L)).thenReturn(a);
        when(payableMapper.selectCount(any())).thenReturn(1L);   // 该设备有已付应付

        PurchaseEditDtos.ItemsRequest req = new PurchaseEditDtos.ItemsRequest();
        req.setAssetIds(Arrays.asList(502L));   // 要把 501 换掉
        BizException e = assertThrows(BizException.class, () -> service.replaceItems(1L, req));
        assertTrue(e.getMessage().contains("不能从本单移除"), e.getMessage());
        assertNull(p.getReceiveDate());
        verify(assetService, never()).releaseOnPurchaseReturn(anyLong(), anyLong());
    }

    @Test
    void 明细不能清空() {
        purchase("已下单", "2026-03-01", "2026-03-31", null);
        Contract c = new Contract();
        c.setId(60L);
        when(contractMapper.selectById(60L)).thenReturn(c);
        PurchaseEditDtos.ItemsRequest req = new PurchaseEditDtos.ItemsRequest();
        req.setAssetIds(new ArrayList<>());
        BizException e = assertThrows(BizException.class, () -> service.replaceItems(1L, req));
        assertTrue(e.getMessage().contains("至少保留 1 台设备"), e.getMessage());
    }
}
