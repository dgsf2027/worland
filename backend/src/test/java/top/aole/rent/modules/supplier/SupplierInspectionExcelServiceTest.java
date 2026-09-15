package top.aole.rent.modules.supplier;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import top.aole.rent.modules.supplier.dto.InspectionDtos;
import top.aole.rent.modules.supplier.service.SupplierInspectionExcelService;
import top.aole.rent.modules.supplier.service.SupplierInspectionService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「厂家考察汇总表」解析与导出回读。表结构对齐业务提供的 .xls(两列「是否合格」、日期单元格、数字电话、非数字注册资本)。
 */
class SupplierInspectionExcelServiceTest {

    @Test
    void parsesSummarySheetLikeTheBusinessFile() throws Exception {
        byte[] xls;
        try (Workbook wb = new HSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("汇总表");
            String[] headers = {"序号", "公司名称", "法人", "注册资本/万元", "成立时间", "业务范围", "公司地址",
                    "主要联系人", "主要电话", "考察记录压缩包", "是否合格", "是否合格"};
            Row h = sh.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                h.createCell(i).setCellValue(headers[i]);
            }
            CreationHelper ch = wb.getCreationHelper();
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(ch.createDataFormat().getFormat("m/d/yy"));

            Row r1 = sh.createRow(1);
            r1.createCell(0).setCellValue(1);
            r1.createCell(1).setCellValue("示例甲科技有限公司");
            r1.createCell(2).setCellValue("甲法人");
            r1.createCell(3).setCellValue(2148.57);
            r1.createCell(4).setCellValue(Date.from(LocalDate.of(2014, 7, 21).atStartOfDay(ZoneId.systemDefault()).toInstant()));
            r1.getCell(4).setCellStyle(dateStyle);
            r1.createCell(5).setCellValue("播种墙");
            r1.createCell(6).setCellValue("示例地址一号");
            r1.createCell(7).setCellValue("杨先生");
            r1.createCell(8).setCellValue(19100000001d);
            r1.createCell(10).setCellValue("是");
            r1.createCell(11).setCellValue("不列入“供应商上游”模块，不做关联关系"); // 说明列与是/否矛盾:以是/否为准

            Row r2 = sh.createRow(2);
            r2.createCell(0).setCellValue(2);
            r2.createCell(1).setCellValue("示例乙（深圳）有限公司");
            r2.createCell(3).setCellValue("60*6");
            r2.createCell(4).setCellValue("2011/4/26");
            r2.createCell(5).setCellValue("货架/阁楼、配件");
            r2.createCell(10).setCellValue("否");

            sh.createRow(3); // 空行应被跳过
            Row r4 = sh.createRow(4);
            r4.createCell(1).setCellValue("示例丙有限公司");
            r4.createCell(4).setCellValue("去年");
            r4.createCell(10).setCellValue("待定");

            wb.write(bos);
            xls = bos.toByteArray();
        }

        List<SupplierInspectionService.SheetRow> rows = parse(xls);
        assertEquals(3, rows.size());

        SupplierInspectionService.SheetRow a = rows.get(0);
        assertEquals(2, a.getRowNum());
        assertEquals(Integer.valueOf(1), a.getSortNo());
        assertEquals("示例甲科技有限公司", a.getCompanyName());
        assertEquals("2148.57", a.getRegisteredCapitalWan());
        assertEquals(LocalDate.of(2014, 7, 21), a.getEstablishedDate());
        assertEquals(Collections.singletonList("播种墙"), a.getBusinessScope());
        assertEquals("19100000001", a.getPhone());
        assertEquals(SupplierInspectionService.PASSED, a.getResult());
        assertTrue(a.getWarnings().isEmpty());

        SupplierInspectionService.SheetRow b = rows.get(1);
        assertEquals("60*6", b.getRegisteredCapitalWan());
        assertEquals(LocalDate.of(2011, 4, 26), b.getEstablishedDate());
        assertEquals(Arrays.asList("货架", "阁楼"), b.getBusinessScope());
        assertEquals(SupplierInspectionService.FAILED, b.getResult());
        assertEquals(1, b.getWarnings().size()); // 「配件」不在业务范围内

        SupplierInspectionService.SheetRow c = rows.get(2);
        assertEquals(5, c.getRowNum());
        assertNull(c.getSortNo());
        assertNull(c.getEstablishedDate());
        assertNull(c.getResult());
        assertEquals(2, c.getWarnings().size()); // 成立时间、是否合格 均无法识别
    }

    @Test
    void exportedFileCanBeImportedBack() throws Exception {
        SupplierInspectionService inspectionService = mock(SupplierInspectionService.class);
        InspectionDtos.Item passed = item(3, "示例丁有限公司", SupplierInspectionService.PASSED);
        passed.setRegisteredCapitalWan("60*6");
        passed.setEstablishedDate(LocalDate.of(2022, 2, 18));
        passed.setBusinessScope(Arrays.asList("货架", "播种墙"));
        passed.setPhone("13800000000");
        passed.setArchiveNames(Collections.singletonList("考察.zip"));
        InspectionDtos.Item pending = item(7, "示例戊有限公司", SupplierInspectionService.PENDING);
        when(inspectionService.listAll()).thenReturn(Arrays.asList(passed, pending));

        SupplierInspectionExcelService svc = new SupplierInspectionExcelService(inspectionService);
        byte[] xlsx = svc.export(false);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Row r1 = wb.getSheet("汇总表").getRow(1);
            assertEquals("是", r1.getCell(10).getStringCellValue());
            assertEquals("列入“供应商上游”模块，做好关联关系", r1.getCell(11).getStringCellValue());
            assertEquals("考察.zip", r1.getCell(9).getStringCellValue());
        }

        List<SupplierInspectionService.SheetRow> rows = parse(xlsx, svc);
        assertEquals(2, rows.size());
        SupplierInspectionService.SheetRow a = rows.get(0);
        assertEquals(Integer.valueOf(3), a.getSortNo());
        assertEquals("60*6", a.getRegisteredCapitalWan());
        assertEquals(LocalDate.of(2022, 2, 18), a.getEstablishedDate());
        assertEquals(Arrays.asList("货架", "播种墙"), a.getBusinessScope());
        assertEquals("13800000000", a.getPhone());
        assertEquals(SupplierInspectionService.PASSED, a.getResult());
        assertTrue(a.getWarnings().isEmpty());
        assertNull(rows.get(1).getResult()); // 待考察导出为空,导回保持原结果
    }

    private InspectionDtos.Item item(int sortNo, String name, String result) {
        InspectionDtos.Item it = new InspectionDtos.Item();
        it.setSortNo(sortNo);
        it.setCompanyName(name);
        it.setResult(result);
        it.setBusinessScope(Collections.emptyList());
        return it;
    }

    private List<SupplierInspectionService.SheetRow> parse(byte[] data) throws Exception {
        return parse(data, new SupplierInspectionExcelService(mock(SupplierInspectionService.class)));
    }

    @SuppressWarnings("unchecked")
    private List<SupplierInspectionService.SheetRow> parse(byte[] data, SupplierInspectionExcelService svc) throws Exception {
        java.lang.reflect.Method m = SupplierInspectionExcelService.class.getDeclaredMethod("parse", java.io.InputStream.class);
        m.setAccessible(true);
        return (List<SupplierInspectionService.SheetRow>) m.invoke(svc, new ByteArrayInputStream(data));
    }
}
