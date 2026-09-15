package top.aole.rent.modules.supplier.service;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.util.BusinessScope;
import top.aole.rent.modules.supplier.dto.InspectionDtos;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 供应商考察 ⇄「厂家考察汇总表」Excel。导出与导入同一套列,导出的文件改完可直接导回(按公司名称自动更新)。
 *
 * <p>列:序号 / 公司名称 / 法人 / 注册资本/万元 / 成立时间 / 业务范围 / 公司地址 / 主要联系人 / 主要电话 /
 * 考察记录压缩包 / 是否合格(是/否) / 是否合格(说明)。
 * 导入只认「是/否」列;说明列与压缩包列由系统生成,导入时忽略。按表头名称识别列,列顺序可调整。
 */
@Service
@RequiredArgsConstructor
public class SupplierInspectionExcelService {

    private final SupplierInspectionService inspectionService;

    static final String SHEET_NAME = "汇总表";
    static final String[] HEADERS = {
            "序号", "公司名称", "法人", "注册资本/万元", "成立时间", "业务范围", "公司地址",
            "主要联系人", "主要电话", "考察记录压缩包", "是否合格", "是否合格"
    };
    private static final int[] COL_WIDTHS = {9, 29, 13, 15, 15, 12, 25, 11, 15, 14, 14, 36};
    static final String PASS_NOTE = "列入“供应商上游”模块，做好关联关系";
    static final String FAIL_NOTE = "不列入“供应商上游”模块，不做关联关系";

    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final List<DateTimeFormatter> DATE_FORMATS = Arrays.asList(
            DateTimeFormatter.ofPattern("yyyy-M-d"), DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"), DateTimeFormatter.ofPattern("yyyy年M月d日"),
            DateTimeFormatter.ofPattern("M/d/yy", Locale.US), DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US));

    // ============================== 导出 ==============================

    /** @param template true=只含表头的空模板 */
    public byte[] export(boolean template) {
        List<InspectionDtos.Item> items = template ? new ArrayList<>() : inspectionService.listAll();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet(SHEET_NAME);
            CellStyle head = headStyle(wb);
            CellStyle body = bodyStyle(wb);
            Row hr = sh.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(head);
                sh.setColumnWidth(i, COL_WIDTHS[i] * 256);
            }
            int r = 1;
            int seq = 1;
            for (InspectionDtos.Item it : items) {
                Row row = sh.createRow(r++);
                int c = 0;
                put(row, c++, it.getSortNo() == null ? String.valueOf(seq) : String.valueOf(it.getSortNo()), body);
                seq++;
                put(row, c++, it.getCompanyName(), body);
                put(row, c++, it.getLegalPerson(), body);
                put(row, c++, it.getRegisteredCapitalWan(), body);
                put(row, c++, it.getEstablishedDate() == null ? null : it.getEstablishedDate().toString(), body);
                put(row, c++, String.join("/", it.getBusinessScope()), body);
                put(row, c++, it.getAddress(), body);
                put(row, c++, it.getContact(), body);
                put(row, c++, it.getPhone(), body);
                put(row, c++, String.join("、", it.getArchiveNames()), body);
                put(row, c++, yesNo(it.getResult()), body);
                put(row, c, note(it.getResult()), body);
            }
            sh.createFreezePane(2, 1);
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "考察汇总表导出失败:" + e.getMessage());
        }
    }

    static String yesNo(String result) {
        if (SupplierInspectionService.PASSED.equals(result)) {
            return "是";
        }
        if (SupplierInspectionService.FAILED.equals(result)) {
            return "否";
        }
        return "";
    }

    static String note(String result) {
        if (SupplierInspectionService.PASSED.equals(result)) {
            return PASS_NOTE;
        }
        if (SupplierInspectionService.FAILED.equals(result)) {
            return FAIL_NOTE;
        }
        return "待考察";
    }

    // ============================== 导入 ==============================

    public InspectionDtos.ImportResult importFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择要导入的 Excel 文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException(400, "导入文件超过 10MB 上限");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xls") && !name.endsWith(".xlsx")) {
            throw new BizException(400, "仅支持 .xls / .xlsx 格式");
        }
        try (InputStream in = file.getInputStream()) {
            return inspectionService.importRows(parse(in));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "Excel 解析失败,请确认文件未损坏:" + e.getMessage());
        }
    }

    /** 解析首个含「公司名称」表头的工作表。 */
    List<SupplierInspectionService.SheetRow> parse(InputStream in) throws Exception {
        try (Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter fmt = new DataFormatter(Locale.CHINA);
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sh = wb.getSheetAt(s);
                for (int h = 0; h <= Math.min(5, sh.getLastRowNum()); h++) {
                    Map<String, Integer> cols = headerMap(sh.getRow(h), fmt);
                    if (cols.containsKey("公司名称")) {
                        return parseRows(sh, h, cols, fmt);
                    }
                }
            }
        }
        throw new BizException(400, "没找到表头:请确认表格前几行里有「公司名称」列(可先导出模板对照)");
    }

    private Map<String, Integer> headerMap(Row row, DataFormatter fmt) {
        Map<String, Integer> cols = new HashMap<>();
        if (row == null) {
            return cols;
        }
        for (int c = 0; c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell == null) {
                continue;
            }
            String h = fmt.formatCellValue(cell).replaceAll("\\s+", "");
            String key = canonicalHeader(h);
            if (key != null) {
                cols.putIfAbsent(key, c); // 两列「是否合格」取第一列(是/否)
            }
        }
        return cols;
    }

    private String canonicalHeader(String h) {
        if (h.isEmpty()) {
            return null;
        }
        if (h.equals("序号")) return "序号";
        if (h.equals("公司名称") || h.equals("供应商名称") || h.equals("厂家名称")) return "公司名称";
        if (h.startsWith("法人")) return "法人";
        if (h.startsWith("注册资本")) return "注册资本";
        if (h.startsWith("成立")) return "成立时间";
        if (h.startsWith("业务范围")) return "业务范围";
        if (h.contains("地址")) return "公司地址";
        if (h.contains("联系人")) return "主要联系人";
        if (h.contains("电话") || h.contains("联系方式") || h.contains("手机")) return "主要电话";
        if (h.startsWith("是否合格") || h.equals("考察结果")) return "是否合格";
        return null;
    }

    private List<SupplierInspectionService.SheetRow> parseRows(Sheet sh, int headerRow, Map<String, Integer> cols,
                                                                DataFormatter fmt) {
        List<SupplierInspectionService.SheetRow> out = new ArrayList<>();
        for (int r = headerRow + 1; r <= sh.getLastRowNum(); r++) {
            Row row = sh.getRow(r);
            if (row == null || isBlankRow(row, cols, fmt)) {
                continue;
            }
            SupplierInspectionService.SheetRow sr = new SupplierInspectionService.SheetRow();
            sr.setRowNum(r + 1);
            sr.setCompanyName(text(row, cols.get("公司名称"), fmt));
            sr.setLegalPerson(text(row, cols.get("法人"), fmt));
            sr.setRegisteredCapitalWan(text(row, cols.get("注册资本"), fmt));
            sr.setAddress(text(row, cols.get("公司地址"), fmt));
            sr.setContact(text(row, cols.get("主要联系人"), fmt));
            sr.setPhone(text(row, cols.get("主要电话"), fmt));

            String seq = text(row, cols.get("序号"), fmt);
            if (seq != null) {
                try {
                    sr.setSortNo(new BigDecimal(seq).intValueExact());
                } catch (Exception e) {
                    sr.getWarnings().add("序号「" + seq + "」不是整数,已按末尾排序");
                }
            }
            sr.setEstablishedDate(date(row, cols.get("成立时间"), fmt, sr));
            sr.setBusinessScope(scope(text(row, cols.get("业务范围"), fmt), sr));
            sr.setResult(result(text(row, cols.get("是否合格"), fmt), sr));
            out.add(sr);
        }
        return out;
    }

    private boolean isBlankRow(Row row, Map<String, Integer> cols, DataFormatter fmt) {
        for (Integer c : cols.values()) {
            if (c != null && !c.equals(cols.get("序号")) && text(row, c, fmt) != null) {
                return false;
            }
        }
        return true;
    }

    /** 单元格文本;整数型数字(如电话)按完整数字输出,不走科学计数法。 */
    private String text(Row row, Integer col, DataFormatter fmt) {
        if (col == null) {
            return null;
        }
        Cell cell = row.getCell(col);
        if (cell == null) {
            return null;
        }
        String v;
        if (cell.getCellType() == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) {
            BigDecimal bd = BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros();
            v = bd.scale() <= 0 ? bd.toBigInteger().toString() : bd.toPlainString();
        } else {
            v = fmt.formatCellValue(cell);
        }
        v = v == null ? "" : v.trim();
        return v.isEmpty() ? null : v;
    }

    private LocalDate date(Row row, Integer col, DataFormatter fmt, SupplierInspectionService.SheetRow sr) {
        if (col == null || row.getCell(col) == null) {
            return null;
        }
        Cell cell = row.getCell(col);
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        }
        String raw = text(row, col, fmt);
        if (raw == null) {
            return null;
        }
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(raw, f);
            } catch (DateTimeParseException ignored) {
                // 试下一种格式
            }
        }
        sr.getWarnings().add("成立时间「" + raw + "」无法识别,已留空(请用 2022-07-19 这类格式)");
        return null;
    }

    private List<String> scope(String raw, SupplierInspectionService.SheetRow sr) {
        List<String> picked = new ArrayList<>();
        if (raw == null) {
            return picked;
        }
        for (String t : raw.split("[/、,，;；\\s]+")) {
            if (t.isEmpty()) {
                continue;
            }
            if (BusinessScope.VALUES.contains(t)) {
                picked.add(t);
            } else {
                sr.getWarnings().add("业务范围「" + t + "」不在 货架/阁楼/播种墙 之内,已忽略");
            }
        }
        return picked;
    }

    private String result(String raw, SupplierInspectionService.SheetRow sr) {
        if (raw == null) {
            return null;
        }
        switch (raw) {
            case "是":
            case "合格":
            case "Y":
            case "y":
                return SupplierInspectionService.PASSED;
            case "否":
            case "不合格":
            case "N":
            case "n":
                return SupplierInspectionService.FAILED;
            case "待考察":
                return null;
            default:
                sr.getWarnings().add("是否合格「" + raw + "」无法识别(应填 是/否),结果保持不变");
                return null;
        }
    }

    // ============================== 样式 ==============================

    private void put(Row row, int col, String v, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(v == null ? "" : v);
        c.setCellStyle(style);
    }

    private CellStyle headStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        return s;
    }

    private CellStyle bodyStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setWrapText(true);
        return s;
    }
}
