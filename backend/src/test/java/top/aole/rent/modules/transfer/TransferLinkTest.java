package top.aole.rent.modules.transfer;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.asset.service.AssetService;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.mapper.ContractAssetMapper;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.contract.service.ContractService;
import top.aole.rent.modules.customer.domain.Customer;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.finance.service.VoucherService;
import top.aole.rent.modules.rule.service.RuleConfigService;
import top.aole.rent.modules.transfer.domain.TransferOrder;
import top.aole.rent.modules.transfer.domain.TransferOrderLine;
import top.aole.rent.modules.transfer.dto.TransferDtos;
import top.aole.rent.modules.transfer.mapper.TransferOrderLineMapper;
import top.aole.rent.modules.transfer.mapper.TransferOrderMapper;
import top.aole.rent.modules.transfer.service.TransferQueryService;
import top.aole.rent.modules.transfer.service.TransferService;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 转让/处置单与设备租赁台账的关联:行上带出设备标签/型号/状态/账面价,单头带出合同与客户;
 * 设备已被删除时退回占位标签而不是让整个详情报错。
 */
class TransferLinkTest {

    private TransferOrderMapper orderMapper;
    private TransferOrderLineMapper lineMapper;
    private ContractMapper contractMapper;
    private CustomerMapper customerMapper;
    private AssetMapper assetMapper;
    private TransferService service;
    private TransferQueryService query;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(a, TransferOrder.class);
        TableInfoHelper.initTableInfo(a, TransferOrderLine.class);
    }

    @BeforeEach
    void setUp() {
        orderMapper = mock(TransferOrderMapper.class);
        lineMapper = mock(TransferOrderLineMapper.class);
        contractMapper = mock(ContractMapper.class);
        customerMapper = mock(CustomerMapper.class);
        assetMapper = mock(AssetMapper.class);
        service = new TransferService(orderMapper, lineMapper, contractMapper,
                mock(ContractAssetMapper.class), mock(ContractService.class), mock(AssetService.class),
                mock(VoucherService.class), mock(RuleConfigService.class), mock(AuditLogService.class),
                assetMapper, customerMapper);
        query = new TransferQueryService(orderMapper, lineMapper);
    }

    private TransferOrder order(long id, Long contractId) {
        TransferOrder o = new TransferOrder();
        o.setId(id);
        o.setNo("TR-2026-00" + id);
        o.setContractId(contractId);
        o.setType("转让");
        o.setAssetCount(1);
        o.setTotalPrice(new BigDecimal("20000.00"));
        o.setTotalGain(new BigDecimal("3000.00"));
        o.setStatus("已完成");
        return o;
    }

    private TransferOrderLine line(long id, long orderId, long assetId) {
        TransferOrderLine l = new TransferOrderLine();
        l.setId(id);
        l.setTransferOrderId(orderId);
        l.setAssetId(assetId);
        l.setBookValue(new BigDecimal("177000.00"));
        l.setTransferPrice(new BigDecimal("20000.00"));
        l.setGain(new BigDecimal("3000.00"));
        l.setNominalFlag(0);
        return l;
    }

    @Test
    void 转让行带出设备标签型号状态与账面价() {
        Asset a = new Asset();
        a.setId(7L);
        a.setSerialNo("AS-007");
        a.setCategory("播种墙");
        a.setModel("播种墙 V2");
        a.setStatus("在租");
        a.setMarketPrice(new BigDecimal("300000.00"));
        when(assetMapper.selectById(7L)).thenReturn(a);
        when(orderMapper.selectById(1L)).thenReturn(order(1L, null));
        when(lineMapper.selectList(any())).thenReturn(Collections.singletonList(line(11L, 1L, 7L)));

        TransferDtos.TransferLineItem row = service.detail(1L).getLines().get(0);
        assertEquals("播种墙 · 播种墙 V2", row.getAssetLabel());
        assertEquals("AS-007", row.getSerialNo());
        assertEquals("播种墙 V2", row.getModel());
        assertEquals("在租", row.getAssetStatus());
        assertEquals(new BigDecimal("177000.00"), row.getBookValue());
    }

    @Test
    void 设备已删时退回占位标签且不抛异常() {
        when(assetMapper.selectById(8L)).thenReturn(null);
        when(orderMapper.selectById(2L)).thenReturn(order(2L, null));
        when(lineMapper.selectList(any())).thenReturn(Collections.singletonList(line(12L, 2L, 8L)));

        TransferDtos.TransferLineItem row = service.detail(2L).getLines().get(0);
        assertEquals("#8", row.getAssetLabel());
        assertNull(row.getSerialNo());
        assertNull(row.getAssetStatus());
        // 账面价是转让当时的快照,存在行上,设备删了也还在
        assertEquals(new BigDecimal("177000.00"), row.getBookValue());
    }

    @Test
    void 单头带出合同号与客户名() {
        Contract c = new Contract();
        c.setId(60L);
        c.setNo("HT-2026-001");
        c.setCustomerId(70L);
        Customer cust = new Customer();
        cust.setId(70L);
        cust.setName("广东云山供应链科技有限公司");
        when(contractMapper.selectById(60L)).thenReturn(c);
        when(customerMapper.selectById(70L)).thenReturn(cust);
        when(orderMapper.selectById(3L)).thenReturn(order(3L, 60L));
        when(lineMapper.selectList(any())).thenReturn(Collections.emptyList());

        TransferDtos.TransferItem it = service.detail(3L).getOrder();
        assertEquals("HT-2026-001", it.getContractNo());
        assertEquals("广东云山供应链科技有限公司", it.getCustomerName());
    }

    @Test
    void 未挂合同的处置单客户名为空() {
        when(orderMapper.selectById(4L)).thenReturn(order(4L, null));
        when(lineMapper.selectList(any())).thenReturn(Collections.emptyList());

        TransferDtos.TransferItem it = service.detail(4L).getOrder();
        assertNull(it.getContractNo());
        assertNull(it.getCustomerName());
    }

    // ============ 设备侧反向:处置记录 ============

    private TransferOrderLine disposalLine(long id, long orderId, long assetId, String price, String gain) {
        TransferOrderLine l = new TransferOrderLine();
        l.setId(id);
        l.setTransferOrderId(orderId);
        l.setAssetId(assetId);
        l.setBookValue(new BigDecimal("17000.00"));
        l.setTransferPrice(new BigDecimal(price));
        l.setGain(new BigDecimal(gain));
        l.setNominalFlag(0);
        return l;
    }

    private TransferOrder disposalOrder(long id, String no, String type, String status, String bizTime) {
        TransferOrder o = new TransferOrder();
        o.setId(id);
        o.setNo(no);
        o.setType(type);
        o.setStatus(status);
        o.setBizTime(java.time.LocalDateTime.parse(bizTime));
        return o;
    }

    @Test
    void 按设备聚合处置记录_业务时间倒序() {
        when(lineMapper.selectList(any())).thenReturn(Arrays.asList(
                disposalLine(21L, 31L, 7L, "10000", "2000"),
                disposalLine(22L, 32L, 7L, "20000", "3000")));
        when(orderMapper.selectBatchIds(any())).thenReturn(Arrays.asList(
                disposalOrder(31L, "TR-2026-001", "转让", "已完成", "2026-01-15T10:00:00"),
                disposalOrder(32L, "TR-2026-002", "二手", "待过账", "2026-03-01T09:00:00")));

        java.util.Map<Long, java.util.List<TransferDtos.DisposalLine>> m =
                query.disposalsByAsset(Collections.singletonList(7L));
        java.util.List<TransferDtos.DisposalLine> d = m.get(7L);
        assertEquals(2, d.size());
        assertEquals("二手", d.get(0).getType());           // 最近的在前
        assertEquals("待过账", d.get(0).getStatus());
        assertEquals("TR-2026-002", d.get(0).getOrderNo());
        assertEquals(new BigDecimal("20000"), d.get(0).getTransferPrice());   // 原样传出行上存的值,不改标度
        assertEquals("转让", d.get(1).getType());
    }

    @Test
    void 处置状态摘要取最近一次_无记录为空() {
        when(lineMapper.selectList(any())).thenReturn(Collections.singletonList(
                disposalLine(21L, 31L, 7L, "10000", "2000")));
        when(orderMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(
                disposalOrder(31L, "TR-2026-001", "报废", "已完成", "2026-01-15T10:00:00")));

        java.util.Map<Long, String> m = query.disposalStatusByAsset(Arrays.asList(7L, 9L));
        assertEquals("报废 · 已完成", m.get(7L));
        assertNull(m.get(9L));
    }

    @Test
    void 处置记录批量查询传空集合不查库() {
        assertEquals(0, query.disposalsByAsset(Collections.emptyList()).size());
        org.mockito.Mockito.verify(lineMapper, org.mockito.Mockito.times(0)).selectList(any());
    }

    @Test
    void 型号为空时标签退回序列号() {
        Asset a = new Asset();
        a.setId(9L);
        a.setSerialNo("AS-009");
        a.setCategory("货架");
        a.setStatus("收回待处置");
        when(assetMapper.selectById(9L)).thenReturn(a);
        when(orderMapper.selectById(5L)).thenReturn(order(5L, null));
        when(lineMapper.selectList(any())).thenReturn(Arrays.asList(line(13L, 5L, 9L)));

        assertEquals("货架 · AS-009", service.detail(5L).getLines().get(0).getAssetLabel());
    }
}
