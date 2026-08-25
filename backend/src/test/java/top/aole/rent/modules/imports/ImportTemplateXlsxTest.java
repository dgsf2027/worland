package top.aole.rent.modules.imports;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import top.aole.rent.modules.imports.dto.ImportDtos;
import top.aole.rent.modules.imports.service.ImportService;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导入模板下载(F6 修 Bug:导入台账表格格式不对/缺公司名称·账户信息·开户行)。
 *
 * <p>验:①供应商模板表头含工商/开票/收款四要素 ②生成的 .xlsx 表头与 template() 的 label 逐字一致
 * (逐字一致才能被 resolveMapping 自动映射,否则用户填完回传会「列映射为空」)。
 * templateXlsx 只依赖 template()/sampleRow(),故直接 new 出实例(依赖传 null 不参与本路径)。
 */
class ImportTemplateXlsxTest {

    private final ImportService svc =
            new ImportService(null, null, null, null, null, null, null, null, null);

    @Test
    void 供应商模板含工商开票收款字段() {
        List<String> labels = new ArrayList<>();
        for (ImportDtos.Field f : svc.template("supplier").getFields()) {
            labels.add(f.getLabel());
        }
        assertTrue(labels.contains("公司全称"), "缺「公司全称」:" + labels);
        assertTrue(labels.contains("统一社会信用代码"), "缺「统一社会信用代码」:" + labels);
        assertTrue(labels.contains("开户行"), "缺「开户行」:" + labels);
        assertTrue(labels.contains("银行账号"), "缺「银行账号」:" + labels);
        assertTrue(labels.contains("收款户名"), "缺「收款户名」:" + labels);
        assertTrue(labels.contains("发票类型"), "缺「发票类型」:" + labels);
        assertTrue(labels.contains("注册地址"), "缺「注册地址」:" + labels);
        assertTrue(labels.contains("注册电话"), "缺「注册电话」:" + labels);
    }

    @Test
    void 生成的xlsx表头与目标字段逐字一致且带示例行() throws Exception {
        for (String target : new String[]{"supplier", "customer", "asset"}) {
            byte[] bytes = svc.templateXlsx(target);
            assertTrue(bytes.length > 0, target + " 模板为空");
            try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
                Sheet sheet = wb.getSheetAt(0);
                Row head = sheet.getRow(0);
                Row sample = sheet.getRow(1);
                List<ImportDtos.Field> fields = svc.template(target).getFields();
                assertEquals(fields.size(), head.getLastCellNum(), target + " 表头列数不符");
                for (int i = 0; i < fields.size(); i++) {
                    assertEquals(fields.get(i).getLabel(), head.getCell(i).getStringCellValue(),
                            target + " 第" + (i + 1) + "列表头与目标字段 label 不一致");
                }
                assertTrue(sample.getCell(0).getStringCellValue().length() > 0, target + " 缺示例行");
            }
        }
    }
}
