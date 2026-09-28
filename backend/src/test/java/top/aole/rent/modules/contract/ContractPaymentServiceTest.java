package top.aole.rent.modules.contract;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.asset.dto.PaymentTermDtos;
import top.aole.rent.modules.contract.domain.ContractPaymentTerm;
import top.aole.rent.modules.contract.mapper.ContractPaymentTermMapper;
import top.aole.rent.modules.contract.service.ContractPaymentService;
import top.aole.rent.modules.purchase.domain.Payable;
import top.aole.rent.modules.purchase.mapper.PayableMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 合同付款方式:校验(名称/比例/触发点)、已付阶段锁定、默认三段、摘要格式。
 */
class ContractPaymentServiceTest {

    private ContractPaymentTermMapper termMapper;
    private PayableMapper payableMapper;
    private AssetMapper assetMapper;
    private RuleConfigService rules;
    private ContractPaymentService service;
    private final List<ContractPaymentTerm> store = new ArrayList<>();
    private final List<Asset> assetStore = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(700);

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(a, ContractPaymentTerm.class);
        TableInfoHelper.initTableInfo(a, Payable.class);
    }

    @BeforeEach
    void setUp() {
        termMapper = mock(ContractPaymentTermMapper.class);
        payableMapper = mock(PayableMapper.class);
        rules = mock(RuleConfigService.class);
        assetMapper = mock(AssetMapper.class);
        when(assetMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(assetStore));
        service = new ContractPaymentService(termMapper, payableMapper, assetMapper, rules, mock(AuditLogService.class));
        store.clear();
        assetStore.clear();
        Asset a = new Asset();
        a.setId(501L);
        a.setContractId(60L);
        assetStore.add(a);
        doAnswer(inv -> {
            ContractPaymentTerm t = inv.getArgument(0);
            t.setId(ids.incrementAndGet());
            store.add(t);
            return 1;
        }).when(termMapper).insert(any(ContractPaymentTerm.class));
        when(termMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(store));
        when(payableMapper.selectList(any())).thenReturn(Collections.emptyList());
        // rule_config:首付 30% / 验收 60% / 尾款账期 90 天
        when(rules.getValue(eq("payable_stage_ratio"), eq("首付"), any(LocalDate.class)))
                .thenReturn(new BigDecimal("0.3"));
        when(rules.getValue(eq("payable_stage_ratio"), eq("验收"), any(LocalDate.class)))
                .thenReturn(new BigDecimal("0.6"));
        when(rules.getValue(eq("payable_tail_days"), anyString(), any(LocalDate.class)))
                .thenReturn(new BigDecimal("90"));
    }

    private PaymentTermDtos.TermInput term(String name, String ratio, String trigger, int days) {
        return new PaymentTermDtos.TermInput(name, new BigDecimal(ratio), trigger, days);
    }

    // ============ 校验 ============

    @Test
    void 比例合计不足百分之百被拒() {
        BizException e = assertThrows(BizException.class, () -> service.replaceAll(60L,
                Arrays.asList(term("首付", "0.3", "下单", 0), term("尾款", "0.5", "入库", 90))));
        assertTrue(e.getMessage().contains("合计须为 100%"), e.getMessage());
    }

    @Test
    void 阶段名重复被拒() {
        BizException e = assertThrows(BizException.class, () -> service.replaceAll(60L,
                Arrays.asList(term("首付", "0.5", "下单", 0), term("首付", "0.5", "入库", 0))));
        assertTrue(e.getMessage().contains("名称重复"), e.getMessage());
    }

    @Test
    void 触发点只能是下单或入库() {
        BizException e = assertThrows(BizException.class, () -> service.replaceAll(60L,
                Collections.singletonList(term("首付", "1", "验收", 0))));
        assertTrue(e.getMessage().contains("下单 或 入库"), e.getMessage());
    }

    @Test
    void 保留阶段名被拒() {
        BizException e = assertThrows(BizException.class, () -> service.replaceAll(60L,
                Collections.singletonList(term("退款红字", "1", "下单", 0))));
        assertTrue(e.getMessage().contains("系统保留"), e.getMessage());
    }

    @Test
    void 空列表被拒() {
        BizException e = assertThrows(BizException.class, () -> service.replaceAll(60L, new ArrayList<>()));
        assertTrue(e.getMessage().contains("至少设置一段"), e.getMessage());
    }

    @Test
    void 到期天数不能为负() {
        BizException e = assertThrows(BizException.class, () -> service.replaceAll(60L,
                Collections.singletonList(term("首付", "1", "入库", -5))));
        assertTrue(e.getMessage().contains("不能为负"), e.getMessage());
    }

    // ============ 写入与读回 ============

    @Test
    void 保存三段后段序与字段正确() {
        List<ContractPaymentTerm> saved = service.replaceAll(60L, Arrays.asList(
                term("首付", "0.3", "下单", 0), term("验收", "0.6", "入库", 0), term("尾款", "0.1", "入库", 90)));
        assertEquals(3, saved.size());
        assertEquals(1, saved.get(0).getSeq());
        assertEquals("首付", saved.get(0).getStageName());
        assertEquals("下单", saved.get(0).getTriggerPoint());
        assertEquals(3, saved.get(2).getSeq());
        assertEquals(90, saved.get(2).getDueDays());
    }

    @Test
    void 触发点留空默认入库() {
        List<ContractPaymentTerm> saved = service.replaceAll(60L,
                Collections.singletonList(term("全款", "1", null, 0)));
        assertEquals("入库", saved.get(0).getTriggerPoint());
    }

    // ============ 已付阶段锁定 ============

    @Test
    void 已付阶段不能删除() {
        service.replaceAll(60L, Arrays.asList(
                term("首付", "0.3", "下单", 0), term("验收", "0.7", "入库", 0)));
        Payable paid = new Payable();
        paid.setAssetId(501L);
        paid.setTermId(store.get(0).getId());
        paid.setStage("首付");
        paid.setStatus("已付");
        when(payableMapper.selectList(any())).thenReturn(Collections.singletonList(paid));

        BizException e = assertThrows(BizException.class, () -> service.replaceAll(60L,
                Collections.singletonList(term("验收", "1", "入库", 0))));
        assertTrue(e.getMessage().contains("已付款,不能删除或修改比例"), e.getMessage());
    }

    @Test
    void 已付阶段不能改比例() {
        service.replaceAll(60L, Arrays.asList(
                term("首付", "0.3", "下单", 0), term("验收", "0.7", "入库", 0)));
        Payable paid = new Payable();
        paid.setAssetId(501L);
        paid.setTermId(store.get(0).getId());
        paid.setStage("首付");
        paid.setStatus("已付");
        when(payableMapper.selectList(any())).thenReturn(Collections.singletonList(paid));

        BizException e = assertThrows(BizException.class, () -> service.replaceAll(60L,
                Arrays.asList(term("首付", "0.5", "下单", 0), term("验收", "0.5", "入库", 0))));
        assertTrue(e.getMessage().contains("已付款"), e.getMessage());
    }

    @Test
    void 整套替换过一次后已付锁定仍然有效_回归() {
        // 保存一次 → 旧段行被物理删、新段换了 id;此时若按 term_id 判已付,锁定会静默失效
        service.replaceAll(60L, Arrays.asList(
                term("首付", "0.3", "下单", 0), term("验收", "0.7", "入库", 0)));
        Payable paid = new Payable();
        paid.setAssetId(501L);
        paid.setTermId(-999L);      // 故意指向一个已不存在的段
        paid.setStage("首付");
        paid.setStatus("已付");
        when(payableMapper.selectList(any())).thenReturn(Collections.singletonList(paid));

        // 再存一次:首付比例照旧 → 放行
        service.replaceAll(60L, Arrays.asList(
                term("首付", "0.3", "下单", 0), term("验收", "0.4", "入库", 0), term("尾款", "0.3", "入库", 30)));
        // 改首付比例 → 必须仍然被拦
        BizException e = assertThrows(BizException.class, () -> service.replaceAll(60L,
                Arrays.asList(term("首付", "0.6", "下单", 0), term("验收", "0.4", "入库", 0))));
        assertTrue(e.getMessage().contains("已付款"), e.getMessage());
    }

    @Test
    void 已付阶段比例不变时允许改其它段() {
        service.replaceAll(60L, Arrays.asList(
                term("首付", "0.3", "下单", 0), term("验收", "0.4", "入库", 0), term("尾款", "0.3", "入库", 60)));
        Payable paid = new Payable();
        paid.setAssetId(501L);
        paid.setTermId(store.get(0).getId());
        paid.setStage("首付");
        paid.setStatus("已付");
        when(payableMapper.selectList(any())).thenReturn(Collections.singletonList(paid));

        // 首付仍是 30%,把验收/尾款调成 50/20 应放行
        List<ContractPaymentTerm> saved = service.replaceAll(60L, Arrays.asList(
                term("首付", "0.3", "下单", 0), term("验收", "0.5", "入库", 0), term("尾款", "0.2", "入库", 90)));
        assertEquals(3, saved.size());
        assertEquals(new BigDecimal("0.50000000"), saved.get(1).getRatio());
    }

    // ============ 默认三段 / 摘要 ============

    @Test
    void 没设过付款方式时写入默认三段() {
        List<ContractPaymentTerm> t = service.ensureDefault(60L, null, null);
        assertEquals(3, t.size());
        assertEquals("首付", t.get(0).getStageName());
        assertEquals("下单", t.get(0).getTriggerPoint());
        assertEquals("尾款", t.get(2).getStageName());
        assertEquals(90, t.get(2).getDueDays());
    }

    @Test
    void 已设过付款方式时ensureDefault原样返回不覆盖() {
        service.replaceAll(60L, Collections.singletonList(term("全款", "1", "下单", 0)));
        List<ContractPaymentTerm> t = service.ensureDefault(60L, null, null);
        assertEquals(1, t.size());
        assertEquals("全款", t.get(0).getStageName());
    }

    @Test
    void 摘要格式() {
        service.replaceAll(60L, Arrays.asList(
                term("首付", "0.3", "下单", 0), term("验收", "0.6", "入库", 0), term("尾款", "0.1", "入库", 90)));
        assertEquals("首付30%(下单) / 验收60%(入库) / 尾款10%(入库+90天)", service.describe(60L));
    }

    @Test
    void 没设过付款方式时摘要为空且入参列表为空() {
        assertNull(service.describe(60L));
        assertTrue(service.termsAsInput(60L).isEmpty());
    }
}
