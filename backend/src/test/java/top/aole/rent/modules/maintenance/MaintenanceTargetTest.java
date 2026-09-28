package top.aole.rent.modules.maintenance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.domain.AssetBom;
import top.aole.rent.modules.asset.mapper.AssetBomMapper;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.inventory.domain.InvItem;
import top.aole.rent.modules.inventory.dto.InvDtos;
import top.aole.rent.modules.inventory.mapper.InvItemMapper;
import top.aole.rent.modules.inventory.service.InvService;
import top.aole.rent.modules.maintenance.domain.Maintenance;
import top.aole.rent.modules.maintenance.dto.MaintenanceDtos;
import top.aole.rent.modules.maintenance.mapper.MaintenanceMapper;
import top.aole.rent.modules.maintenance.service.MaintenanceService;
import top.aole.rent.modules.rule.service.RuleConfigService;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 维保工单的对象类型维度:设备/仓库物品互斥校验;仓库物品报修走资产管理的出入库流转
 * (库存数量唯一写手仍是 InvService),完工按结果走修好/报废。
 */
class MaintenanceTargetTest {

    private MaintenanceMapper maintenanceMapper;
    private AssetMapper assetMapper;
    private AssetBomMapper bomMapper;
    private InvItemMapper invItemMapper;
    private InvService invService;
    private MaintenanceService service;
    private final AtomicLong ids = new AtomicLong(500);

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(a, Maintenance.class);
        TableInfoHelper.initTableInfo(a, AssetBom.class);
    }

    @BeforeEach
    void setUp() {
        maintenanceMapper = mock(MaintenanceMapper.class);
        assetMapper = mock(AssetMapper.class);
        bomMapper = mock(AssetBomMapper.class);
        invItemMapper = mock(InvItemMapper.class);
        invService = mock(InvService.class);
        service = new MaintenanceService(maintenanceMapper, assetMapper, bomMapper,
                mock(SupplierMapper.class), mock(RuleConfigService.class), invItemMapper, invService);
        // insert 时回填自增 id
        doAnswer(inv -> {
            Maintenance m = inv.getArgument(0);
            m.setId(ids.incrementAndGet());
            return 1;
        }).when(maintenanceMapper).insert(any(Maintenance.class));
        when(maintenanceMapper.selectCount(any())).thenReturn(0L);
    }

    private Asset asset(long id) {
        Asset a = new Asset();
        a.setId(id);
        a.setSerialNo("AS-" + id);
        a.setCategory("播种墙");
        a.setStatus("在租");
        return a;
    }

    private InvItem item(long id, int stock) {
        InvItem it = new InvItem();
        it.setId(id);
        it.setCode("INV-00" + id);
        it.setName("周转箱");
        it.setUnit("个");
        it.setStockQty(stock);
        return it;
    }

    private MaintenanceDtos.CreateRequest req(String targetType) {
        MaintenanceDtos.CreateRequest r = new MaintenanceDtos.CreateRequest();
        r.setTargetType(targetType);
        r.setType("报修");
        r.setFaultDesc("外壳破损");
        return r;
    }

    // ============ 互斥校验 ============

    @Test
    void 设备工单不能同时指定仓库物品() {
        MaintenanceDtos.CreateRequest r = req("asset");
        r.setAssetId(7L);
        r.setInvItemId(5L);
        when(assetMapper.selectById(7L)).thenReturn(asset(7L));
        BizException e = assertThrows(BizException.class, () -> service.create(r));
        assertTrue(e.getMessage().contains("不能同时指定仓库物品"), e.getMessage());
    }

    @Test
    void 仓库物品工单必须选物品() {
        BizException e = assertThrows(BizException.class, () -> service.create(req("inv_item")));
        assertTrue(e.getMessage().contains("请选择仓库物品"), e.getMessage());
    }

    @Test
    void 不填对象类型时默认设备且必须给设备() {
        MaintenanceDtos.CreateRequest r = req(null);
        BizException e = assertThrows(BizException.class, () -> service.create(r));
        assertTrue(e.getMessage().contains("assetId"), e.getMessage());
    }

    @Test
    void 故障配件只能用于设备工单() {
        MaintenanceDtos.CreateRequest r = req("inv_item");
        r.setInvItemId(5L);
        r.setQty(1);
        r.setBomId(9L);
        when(invItemMapper.selectById(5L)).thenReturn(item(5L, 10));
        BizException e = assertThrows(BizException.class, () -> service.create(r));
        assertTrue(e.getMessage().contains("故障配件只能用于设备工单"), e.getMessage());
    }

    @Test
    void 送修数量不能超过库存() {
        MaintenanceDtos.CreateRequest r = req("inv_item");
        r.setInvItemId(5L);
        r.setQty(20);
        when(invItemMapper.selectById(5L)).thenReturn(item(5L, 10));
        BizException e = assertThrows(BizException.class, () -> service.create(r));
        assertTrue(e.getMessage().contains("库存不足"), e.getMessage());
    }

    // ============ 库存联动 ============

    @Test
    void 仓库物品报修调送修流转并记下movement() {
        MaintenanceDtos.CreateRequest r = req("inv_item");
        r.setInvItemId(5L);
        r.setQty(3);
        when(invItemMapper.selectById(5L)).thenReturn(item(5L, 10));
        when(invService.adjust(eq(5L), any(InvDtos.AdjustRequest.class))).thenReturn(88L);

        service.create(r);

        ArgumentCaptor<InvDtos.AdjustRequest> adj = ArgumentCaptor.forClass(InvDtos.AdjustRequest.class);
        verify(invService).adjust(eq(5L), adj.capture());
        assertEquals("送修", adj.getValue().getType());
        assertEquals(3, adj.getValue().getQty());

        ArgumentCaptor<Maintenance> saved = ArgumentCaptor.forClass(Maintenance.class);
        verify(maintenanceMapper).insert(saved.capture());
        assertEquals("inv_item", saved.getValue().getTargetType());
        assertEquals(5L, saved.getValue().getInvItemId());
        assertEquals(3, saved.getValue().getQty());
        assertEquals(88L, saved.getValue().getMovementOutId());
    }

    @Test
    void 设备工单不碰库存流转() {
        MaintenanceDtos.CreateRequest r = req("asset");
        r.setAssetId(7L);
        when(assetMapper.selectById(7L)).thenReturn(asset(7L));

        service.create(r);

        verify(invService, never()).adjust(anyLong(), any());
        ArgumentCaptor<Maintenance> saved = ArgumentCaptor.forClass(Maintenance.class);
        verify(maintenanceMapper).insert(saved.capture());
        assertEquals("asset", saved.getValue().getTargetType());
        assertEquals(1, saved.getValue().getQty());
    }

    private Maintenance invWorkOrder(long id, String status) {
        Maintenance m = new Maintenance();
        m.setId(id);
        m.setNo("MT-" + id);
        m.setTargetType("inv_item");
        m.setInvItemId(5L);
        m.setQty(3);
        m.setType("报修");
        m.setStatus(status);
        m.setInWarranty(0);
        m.setResponsibleParty("我方");
        m.setCost(BigDecimal.ZERO);
        return m;
    }

    @Test
    void 完工修好把数量退回库存() {
        when(maintenanceMapper.selectById(1L)).thenReturn(invWorkOrder(1L, "处理中"));
        when(invItemMapper.selectById(5L)).thenReturn(item(5L, 7));
        when(invService.adjust(eq(5L), any(InvDtos.AdjustRequest.class))).thenReturn(89L);

        MaintenanceDtos.HandleRequest h = new MaintenanceDtos.HandleRequest();
        h.setCost(new BigDecimal("120.00"));
        h.setScrapped(false);
        service.handle(1L, h);

        ArgumentCaptor<InvDtos.AdjustRequest> adj = ArgumentCaptor.forClass(InvDtos.AdjustRequest.class);
        verify(invService).adjust(eq(5L), adj.capture());
        assertEquals("修好", adj.getValue().getType());
        assertEquals(3, adj.getValue().getQty());
    }

    @Test
    void 完工报废从维修中转已报废() {
        when(maintenanceMapper.selectById(2L)).thenReturn(invWorkOrder(2L, "处理中"));
        when(invItemMapper.selectById(5L)).thenReturn(item(5L, 7));
        when(invService.adjust(eq(5L), any(InvDtos.AdjustRequest.class))).thenReturn(90L);

        MaintenanceDtos.HandleRequest h = new MaintenanceDtos.HandleRequest();
        h.setScrapped(true);
        service.handle(2L, h);

        ArgumentCaptor<InvDtos.AdjustRequest> adj = ArgumentCaptor.forClass(InvDtos.AdjustRequest.class);
        verify(invService).adjust(eq(5L), adj.capture());
        assertEquals("报废", adj.getValue().getType());
        assertEquals("维修中", adj.getValue().getFromStatus());
    }

    @Test
    void 设备工单完工不碰库存() {
        Maintenance m = new Maintenance();
        m.setId(3L);
        m.setNo("MT-3");
        m.setTargetType("asset");
        m.setAssetId(7L);
        m.setQty(1);
        m.setType("报修");
        m.setStatus("处理中");
        m.setInWarranty(0);
        m.setResponsibleParty("我方");
        m.setCost(BigDecimal.ZERO);
        when(maintenanceMapper.selectById(3L)).thenReturn(m);
        when(assetMapper.selectById(7L)).thenReturn(asset(7L));

        service.handle(3L, new MaintenanceDtos.HandleRequest());

        verify(invService, never()).adjust(anyLong(), any());
    }
}
