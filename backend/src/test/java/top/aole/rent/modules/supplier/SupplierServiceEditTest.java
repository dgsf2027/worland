package top.aole.rent.modules.supplier;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import top.aole.rent.modules.asset.domain.AssetBom;
import top.aole.rent.modules.asset.mapper.AssetBomMapper;
import top.aole.rent.modules.asset.mapper.AssetMapper;
import top.aole.rent.modules.customer.mapper.CustomerMapper;
import top.aole.rent.modules.maintenance.domain.Maintenance;
import top.aole.rent.modules.maintenance.mapper.MaintenanceMapper;
import top.aole.rent.modules.purchase.domain.PurchaseIn;
import top.aole.rent.modules.purchase.domain.PurchaseItem;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseItemMapper;
import top.aole.rent.modules.rule.service.RuleConfigService;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.domain.SupplierInspection;
import top.aole.rent.modules.supplier.domain.SupplierSupply;
import top.aole.rent.modules.supplier.dto.SupplierEditDtos;
import top.aole.rent.modules.supplier.mapper.SupplierInspectionMapper;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;
import top.aole.rent.modules.supplier.mapper.SupplierSupplyMapper;
import top.aole.rent.modules.supplier.service.SupplierInspectionService;
import top.aole.rent.modules.supplier.service.SupplierService;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 供应商删除的引用保护,以及无供货项时编辑履约评分自动建代表项。
 */
class SupplierServiceEditTest {

    private SupplierMapper supplierMapper;
    private SupplierSupplyMapper supplyMapper;
    private AssetMapper assetMapper;
    private SupplierService service;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> c : new Class<?>[]{Supplier.class, SupplierSupply.class, SupplierInspection.class, Asset.class,
                AssetBom.class, PurchaseIn.class, PurchaseItem.class, Maintenance.class}) {
            TableInfoHelper.initTableInfo(a, c);
        }
    }

    @BeforeEach
    void setUp() {
        supplierMapper = mock(SupplierMapper.class);
        supplyMapper = mock(SupplierSupplyMapper.class);
        assetMapper = mock(AssetMapper.class);
        AssetBomMapper bomMapper = mock(AssetBomMapper.class);
        PurchaseInMapper purchaseInMapper = mock(PurchaseInMapper.class);
        PurchaseItemMapper purchaseItemMapper = mock(PurchaseItemMapper.class);
        MaintenanceMapper maintenanceMapper = mock(MaintenanceMapper.class);
        SupplierInspectionMapper inspectionMapper = mock(SupplierInspectionMapper.class);
        when(assetMapper.selectCount(any())).thenReturn(0L);
        when(bomMapper.selectCount(any())).thenReturn(0L);
        when(purchaseInMapper.selectCount(any())).thenReturn(0L);
        when(purchaseItemMapper.selectCount(any())).thenReturn(0L);
        when(maintenanceMapper.selectCount(any())).thenReturn(0L);
        when(inspectionMapper.selectCount(any())).thenReturn(0L);
        service = new SupplierService(supplierMapper, supplyMapper, mock(RuleConfigService.class), mock(AuditLogService.class),
                mock(SupplierInspectionService.class), inspectionMapper, assetMapper, bomMapper, purchaseInMapper,
                purchaseItemMapper, maintenanceMapper, mock(CustomerMapper.class));
        UserContext.set(new CurrentUser(1006L, "供应链", "供应链", null));

        Supplier s = new Supplier();
        s.setId(11L);
        s.setName("11");
        s.setMainCategory("播种墙");
        when(supplierMapper.selectById(11L)).thenReturn(s);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void deleteRemovesUnreferencedSupplierWithItsSupplies() {
        service.delete(11L);
        verify(supplyMapper, times(1)).delete(any(Wrapper.class));
        verify(supplierMapper, times(1)).deleteById(11L);
    }

    @Test
    void deleteIsRefusedWhenEquipmentUsesTheSupplier() {
        when(assetMapper.selectCount(any())).thenReturn(2L);
        BizException e = assertThrows(BizException.class, () -> service.delete(11L));
        assertTrue(e.getMessage().contains("2 条设备"));
        verify(supplierMapper, never()).deleteById(anyLong());
    }

    @Test
    void editingScoresWithoutSuppliesCreatesPrimaryRow() {
        when(supplyMapper.selectList(any())).thenReturn(new ArrayList<>());
        SupplierEditDtos.ScoreRequest req = new SupplierEditDtos.ScoreRequest();
        req.setQuality(85);
        req.setDelivery(80);
        req.setService(82);
        req.setPrice(78);
        req.setTerm(80);

        service.updateScores(11L, req);

        ArgumentCaptor<SupplierSupply> created = ArgumentCaptor.forClass(SupplierSupply.class);
        verify(supplyMapper, times(1)).insert(created.capture());
        assertEquals(Integer.valueOf(1), created.getValue().getIsPrimary());
        assertEquals("整机", created.getValue().getItemType());
        assertEquals("播种墙 整机", created.getValue().getItemName());
        verify(supplyMapper, times(1)).update(isNull(), any(Wrapper.class));
    }
}
