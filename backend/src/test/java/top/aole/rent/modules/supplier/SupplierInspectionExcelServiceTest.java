package top.aole.rent.modules.supplier;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import top.aole.rent.modules.file.domain.FileObject;
import top.aole.rent.modules.file.service.FileStorageService;
import top.aole.rent.modules.supplier.dto.InspectionDtos;
import top.aole.rent.modules.supplier.service.SupplierInspectionExcelService;
import top.aole.rent.modules.supplier.service.SupplierInspectionService;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

        passed.setId(31L);
        passed.setCompanyProfile("主营播种墙研发与生产");
        passed.setPerformanceWan("2000万");
        passed.setSocialStaff("21");
        passed.setImpression("1、有在线运营项目\n2、达到合格要求");
        pending.setId(32L);
        pending.setProductImageNote("无播种墙图片");

        FileStorageService files = mock(FileStorageService.class);
        FileObject pic = new FileObject();
        pic.setId(900L);
        pic.setFileName("产品图片-1.png");
        when(files.listRaw(SupplierInspectionService.IMAGE_BIZ_TYPE, 31L)).thenReturn(Collections.singletonList(pic));
        when(files.readBytes(pic)).thenReturn(PNG);

        SupplierInspectionExcelService svc = new SupplierInspectionExcelService(inspectionService, files);
        byte[] xlsx = svc.export(false);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsx))) {
            Row h = wb.getSheet("汇总表").getRow(0);
            assertEquals("公司业务范围", h.getCell(5).getStringCellValue());
            assertEquals("业务类型", h.getCell(6).getStringCellValue());
            assertEquals("产品图片", h.getCell(12).getStringCellValue());
            Row r1 = wb.getSheet("汇总表").getRow(1);
            assertEquals("是", r1.getCell(14).getStringCellValue());
            assertEquals("列入“供应商上游”模块，做好关联关系", r1.getCell(15).getStringCellValue());
            assertEquals("考察.zip", r1.getCell(16).getStringCellValue());
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
        assertEquals("主营播种墙研发与生产", a.getCompanyProfile());
        assertEquals("2000万", a.getPerformanceWan());
        assertEquals("21", a.getSocialStaff());
        assertEquals("1、有在线运营项目\n2、达到合格要求", a.getImpression());
        assertEquals(1, a.getImages().size()); // 导出嵌入的图片能导回
        assertArrayEquals(PNG, a.getImages().get(0).getData());
        assertEquals("png", a.getImages().get(0).getExt());
        assertTrue(a.getColumns().contains(SupplierInspectionService.COL_IMAGE));
        SupplierInspectionService.SheetRow b = rows.get(1);
        assertNull(b.getResult()); // 待考察导出为空,导回保持原结果
        assertEquals("无播种墙图片", b.getProductImageNote());
        assertTrue(b.getImages().isEmpty());
    }

    @Test
    void oldSheetKeepsNewColumnsUntouched() throws Exception {
        List<SupplierInspectionService.SheetRow> rows = parse(oldSheet());
        assertTrue(rows.get(0).getColumns().isEmpty()); // 旧版表格没有新列,导入时不覆盖系统里的值
        assertEquals(Collections.singletonList("货架"), rows.get(0).getBusinessScope());
    }

    @Test
    void readsWpsCellImagesFromPackage() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            entry(zip, "xl/cellImages.xml", "<etc:cellImages xmlns:etc=\"x\"><etc:cellImage><xdr:pic><xdr:nvPicPr>"
                    + "<xdr:cNvPr id=\"5\" name=\"ID_ABC123\"/></xdr:nvPicPr><xdr:blipFill><a:blip r:embed=\"rId1\"/>"
                    + "</xdr:blipFill></xdr:pic></etc:cellImage></etc:cellImages>");
            entry(zip, "xl/_rels/cellImages.xml.rels", "<Relationships><Relationship Id=\"rId1\" "
                    + "Type=\"http://x/image\" Target=\"media/image1.jpeg\"/></Relationships>");
            zip.putNextEntry(new ZipEntry("xl/media/image1.jpeg"));
            zip.write(PNG);
            zip.closeEntry();
        }
        Map<String, SupplierInspectionService.SheetImage> images = invokeStatic("cellImagesFromPackage", bos.toByteArray());
        assertEquals(1, images.size());
        assertArrayEquals(PNG, images.get("ID_ABC123").getData());
        assertEquals("jpg", images.get("ID_ABC123").getExt());
    }

    /** 业务提供的真实表格(含真实联系人,不入库);本机有这份文件时才跑。 */
    @Test
    void parsesRealWpsSheetWhenAvailable() throws Exception {
        java.io.File f = new java.io.File("E:\\云山项目\\耀石公司\\5、系统设置\\【20260916】播种墙厂家考察观后感.xls");
        Assumptions.assumeTrue(f.isFile());
        List<SupplierInspectionService.SheetRow> rows = parse(java.nio.file.Files.readAllBytes(f.toPath()));
        assertEquals(8, rows.size());
        long withImages = rows.stream().filter(r -> !r.getImages().isEmpty()).count();
        assertEquals(7, withImages);
        SupplierInspectionService.SheetRow third = rows.get(2);
        assertTrue(third.getImages().isEmpty());
        assertEquals("无播种墙图片", third.getProductImageNote());
        assertEquals(SupplierInspectionService.FAILED, third.getResult());
        assertEquals(Arrays.asList("货架", "阁楼"), rows.get(6).getBusinessScope());
        assertTrue(rows.get(0).getCompanyProfile().length() > 20);
        assertTrue(rows.get(0).getImpression().startsWith("1、"));
        assertTrue(rows.stream().allMatch(r -> r.getWarnings().isEmpty()), () -> rows.get(0).getWarnings().toString());
    }

    @Test
    void syncImagesAddsMissingAndRemovesStale() throws Exception {
        FileStorageService files = mock(FileStorageService.class);
        FileObject same = new FileObject();
        same.setId(1L);
        FileObject stale = new FileObject();
        stale.setId(2L);
        byte[] other = new byte[]{9, 9, 9};
        when(files.listRaw(SupplierInspectionService.IMAGE_BIZ_TYPE, 5L)).thenReturn(Arrays.asList(same, stale));
        when(files.readBytes(same)).thenReturn(PNG);
        when(files.readBytes(stale)).thenReturn(new byte[]{1, 2});
        SupplierInspectionExcelService svc = new SupplierInspectionExcelService(mock(SupplierInspectionService.class), files);

        SupplierInspectionService.SheetRow row = new SupplierInspectionService.SheetRow();
        row.setRowNum(2);
        row.setInspectionId(5L);
        row.getImages().add(new SupplierInspectionService.SheetImage(PNG, "png"));
        row.getImages().add(new SupplierInspectionService.SheetImage(other, "jpg"));
        row.getImages().add(new SupplierInspectionService.SheetImage(other, "jpg")); // 表内重复只存一次
        SupplierInspectionService.SheetRow noImage = new SupplierInspectionService.SheetRow();
        noImage.setInspectionId(6L); // 表里这行没图:不动系统里的图

        InspectionDtos.ImportResult res = new InspectionDtos.ImportResult();
        java.lang.reflect.Method m = SupplierInspectionExcelService.class.getDeclaredMethod("syncImages", List.class, InspectionDtos.ImportResult.class);
        m.setAccessible(true);
        m.invoke(svc, Arrays.asList(row, noImage), res);

        verify(files, times(1)).storeBytes(eq(other), eq("产品图片-2.jpg"), eq("image/jpeg"),
                eq(SupplierInspectionService.IMAGE_BIZ_TYPE), eq(5L));
        verify(files).removeRaw(2L);
        verify(files, never()).removeRaw(1L);
        verify(files, never()).listRaw(SupplierInspectionService.IMAGE_BIZ_TYPE, 6L);
        assertEquals(1, res.getImagesAdded());
    }

    /** 1×1 PNG */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    private static void entry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @SuppressWarnings("unchecked")
    private static <T> T invokeStatic(String name, byte[] arg) throws Exception {
        java.lang.reflect.Method m = SupplierInspectionExcelService.class.getDeclaredMethod(name, byte[].class);
        m.setAccessible(true);
        return (T) m.invoke(null, (Object) arg);
    }

    private byte[] oldSheet() throws Exception {
        try (Workbook wb = new HSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("汇总表");
            Row h = sh.createRow(0);
            String[] headers = {"序号", "公司名称", "业务范围", "是否合格"};
            for (int i = 0; i < headers.length; i++) {
                h.createCell(i).setCellValue(headers[i]);
            }
            Row r = sh.createRow(1);
            r.createCell(1).setCellValue("示例己有限公司");
            r.createCell(2).setCellValue("货架");
            wb.write(bos);
            return bos.toByteArray();
        }
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
        return parse(data, new SupplierInspectionExcelService(mock(SupplierInspectionService.class), mock(FileStorageService.class)));
    }

    @SuppressWarnings("unchecked")
    private List<SupplierInspectionService.SheetRow> parse(byte[] data, SupplierInspectionExcelService svc) throws Exception {
        java.lang.reflect.Method m = SupplierInspectionExcelService.class.getDeclaredMethod("parse", byte[].class);
        m.setAccessible(true);
        return (List<SupplierInspectionService.SheetRow>) m.invoke(svc, (Object) data);
    }
}
