package top.aole.rent.modules.imports.parser;

import lombok.Data;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import top.aole.rent.common.exception.BizException;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 导入中心 Excel 解析器(POI · M5-07)。读第一个 sheet,第一非空行当表头,输出规整文本行。
 *
 * <p><b>公式注入前缀转义(P1-17)</b>:任何以 {@code = + - @ \t \r} 开头的<b>文本</b>单元格,
 * 落地前加单引号前缀 {@code '} 中和(CSV/Excel 注入防御),并计数。数字/日期单元格不受影响。
 * 公式型单元格取缓存值(不执行公式)。
 */
@Component
public class ExcelParser {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final DataFormatter formatter = new DataFormatter();

    /** 解析结果:表头 + 数据行(已转义) + 转义单元格计数 */
    @Data
    public static class ParsedSheet {
        private List<String> headers = new ArrayList<>();
        private List<ParsedRow> rows = new ArrayList<>();
        private int escapedCells = 0;
    }

    @Data
    public static class ParsedRow {
        private int rowNo;
        /** 表头名 → 已转义单元格值 */
        private Map<String, String> cells = new LinkedHashMap<>();
    }

    public ParsedSheet parse(InputStream in) {
        try (Workbook wb = new XSSFWorkbook(in)) {
            Sheet sheet = wb.getSheetAt(0);
            ParsedSheet result = new ParsedSheet();
            int headerRowIdx = -1;
            Map<Integer, String> colIndex = new LinkedHashMap<>();
            for (Row row : sheet) {
                if (headerRowIdx < 0) {
                    boolean any = false;
                    for (Cell cell : row) {
                        String text = cellText(cell, result); // 表头也转义(防表头注入)
                        if (text != null && !text.trim().isEmpty()) {
                            any = true;
                            colIndex.put(cell.getColumnIndex(), text.trim());
                        }
                    }
                    if (any) {
                        headerRowIdx = row.getRowNum();
                        result.getHeaders().addAll(colIndex.values());
                    }
                    continue;
                }
                ParsedRow parsed = new ParsedRow();
                parsed.setRowNo(row.getRowNum() + 1);
                boolean any = false;
                for (Map.Entry<Integer, String> e : colIndex.entrySet()) {
                    Cell cell = row.getCell(e.getKey());
                    String text = cellText(cell, result);
                    if (text != null && !text.trim().isEmpty()) {
                        any = true;
                        parsed.getCells().put(e.getValue(), text.trim());
                    } else {
                        parsed.getCells().put(e.getValue(), null);
                    }
                }
                if (any) {
                    result.getRows().add(parsed);
                }
            }
            if (headerRowIdx < 0) {
                throw new BizException("文件内容为空:找不到表头行");
            }
            return result;
        } catch (IOException e) {
            throw new BizException("Excel 解析失败(仅支持 .xlsx):" + e.getMessage());
        }
    }

    /** 单元格 → 规整文本(文本型做公式注入前缀转义并计数) */
    private String cellText(Cell cell, ParsedSheet acc) {
        if (cell == null) {
            return null;
        }
        CellType type = cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType() : cell.getCellType();
        switch (type) {
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    LocalDateTime dt = cell.getLocalDateTimeCellValue();
                    return dt == null ? null : dt.format(DATE_TIME);
                }
                return BigDecimal.valueOf(cell.getNumericCellValue())
                        .stripTrailingZeros().toPlainString();
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case STRING:
                return escapeFormula(cell.getStringCellValue(), acc);
            case BLANK:
                return null;
            default:
                return escapeFormula(formatter.formatCellValue(cell), acc);
        }
    }

    /** 公式注入前缀转义(P1-17):=+-@ \t \r 开头文本 → 前置单引号中和。 */
    private String escapeFormula(String raw, ParsedSheet acc) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        char c0 = raw.charAt(0);
        if (c0 == '=' || c0 == '+' || c0 == '-' || c0 == '@' || c0 == '\t' || c0 == '\r') {
            if (acc != null) {
                acc.setEscapedCells(acc.getEscapedCells() + 1);
            }
            return "'" + raw;
        }
        return raw;
    }
}
