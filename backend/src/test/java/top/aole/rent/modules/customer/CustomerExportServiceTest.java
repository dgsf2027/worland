package top.aole.rent.modules.customer;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.modules.customer.dto.CustomerPoolItem;
import top.aole.rent.modules.customer.service.CustomerExportService;
import top.aole.rent.modules.customer.service.CustomerService;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CustomerExportServiceTest {

    @Test
    void exportsCompanyInfoContractCountsAndMasksSensitiveColumns() throws Exception {
        CustomerService customerService = mock(CustomerService.class);

        CustomerPoolItem visible = item("华南仓储有限公司", false);
        visible.setLegalPerson("张三");
        visible.setRegisteredCapital(new BigDecimal("5000000"));
        visible.setBusinessScope(Arrays.asList("货架", "播种墙"));
        visible.setContact("李四");
        visible.setPhone("13800000000");
        visible.setActiveContractCount(2);
        visible.setContractTotal(3);
        visible.setActiveAssetCount(5);
        visible.setExposureOrOppAmount(new BigDecimal("120000"));

        CustomerPoolItem masked = item("投资人视角客户", true);

        when(customerService.pool(eq("在租"), isNull(), isNull(), isNull(), eq(1), anyInt()))
                .thenReturn(new PageResult<>(2, 1, Integer.MAX_VALUE, Arrays.asList(visible, masked)));

        byte[] data = new CustomerExportService(customerService).exportExcel("在租", null, null, null);

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            Sheet sh = wb.getSheet("客户信息");
            assertEquals("公司名称", sh.getRow(0).getCell(0).getStringCellValue());
            assertEquals("可在租合同(份)", sh.getRow(0).getCell(10).getStringCellValue());

            Row r1 = sh.getRow(1);
            assertEquals("华南仓储有限公司", r1.getCell(0).getStringCellValue());
            assertEquals("张三", r1.getCell(1).getStringCellValue());
            assertEquals(500.0, r1.getCell(2).getNumericCellValue(), 0.001); // 元 → 万元
            assertEquals("货架/播种墙", r1.getCell(3).getStringCellValue());
            assertEquals(2.0, r1.getCell(10).getNumericCellValue(), 0.001);
            assertEquals(3.0, r1.getCell(11).getNumericCellValue(), 0.001);
            assertEquals(5.0, r1.getCell(12).getNumericCellValue(), 0.001);
            assertEquals(120000.0, r1.getCell(13).getNumericCellValue(), 0.001);

            Row r2 = sh.getRow(2);
            assertEquals("无权限", r2.getCell(13).getStringCellValue());
            assertEquals("无权限", r2.getCell(14).getStringCellValue());
            assertEquals(3, sh.getPhysicalNumberOfRows());
        }
    }

    private CustomerPoolItem item(String name, boolean masked) {
        CustomerPoolItem it = new CustomerPoolItem();
        it.setName(name);
        it.setBusinessScope(Collections.emptyList());
        it.setActiveContractCount(0);
        it.setContractTotal(0);
        it.setActiveAssetCount(0);
        it.setPhase("在租");
        it.setSensitiveMasked(masked);
        return it;
    }
}
