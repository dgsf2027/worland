package top.aole.rent.modules.asset;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.domain.AssetPaymentTerm;
import top.aole.rent.modules.asset.dto.PaymentTermDtos;
import top.aole.rent.modules.asset.mapper.AssetPaymentTermMapper;
import top.aole.rent.modules.asset.service.AssetPaymentService;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.domain.PurchaseIn;
import top.aole.rent.modules.purchase.domain.PurchaseItem;
import top.aole.rent.modules.purchase.mapper.PayableMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;

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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 设备合同付款条件:校验、预计付款补差、下单/入库分阶段逐台生成应付、已付阶段锁定。
 */
class AssetPaymentServiceTest {

    private AssetPaymentTermMapper termMapper;
    private PayableMapper payableMapper;
    private PurchaseInMapper purchaseInMapper;
    private AssetPaymentService service;
    private final AtomicLong ids = new AtomicLong(100);

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(a, AssetPaymentTerm.class);
        TableInfoHelper.initTableInfo(a, Payable.class);
    }

    @BeforeEach
    void setUp() {
        termMapper = mock(AssetPaymentTermMapper.class);
        payableMapper = mock(PayableMapper.class);
        purchaseInMapper = mock(PurchaseInMapper.class);
        RuleConfigService rules = mock(RuleConfigService.class);
        when(rules.getValue(anyString(), anyString(), any())).thenThrow(new RuntimeException("无规则,走默认"));
        service = new AssetPaymentService(termMapper, payableMapper, purchaseInMapper, rules, mock(AuditLogService.class));
        doAnswer(inv -> {
            ((AssetPaymentTerm) inv.getArgument(0)).setId(ids.incrementAndGet());
            return 1;
        }).when(termMapper).insert(any(AssetPaymentTerm.class));
        when(payableMapper.selectCount(any())).thenReturn(0L);
        UserContext.set(new CurrentUser(1006L, "供应链", "供应链", null));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void ratiosMustSumTo100PercentAndNamesBeUnique() {
        BizException sum = assertThrows(BizException.class, () -> service.normalize(Arrays.asList(
                term("预付", "0.3", "下单", 0), term("发货", "0.5", "入库", 0))));
        assertTrue(sum.getMessage().contains("合计须为 100%"));
        BizException dup = assertThrows(BizException.class, () -> service.normalize(Arrays.asList(
                term("预付", "0.5", "下单", 0), term("预付", "0.5", "入库", 0))));
        assertTrue(dup.getMessage().contains("重复"));
    }

    @Test
    void expectedAmountsPutRoundingRemainderOnLastStage() {
        List<BigDecimal> amounts = service.expectedAmounts(new BigDecimal("10000"),
                Arrays.asList(new BigDecimal("0.33333333"), new BigDecimal("0.33333333"), new BigDecimal("0.33333334")));
        assertEquals(new BigDecimal("3333.33"), amounts.get(0));
        assertEquals(new BigDecimal("3333.33"), amounts.get(1));
        assertEquals(new BigDecimal("3333.34"), amounts.get(2));
    }

    @Test
    void orderCreatesOrderStagesAndReceiveCreatesReceiveStagesPerAsset() {
        PurchaseIn p = new PurchaseIn();
        p.setId(1L);
        p.setStatus("已下单");
        p.setOrderDate(LocalDate.of(2026, 9, 1));
        PurchaseItem item = new PurchaseItem();
        item.setId(11L);
        item.setSerialNo("WL-001");
        item.setPurchasePrice(new BigDecimal("100000"));

        List<PaymentTermDtos.TermInput> terms = Arrays.asList(
                term("预付", "0.2", "下单", 3),
                term("发货", "0.5", "入库", 0),
                term("质保", "0.3", "入库", 180));
        service.onOrder(p, item, terms);

        ArgumentCaptor<Payable> orderPay = ArgumentCaptor.forClass(Payable.class);
        verify(payableMapper, times(1)).insert(orderPay.capture());
        assertEquals("预付", orderPay.getValue().getStage());
        assertEquals(0, new BigDecimal("20000").compareTo(orderPay.getValue().getAmount()));
        assertEquals(LocalDate.of(2026, 9, 4), orderPay.getValue().getDueDate());
        assertEquals(Long.valueOf(11L), orderPay.getValue().getPurchaseItemId());

        // 入库:条件已挂明细,回填设备并只生成入库阶段
        List<AssetPaymentTerm> saved = new ArrayList<>();
        saved.add(savedTerm(201L, 1, "预付", "0.2", "下单", 3));
        saved.add(savedTerm(202L, 2, "发货", "0.5", "入库", 0));
        saved.add(savedTerm(203L, 3, "质保", "0.3", "入库", 180));
        when(termMapper.selectList(any())).thenReturn(saved);
        p.setStatus("已入库");
        p.setReceiveDate(LocalDate.of(2026, 9, 20));

        service.onReceive(p, item, 501L);

        ArgumentCaptor<Payable> all = ArgumentCaptor.forClass(Payable.class);
        verify(payableMapper, times(3)).insert(all.capture());
        Payable ship = all.getAllValues().get(1);
        Payable warranty = all.getAllValues().get(2);
        assertEquals("发货", ship.getStage());
        assertEquals(Long.valueOf(501L), ship.getAssetId());
        assertEquals(0, new BigDecimal("50000").compareTo(ship.getAmount()));
        assertEquals("质保", warranty.getStage());
        assertEquals(LocalDate.of(2027, 3, 19), warranty.getDueDate());
        assertEquals(0, new BigDecimal("30000").compareTo(warranty.getAmount()));
    }

    @Test
    void paidStageCannotBeChanged() {
        Asset a = new Asset();
        a.setId(501L);
        a.setSerialNo("WL-001");
        a.setPurchasePrice(new BigDecimal("100000"));
        a.setPurchaseInId(1L);
        when(termMapper.selectList(any())).thenReturn(Collections.singletonList(savedTerm(201L, 1, "预付", "0.2", "下单", 0)));
        Payable paid = new Payable();
        paid.setId(9L);
        paid.setAssetId(501L);
        paid.setTermId(201L);
        paid.setStage("预付");
        paid.setAmount(new BigDecimal("20000"));
        paid.setStatus("已付");
        when(payableMapper.selectList(any())).thenReturn(Collections.singletonList(paid));

        BizException e = assertThrows(BizException.class, () -> service.updateTerms(a, Arrays.asList(
                term("预付", "0.3", "下单", 0), term("尾款", "0.7", "入库", 30))));
        assertTrue(e.getMessage().contains("已付款"));
        verify(termMapper, never()).deleteById(anyLong());
    }

    private PaymentTermDtos.TermInput term(String name, String ratio, String trigger, int days) {
        return new PaymentTermDtos.TermInput(name, new BigDecimal(ratio), trigger, days);
    }

    private AssetPaymentTerm savedTerm(Long id, int seq, String name, String ratio, String trigger, int days) {
        AssetPaymentTerm t = new AssetPaymentTerm();
        t.setId(id);
        t.setSeq(seq);
        t.setStageName(name);
        t.setRatio(new BigDecimal(ratio));
        t.setTriggerPoint(trigger);
        t.setDueDays(days);
        t.setPurchaseItemId(11L);
        return t;
    }
}
