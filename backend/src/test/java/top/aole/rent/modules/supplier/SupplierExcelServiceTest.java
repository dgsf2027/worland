package top.aole.rent.modules.supplier;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.domain.SupplierSupply;
import top.aole.rent.modules.supplier.service.SupplierExcelService;
import top.aole.rent.modules.supplier.service.SupplierService;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 供应商两张表导出 → 导回解析:字段、百分比、是/否、代表项、成本打码。
 */
class SupplierExcelServiceTest {

    @Test
    void exportedWorkbookParsesBackToSameValues() throws Exception {
        SupplierService svc = mock(SupplierService.class);
        Supplier s = new Supplier();
        s.setId(4L);
        s.setName("科瑞电控");
        s.setContact("陈工");
        s.setPhone("13800000004");
        s.setMainCategory("配件");
        s.setStatus("备供");

        SupplierSupply primary = supply(40L, "电控系统", "配件");
        primary.setQuotePrice(new BigDecimal("26000"));
        primary.setFirstPayRatio(new BigDecimal("0.40000000"));
        primary.setAccountDays(45);
        primary.setScoreQuality(85);
        primary.setScoreDelivery(80);
        primary.setScoreService(82);
        primary.setScorePrice(78);
        primary.setScoreTerm(80);
        primary.setBomEstimate(new BigDecimal("22000"));
        primary.setIsPrimary(1);
        SupplierSupply other = supply(41L, "传感模组", "配件");
        other.setCanSingleBuy(0);

        Map<Long, List<SupplierSupply>> supplies = new LinkedHashMap<>();
        supplies.put(4L, Arrays.asList(primary, other));
        when(svc.canSeeCostNow()).thenReturn(true);
        when(svc.allSuppliers()).thenReturn(Collections.singletonList(s));
        when(svc.suppliesBySupplier()).thenReturn(supplies);
        when(svc.primaryOf(any())).thenReturn(primary);
        when(svc.weightedTotal(primary)).thenReturn(81);

        SupplierExcelService excel = new SupplierExcelService(svc);
        byte[] xlsx = excel.export(false);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Row r = wb.getSheet("供应商").getRow(1);
            assertEquals("科瑞电控", r.getCell(0).getStringCellValue());
            assertEquals(81.0, r.getCell(12).getNumericCellValue(), 0.001);
            Row m = wb.getSheet("供货矩阵").getRow(1);
            assertEquals("40%", m.getCell(5).getStringCellValue());
            assertEquals("偏高(疑虚高)", m.getCell(14).getStringCellValue()); // 26000 > 22000×1.1
        }

        Object parsed = parse(excel, xlsx);
        List<SupplierService.SupplierSheetRow> sup = field(parsed, "suppliers");
        List<SupplierService.SupplySheetRow> sp = field(parsed, "supplies");

        assertEquals(1, sup.size());
        SupplierService.SupplierSheetRow a = sup.get(0);
        assertEquals("科瑞电控", a.getName());
        assertEquals("13800000004", a.getPhone());
        assertEquals("备供", a.getStatus());
        assertTrue(a.getPresent().contains("scores"));
        assertEquals(Integer.valueOf(85), a.getQuality());
        assertEquals(Integer.valueOf(80), a.getTerm());
        assertTrue(a.getWarnings().isEmpty());

        assertEquals(2, sp.size());
        SupplierService.SupplySheetRow p = sp.get(0);
        assertEquals("电控系统", p.getItemName());
        assertEquals(0, new BigDecimal("0.4").compareTo(p.getFirstPayRatio()));
        assertEquals(Integer.valueOf(45), p.getAccountDays());
        assertEquals(Integer.valueOf(1), p.getPrimary());
        assertEquals(0, new BigDecimal("22000").compareTo(p.getBomEstimate()));
        assertFalse(p.getPresent().contains("verdict"));
        assertEquals(Integer.valueOf(0), sp.get(1).getCanSingleBuy());
        assertEquals(Integer.valueOf(0), sp.get(1).getPrimary());
    }

    @Test
    void maskedCostColumnsAreNotImportedBack() throws Exception {
        SupplierService svc = mock(SupplierService.class);
        Supplier s = new Supplier();
        s.setId(1L);
        s.setName("恒丰自动化");
        SupplierSupply sp = supply(10L, "播种墙 整机", "播种墙");
        sp.setQuotePrice(new BigDecimal("180000"));
        Map<Long, List<SupplierSupply>> supplies = new LinkedHashMap<>();
        supplies.put(1L, Collections.singletonList(sp));
        when(svc.canSeeCostNow()).thenReturn(false);
        when(svc.allSuppliers()).thenReturn(Collections.singletonList(s));
        when(svc.suppliesBySupplier()).thenReturn(supplies);
        when(svc.primaryOf(any())).thenReturn(sp);

        SupplierExcelService excel = new SupplierExcelService(svc);
        Object parsed = parse(excel, excel.export(false));
        List<SupplierService.SupplySheetRow> rows = field(parsed, "supplies");
        SupplierService.SupplySheetRow r = rows.get(0);
        assertFalse(r.getPresent().contains("quotePrice"));
        assertFalse(r.getPresent().contains("bomEstimate"));
        assertNull(r.getQuotePrice());
    }

    private SupplierSupply supply(Long id, String name, String category) {
        SupplierSupply sp = new SupplierSupply();
        sp.setId(id);
        sp.setItemType("配件".equals(category) ? "配件" : "整机");
        sp.setItemName(name);
        sp.setCategory(category);
        sp.setNoInterest(1);
        sp.setCanSingleBuy(1);
        sp.setIsPrimary(0);
        return sp;
    }

    private Object parse(SupplierExcelService excel, byte[] data) throws Exception {
        Method m = SupplierExcelService.class.getDeclaredMethod("parse", InputStream.class);
        m.setAccessible(true);
        return m.invoke(excel, new ByteArrayInputStream(data));
    }

    @SuppressWarnings("unchecked")
    private <T> T field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }
}
