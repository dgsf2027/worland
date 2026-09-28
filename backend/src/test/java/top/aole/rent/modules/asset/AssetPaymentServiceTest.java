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
import top.aole.rent.modules.asset.domain.AssetPaymentTerm;
import top.aole.rent.modules.asset.mapper.AssetPaymentTermMapper;
import top.aole.rent.modules.asset.service.AssetPaymentService;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 逐台应付(V118 口径):付款条件取自合同,下单即生成全部阶段;
 * 未入库的「入库」阶段到期日按预计入库日推算并标预估,入库时改写为真实日期并清标记。
 */
class AssetPaymentServiceTest {

    private PayableMapper payableMapper;
    private PurchaseInMapper purchaseInMapper;
    private ContractPaymentService contractPaymentService;
    private ContractPaymentTermMapper contractTermMapper;
    private AssetPaymentService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(a, AssetPaymentTerm.class);
        TableInfoHelper.initTableInfo(a, Payable.class);
        TableInfoHelper.initTableInfo(a, ContractPaymentTerm.class);
    }

    @BeforeEach
    void setUp() {
        payableMapper = mock(PayableMapper.class);
        purchaseInMapper = mock(PurchaseInMapper.class);
        contractPaymentService = mock(ContractPaymentService.class);
        contractTermMapper = mock(ContractPaymentTermMapper.class);
        RuleConfigService rules = mock(RuleConfigService.class);
        when(rules.getValue(anyString(), anyString(), any())).thenThrow(new RuntimeException("无规则,走默认"));
        service = new AssetPaymentService(mock(AssetPaymentTermMapper.class), payableMapper, purchaseInMapper,
                rules, mock(AuditLogService.class), contractPaymentService, contractTermMapper);
        when(payableMapper.selectCount(any())).thenReturn(0L);
        when(payableMapper.selectList(any())).thenReturn(Collections.emptyList());
        UserContext.set(new CurrentUser(1006L, "供应链", "供应链", null));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private ContractPaymentTerm term(long id, int seq, String name, String ratio, String trigger, int days) {
        ContractPaymentTerm t = new ContractPaymentTerm();
        t.setId(id);
        t.setContractId(60L);
        t.setSeq(seq);
        t.setStageName(name);
        t.setRatio(new BigDecimal(ratio));
        t.setTriggerPoint(trigger);
        t.setDueDays(days);
        return t;
    }

    /** 默认三段:首付20%(下单+3天) / 发货50%(入库) / 质保30%(入库+180天) */
    private List<ContractPaymentTerm> threeStages() {
        return Arrays.asList(
                term(201L, 1, "预付", "0.2", "下单", 3),
                term(202L, 2, "发货", "0.5", "入库", 0),
                term(203L, 3, "质保", "0.3", "入库", 180));
    }

    private PurchaseIn purchase(String orderDate, String expectReceive, String receiveDate) {
        PurchaseIn p = new PurchaseIn();
        p.setId(1L);
        p.setContractId(60L);
        p.setStatus(receiveDate == null ? "已下单" : "已入库");
        p.setOrderDate(orderDate == null ? null : LocalDate.parse(orderDate));
        p.setExpectReceiveDate(expectReceive == null ? null : LocalDate.parse(expectReceive));
        p.setReceiveDate(receiveDate == null ? null : LocalDate.parse(receiveDate));
        return p;
    }

    private PurchaseItem item() {
        PurchaseItem it = new PurchaseItem();
        it.setId(11L);
        it.setSerialNo("WL-001");
        it.setPurchasePrice(new BigDecimal("100000"));
        return it;
    }

    // ============ 预计付款金额 ============

    @Test
    void 预计付款金额末段补差() {
        List<BigDecimal> amounts = service.expectedAmounts(new BigDecimal("10000"),
                Arrays.asList(new BigDecimal("0.33333333"), new BigDecimal("0.33333333"), new BigDecimal("0.33333334")));
        assertEquals(new BigDecimal("3333.33"), amounts.get(0));
        assertEquals(new BigDecimal("3333.33"), amounts.get(1));
        assertEquals(new BigDecimal("3333.34"), amounts.get(2));
    }

    // ============ 下单即全量生成 ============

    @Test
    void 下单一次生成全部阶段_入库阶段用预计入库日并标预估() {
        when(contractPaymentService.terms(60L)).thenReturn(threeStages());
        service.onOrder(purchase("2026-09-01", "2026-09-30", null), item(), 501L, 60L);

        ArgumentCaptor<Payable> cap = ArgumentCaptor.forClass(Payable.class);
        verify(payableMapper, times(3)).insert(cap.capture());
        List<Payable> got = cap.getAllValues();

        // 预付:下单日 + 3 天,不是预估
        assertEquals("预付", got.get(0).getStage());
        assertEquals(LocalDate.parse("2026-09-04"), got.get(0).getDueDate());
        assertEquals(Integer.valueOf(0), got.get(0).getDueProvisional());
        assertEquals(0, new BigDecimal("20000").compareTo(got.get(0).getAmount()));

        // 发货:预计入库日 + 0 天,预估
        assertEquals("发货", got.get(1).getStage());
        assertEquals(LocalDate.parse("2026-09-30"), got.get(1).getDueDate());
        assertEquals(Integer.valueOf(1), got.get(1).getDueProvisional());
        assertEquals(0, new BigDecimal("50000").compareTo(got.get(1).getAmount()));

        // 质保:预计入库日 + 180 天,预估
        assertEquals("质保", got.get(2).getStage());
        assertEquals(LocalDate.parse("2027-03-29"), got.get(2).getDueDate());
        assertEquals(Integer.valueOf(1), got.get(2).getDueProvisional());
        assertEquals(0, new BigDecimal("30000").compareTo(got.get(2).getAmount()));

        // 每笔都挂了设备与合同段
        got.forEach(p -> {
            assertEquals(Long.valueOf(501L), p.getAssetId());
            assertEquals(Long.valueOf(11L), p.getPurchaseItemId());
            assertEquals("待付", p.getStatus());
        });
    }

    @Test
    void 缺预计入库日时退回下单日() {
        when(contractPaymentService.terms(60L)).thenReturn(threeStages());
        service.onOrder(purchase("2026-09-01", null, null), item(), 501L, 60L);

        ArgumentCaptor<Payable> cap = ArgumentCaptor.forClass(Payable.class);
        verify(payableMapper, times(3)).insert(cap.capture());
        assertEquals(LocalDate.parse("2026-09-01"), cap.getAllValues().get(1).getDueDate());
        assertEquals(Integer.valueOf(1), cap.getAllValues().get(1).getDueProvisional());
    }

    @Test
    void 下单时已入库的采购单直接用真实入库日且不标预估() {
        when(contractPaymentService.terms(60L)).thenReturn(threeStages());
        service.onOrder(purchase("2026-09-01", "2026-09-30", "2026-09-20"), item(), 501L, 60L);

        ArgumentCaptor<Payable> cap = ArgumentCaptor.forClass(Payable.class);
        verify(payableMapper, times(3)).insert(cap.capture());
        assertEquals(LocalDate.parse("2026-09-20"), cap.getAllValues().get(1).getDueDate());
        assertEquals(Integer.valueOf(0), cap.getAllValues().get(1).getDueProvisional());
    }

    @Test
    void 合同没设付款方式时拒绝下单() {
        when(contractPaymentService.terms(60L)).thenReturn(new ArrayList<>());
        BizException e = assertThrows(BizException.class,
                () -> service.onOrder(purchase("2026-09-01", "2026-09-30", null), item(), 501L, 60L));
        assertTrue(e.getMessage().contains("合同还没设置付款方式"), e.getMessage());
        verify(payableMapper, never()).insert(any());
    }

    @Test
    void 已红冲的采购单不生成应付() {
        when(contractPaymentService.terms(60L)).thenReturn(threeStages());
        PurchaseIn p = purchase("2026-09-01", "2026-09-30", null);
        p.setStatus("已红冲");
        service.onOrder(p, item(), 501L, 60L);
        verify(payableMapper, never()).insert(any());
    }

    // ============ 入库兑现到期日 ============

    private Payable provisional(long id, long termId, String stage, String due) {
        Payable p = new Payable();
        p.setId(id);
        p.setAssetId(501L);
        p.setTermId(termId);
        p.setStage(stage);
        p.setStatus("待付");
        p.setDueDate(LocalDate.parse(due));
        p.setDueProvisional(1);
        p.setAmount(new BigDecimal("50000"));
        return p;
    }

    @Test
    void 入库把预估到期日改写为真实入库日并清标记_不新增应付() {
        Payable firstPay = new Payable();
        firstPay.setId(8L);
        firstPay.setAssetId(501L);
        firstPay.setTermId(201L);
        firstPay.setStage("预付");
        firstPay.setStatus("待付");
        firstPay.setDueDate(LocalDate.parse("2026-09-04"));
        firstPay.setDueProvisional(0);
        when(payableMapper.selectList(any())).thenReturn(Arrays.asList(
                firstPay,
                provisional(9L, 202L, "发货", "2026-09-30"),
                provisional(10L, 203L, "质保", "2027-03-29")));
        when(contractTermMapper.selectById(202L)).thenReturn(term(202L, 2, "发货", "0.5", "入库", 0));
        when(contractTermMapper.selectById(203L)).thenReturn(term(203L, 3, "质保", "0.3", "入库", 180));

        service.onReceive(purchase("2026-09-01", "2026-09-30", "2026-09-20"), item(), 501L);

        // 不新增应付,只改写(1 次回填 asset_id + 2 次到期日改写)
        verify(payableMapper, never()).insert(any());
        verify(payableMapper, times(3)).update(any(), any());
    }

    @Test
    void 入库时该设备一条应付都没有则按合同付款方式补生成() {
        when(payableMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(contractPaymentService.terms(60L)).thenReturn(threeStages());

        service.onReceive(purchase("2026-09-01", "2026-09-30", "2026-09-20"), item(), 501L);

        ArgumentCaptor<Payable> cap = ArgumentCaptor.forClass(Payable.class);
        verify(payableMapper, times(3)).insert(cap.capture());
        // 已入库 → 全部按真实入库日,无预估
        cap.getAllValues().forEach(p -> assertEquals(Integer.valueOf(0), p.getDueProvisional()));
        assertEquals(LocalDate.parse("2026-09-20"), cap.getAllValues().get(1).getDueDate());
    }

    // ============ 付款条件摘要走合同 ============

    @Test
    void 付款条件摘要取自合同() {
        when(contractPaymentService.describe(60L)).thenReturn("预付20%(下单+3天) / 发货50%(入库)");
        assertEquals("预付20%(下单+3天) / 发货50%(入库)", service.describeTerms(60L));
    }
}
