package top.aole.rent.modules.asset;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.domain.AssetBom;
import top.aole.rent.modules.asset.dto.BomSheetDtos;
import top.aole.rent.modules.asset.mapper.AssetBomMapper;
import top.aole.rent.modules.asset.service.AssetBomExcelService;
import top.aole.rent.modules.file.domain.FileObject;
import top.aole.rent.modules.file.mapper.FileObjectMapper;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配件 BOM 明细导入导出:导出回读(含父子关系/供应商/质保等)、整表替换、有附件时拦截。
 */
class AssetBomExcelTest {

    private AssetBomMapper bomMapper;
    private SupplierMapper supplierMapper;
    private FileObjectMapper fileObjectMapper;
    private AssetBomExcelService service;
    private Asset asset;

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> c : Arrays.asList(AssetBom.class, FileObject.class, Supplier.class)) {
            TableInfoHelper.initTableInfo(assistant, c);
        }
    }

    @BeforeEach
    void setUp() {
        bomMapper = mock(AssetBomMapper.class);
        supplierMapper = mock(SupplierMapper.class);
        fileObjectMapper = mock(FileObjectMapper.class);
        when(fileObjectMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(supplierMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(supplier()));
        when(supplierMapper.selectList(any())).thenReturn(Collections.singletonList(supplier()));
        service = new AssetBomExcelService(bomMapper, supplierMapper, fileObjectMapper, mock(AuditLogService.class));
        UserContext.set(new CurrentUser(1006L, "供应链", "供应链", null));
        asset = new Asset();
        asset.setId(1L);
        asset.setSerialNo("AS20260920001");
    }

    private static Supplier supplier() {
        Supplier s = new Supplier();
        s.setId(9L);
        s.setName("科瑞电控");
        return s;
    }

    private static AssetBom bom(long id, Long parentId, int seq, String name, String qty, String unitCost) {
        AssetBom b = new AssetBom();
        b.setId(id);
        b.setAssetId(1L);
        b.setParentId(parentId);
        b.setSeq(seq);
        b.setName(name);
        b.setQty(new BigDecimal(qty));
        b.setUnitCost(new BigDecimal(unitCost));
        b.setRepairable(1);
        b.setFaultCount(0);
        return b;
    }

    @Test
    void exportedSheetCanBeImportedBackWithTree() throws Exception {
        AssetBom top = bom(10, null, 1, "电控系统", "1", "45000");
        top.setModel("EC-2000");
        top.setSpec("含 PLC / 变频器");
        top.setUnit("套");
        top.setSupplierId(9L);
        top.setLifeYears(new BigDecimal("8"));
        top.setWarrantyUntil(LocalDate.of(2027, 2, 1));
        top.setResidualRate(new BigDecimal("0.08"));
        top.setRemark("贬值快");
        AssetBom child = bom(11, 10L, 1, "PLC控制器", "2", "20000");
        child.setUnit("个");
        child.setRepairable(0);
        child.setFaultCount(3);
        AssetBom manual = bom(12, null, 2, "装配辅料", "1", "0");
        manual.setSubtotalOverride(new BigDecimal("20000"));
        when(bomMapper.selectList(any())).thenReturn(new ArrayList<>(Arrays.asList(top, child, manual)));

        byte[] xlsx = service.export(asset, false);
        List<BomSheetDtos.Row> rows = parse(xlsx);

        assertEquals(3, rows.size());
        BomSheetDtos.Row r1 = rows.get(0);
        assertEquals("电控系统", r1.getName());
        assertEquals("EC-2000", r1.getModel());
        assertEquals("含 PLC / 变频器", r1.getSpec());
        assertNull(r1.getParentSeq());
        assertEquals(0, r1.getUnitCost().compareTo(new BigDecimal("45000")));
        assertEquals(Boolean.FALSE, r1.getAmountManual()); // 数量×单价 对得上
        assertEquals(Long.valueOf(9L), r1.getSupplierId());
        assertEquals(LocalDate.of(2027, 2, 1), r1.getWarrantyUntil());
        assertEquals(0, r1.getResidualRate().compareTo(new BigDecimal("0.08")));

        BomSheetDtos.Row r2 = rows.get(1); // 子件紧跟父件,上级序号指向第 1 行
        assertEquals("PLC控制器", r2.getName());
        assertEquals(Integer.valueOf(1), r2.getParentSeq());
        assertEquals(Boolean.FALSE, r2.getRepairable());
        assertEquals(Integer.valueOf(3), r2.getFaultCount());

        BomSheetDtos.Row r3 = rows.get(2); // 手动合价:金额与 数量×单价 不一致
        assertEquals(Boolean.TRUE, r3.getAmountManual());
        assertEquals(0, r3.getAmount().compareTo(new BigDecimal("20000")));
    }

    @Test
    void importReplacesAllAndRebuildsParentLinks() throws Exception {
        when(bomMapper.selectList(any())).thenReturn(new ArrayList<>(Collections.singletonList(bom(99, null, 1, "老配件", "1", "10"))));
        List<AssetBom> inserted = new ArrayList<>();
        doAnswer(inv -> {
            AssetBom b = inv.getArgument(0);
            b.setId(100L + inserted.size());
            inserted.add(b);
            return 1;
        }).when(bomMapper).insert(any(AssetBom.class));

        List<BomSheetDtos.Row> rows = new ArrayList<>();
        rows.add(row(1, null, "钢构框架", "1", "60000"));
        rows.add(row(2, 1, "立柱", "4", "500"));
        rows.add(row(3, 9, "孤儿行", "1", "1")); // 上级序号在表里找不到
        BomSheetDtos.ImportResult res = service.replaceAll(asset, rows);

        verify(bomMapper).deleteById(99L);
        assertEquals(2, res.getImported());
        assertEquals(1, res.getSkipped());
        assertTrue(res.getMessages().get(0).contains("上级序号"), res.getMessages().toString());
        assertEquals("钢构框架", inserted.get(0).getName());
        assertNull(inserted.get(0).getParentId());
        assertEquals(inserted.get(0).getId(), inserted.get(1).getParentId()); // 子件挂到父件上
    }

    @Test
    void importRefusesWhenBomHasAttachments() {
        AssetBom withFile = bom(20, null, 1, "带附件的配件", "1", "100");
        when(bomMapper.selectList(any())).thenReturn(new ArrayList<>(Collections.singletonList(withFile)));
        FileObject fo = new FileObject();
        fo.setId(5L);
        fo.setBizType("asset_bom");
        fo.setBizId(20L);
        when(fileObjectMapper.selectList(any())).thenReturn(Collections.singletonList(fo));

        BizException e = assertThrows(BizException.class,
                () -> service.replaceAll(asset, Collections.singletonList(row(1, null, "新配件", "1", "1"))));
        assertTrue(e.getMessage().contains("带附件的配件"), e.getMessage());
        verify(bomMapper, org.mockito.Mockito.never()).deleteById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void nonCostRoleCannotImport() {
        UserContext.set(new CurrentUser(2001L, "投资人", "LP", null));
        BizException e = assertThrows(BizException.class, () -> service.importFile(asset, null));
        assertTrue(e.getMessage().contains("无权"), e.getMessage());
    }

    private static BomSheetDtos.Row row(int seq, Integer parentSeq, String name, String qty, String unitCost) {
        BomSheetDtos.Row r = new BomSheetDtos.Row();
        r.setSeq(seq);
        r.setParentSeq(parentSeq);
        r.setName(name);
        r.setQty(new BigDecimal(qty));
        r.setUnitCost(new BigDecimal(unitCost));
        r.setRepairable(true);
        r.setFaultCount(0);
        return r;
    }

    @SuppressWarnings("unchecked")
    private List<BomSheetDtos.Row> parse(byte[] data) throws Exception {
        java.lang.reflect.Method m = AssetBomExcelService.class.getDeclaredMethod("parse", InputStream.class);
        m.setAccessible(true);
        return (List<BomSheetDtos.Row>) m.invoke(service, new ByteArrayInputStream(data));
    }
}
