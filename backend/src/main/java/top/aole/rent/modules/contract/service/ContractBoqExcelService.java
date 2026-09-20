package top.aole.rent.modules.contract.service;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.contract.domain.Contract;
import top.aole.rent.modules.contract.dto.BoqDtos;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 合同清单 ⇄《工程量清单计价表》Excel。导出与导入同一套列,导出的文件改完可直接导回(整表替换)。
 *
 * <p>版式对齐业务表格:第 1 行标题「工程量清单计价表」(合并),第 2 行表头
 * 序号/名称/型号/规格/单位/数量/单价/金额/备注,之后逐行明细(赠送行金额留空或「-」、优惠行金额为负),
 * 最后一行「合计实收:¥… 」并注明含税。系统在最后多加一列「生成设备品类」(播种墙/货架/阁楼/配件),
 * 填了的行可按数量一键生成设备;业务原表没有这一列也能直接导入。导入时表头行自动识别,合计行与空行跳过。
 */
@Service
@RequiredArgsConstructor
public class ContractBoqExcelService {

    static final String SHEET_NAME = "工程量清单计价表";
    static final String TITLE = "工程量清单计价表";
    static final String[] HEADERS = {"序号", "名称", "型号", "规格", "单位", "数量", "单价", "金额", "备注", "生成设备品类"};
    private static final int[] COL_WIDTHS = {7, 22, 18, 26, 7, 8, 12, 14, 34, 14};
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    /** 合计行的名称前缀(导入时跳过) */
    private static final List<String> TOTAL_PREFIXES = Arrays.asList("合计", "总计", "小计", "合计实收");

    private final ContractBoqService boqService;

    // ============================== 导出 ==============================

    public byte[] export(Contract contract, BoqDtos.Boq boq, boolean template) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet(SHEET_NAME);
            CellStyle title = titleStyle(wb);
            CellStyle head = headStyle(wb);
            CellStyle body = bodyStyle(wb);
            CellStyle money = moneyStyle(wb);
            CellStyle total = totalStyle(wb);

            Row tr = sh.createRow(0);
            tr.setHeightInPoints(29);
            Cell tc = tr.createCell(0);
            tc.setCellValue(TITLE);
            tc.setCellStyle(title);
            for (int i = 1; i < HEADERS.length; i++) {
                tr.createCell(i).setCellStyle(title);
            }
            sh.addMergedRegion(new CellRangeAddress(0, 0, 0, HEADERS.length - 1));

            Row hr = sh.createRow(1);
            hr.setHeightInPoints(28);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(head);
                sh.setColumnWidth(i, COL_WIDTHS[i] * 256);
            }

            int r = 2;
            List<BoqDtos.Line> lines = template ? new ArrayList<>() : boq.getLines();
            for (BoqDtos.Line l : lines) {
                Row row = sh.createRow(r);
                text(row, 0, l.getSeq() == null ? String.valueOf(r - 1) : String.valueOf(l.getSeq()), body);
                text(row, 1, l.getName(), body);
                text(row, 2, l.getModel(), body);
                text(row, 3, l.getSpec(), body);
                text(row, 4, l.getUnit(), body);
                number(row, 5, l.getQty(), body);
                number(row, 6, l.getUnitPrice(), money);
                if (l.getAmount() == null) {
                    text(row, 7, "-", money);
                } else {
                    number(row, 7, l.getAmount(), money);
                }
                text(row, 8, l.getRemark(), body);
                text(row, 9, l.getAssetCategory(), body);
                r++;
            }

            if (!template) {
                Row sum = sh.createRow(r);
                sum.setHeightInPoints(22);
                Cell c0 = sum.createCell(0);
                c0.setCellValue(String.valueOf(lines.size() + 1));
                c0.setCellStyle(total);
                Cell c1 = sum.createCell(1);
                c1.setCellValue(totalText(contract, boq));
                c1.setCellStyle(total);
                for (int i = 2; i < HEADERS.length; i++) {
                    sum.createCell(i).setCellStyle(total);
                }
                sh.addMergedRegion(new CellRangeAddress(r, r, 1, HEADERS.length - 1));
            }
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "合同清单导出失败:" + e.getMessage());
        }
    }

    private String totalText(Contract contract, BoqDtos.Boq boq) {
        BigDecimal t = boq.getTotalWithTax() == null ? BigDecimal.ZERO : boq.getTotalWithTax();
        StringBuilder sb = new StringBuilder("合计实收：¥")
                .append(t.setScale(2, RoundingMode.HALF_UP).toPlainString())
                .append("（").append(ContractBoqService.upperAmount(t)).append("）");
        if (contract.getTaxRate() != null && contract.getTaxRate().signum() > 0) {
            BigDecimal pct = contract.getTaxRate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros();
            sb.append("  本合同约定价格均已含税费等费用，税率 ").append(pct.toPlainString()).append("%");
            if (boq.getTotalWithoutTax() != null) {
                sb.append("，不含税 ¥").append(boq.getTotalWithoutTax().toPlainString())
                        .append("，税额 ¥").append(boq.getTaxAmount().toPlainString());
            }
        } else {
            sb.append("  本合同约定价格均已含税费等费用");
        }
        return sb.toString();
    }

    // ============================== 导入 ==============================

    public BoqDtos.ImportResult importFile(Contract contract, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "请选择要导入的 Excel 文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException(400, "导入文件超过 " + (MAX_BYTES / 1024 / 1024) + "MB 上限");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xls") && !name.endsWith(".xlsx")) {
            throw new BizException(400, "仅支持 .xls / .xlsx 格式");
        }
        List<String> messages = new ArrayList<>();
        List<BoqDtos.Line> lines;
        try (InputStream in = file.getInputStream()) {
            lines = parse(in, messages);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "Excel 解析失败,请确认文件未损坏:" + e.getMessage());
        }
        if (lines.isEmpty()) {
            throw new BizException(400, "没解析到清单明细行,请确认表格里有「名称」列且下方有数据");
        }
        return boqService.replaceAll(contract, lines, messages);
    }

    /** 解析首个含「名称」表头的工作表。 */
    List<BoqDtos.Line> parse(InputStream in, List<String> messages) throws Exception {
        try (Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter fmt = new DataFormatter(Locale.CHINA);
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sh = wb.getSheetAt(s);
                for (int h = 0; h <= Math.min(8, sh.getLastRowNum()); h++) {
                    Map<String, Integer> cols = headerMap(sh.getRow(h), fmt);
                    if (cols.containsKey("名称")) {
                        return parseRows(sh, h, cols, fmt, messages);
                    }
                }
            }
        }
        throw new BizException(400, "没找到表头:请确认表格前几行里有「名称」列(可先导出模板对照)");
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
                cols.putIfAbsent(key, c);
            }
        }
        return cols;
    }

    private String canonicalHeader(String h) {
        if (h.isEmpty()) {
            return null;
        }
        if (h.equals("序号")) return "序号";
        if (h.equals("名称") || h.equals("项目名称") || h.equals("设备名称")) return "名称";
        if (h.equals("型号")) return "型号";
        if (h.equals("规格") || h.equals("规格说明") || h.equals("配置")) return "规格";
        if (h.equals("单位") || h.equals("计量单位")) return "单位";
        if (h.equals("数量")) return "数量";
        if (h.startsWith("单价")) return "单价";
        if (h.equals("金额") || h.equals("合价") || h.equals("小计")) return "金额";
        if (h.startsWith("备注") || h.equals("说明")) return "备注";
        if (h.contains("生成设备") || h.equals("设备品类") || h.equals("品类")) return "生成设备品类";
        return null;
    }

    private List<BoqDtos.Line> parseRows(Sheet sh, int headerRow, Map<String, Integer> cols, DataFormatter fmt,
                                         List<String> messages) {
        List<BoqDtos.Line> out = new ArrayList<>();
        for (int r = headerRow + 1; r <= sh.getLastRowNum(); r++) {
            Row row = sh.getRow(r);
            if (row == null) {
                continue;
            }
            String name = text(row, cols.get("名称"), fmt);
            BigDecimal amount = decimal(row, cols.get("金额"), fmt);
            if (name == null) {
                continue;
            }
            if (isTotalRow(name)) {
                messages.add("第 " + (r + 1) + " 行「" + brief(name) + "」是合计行,未导入(合计由系统按清单自动算)");
                continue;
            }
            BoqDtos.Line l = new BoqDtos.Line();
            l.setName(name);
            l.setModel(text(row, cols.get("型号"), fmt));
            l.setSpec(text(row, cols.get("规格"), fmt));
            l.setUnit(text(row, cols.get("单位"), fmt));
            l.setQty(decimal(row, cols.get("数量"), fmt));
            l.setUnitPrice(decimal(row, cols.get("单价"), fmt));
            BigDecimal auto = l.getQty() == null || l.getUnitPrice() == null ? null
                    : l.getQty().multiply(l.getUnitPrice()).setScale(2, RoundingMode.HALF_UP);
            // 金额与 数量×单价 不一致(赠送「-」、优惠负数、整单折让)→ 认手填金额
            boolean manual = auto == null || amount == null || amount.compareTo(auto) != 0;
            l.setAmountManual(manual);
            l.setAmount(manual ? amount : auto);
            l.setRemark(text(row, cols.get("备注"), fmt));
            String category = text(row, cols.get("生成设备品类"), fmt);
            if (category != null && !ContractBoqService.ASSET_CATEGORIES.contains(category)) {
                messages.add("第 " + (r + 1) + " 行「生成设备品类」填的是「" + category + "」,不在 "
                        + String.join("/", ContractBoqService.ASSET_CATEGORIES) + " 之内,已忽略");
                category = null;
            }
            l.setAssetCategory(category);
            out.add(l);
        }
        return out;
    }

    private boolean isTotalRow(String name) {
        String n = name.replaceAll("\\s+", "");
        for (String p : TOTAL_PREFIXES) {
            if (n.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static String brief(String s) {
        return s.length() <= 16 ? s : s.substring(0, 16) + "…";
    }

    private String text(Row row, Integer col, DataFormatter fmt) {
        if (col == null) {
            return null;
        }
        Cell cell = row.getCell(col);
        if (cell == null) {
            return null;
        }
        String v = fmt.formatCellValue(cell).trim();
        return v.isEmpty() ? null : v;
    }

    /** 金额/数量:数字单元格直接取值;文本里带 ￥ , 等符号时清洗后解析;「-」「/」等视为无值。 */
    private BigDecimal decimal(Row row, Integer col, DataFormatter fmt) {
        if (col == null) {
            return null;
        }
        Cell cell = row.getCell(col);
        if (cell == null) {
            return null;
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
        if (cell.getCellType() == CellType.FORMULA) {
            try {
                return BigDecimal.valueOf(cell.getNumericCellValue());
            } catch (Exception ignore) {
                // 公式结果不是数字,按文本解析
            }
        }
        String raw = fmt.formatCellValue(cell).trim();
        String cleaned = raw.replace("￥", "").replace("¥", "").replace(",", "")
                .replace("元", "").replace(" ", "").trim();
        if (cleaned.isEmpty() || "-".equals(cleaned) || "—".equals(cleaned) || "/".equals(cleaned)) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ============================== 样式 ==============================

    private void text(Row row, int col, String v, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(v == null ? "" : v);
        c.setCellStyle(style);
    }

    private void number(Row row, int col, BigDecimal v, CellStyle style) {
        Cell c = row.createCell(col);
        if (v != null) {
            c.setCellValue(v.doubleValue());
        }
        c.setCellStyle(style);
    }

    private CellStyle titleStyle(Workbook wb) {
        CellStyle s = border(wb.createCellStyle());
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 14);
        s.setFont(f);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        return s;
    }

    private CellStyle headStyle(Workbook wb) {
        CellStyle s = border(wb.createCellStyle());
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
        CellStyle s = border(wb.createCellStyle());
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setWrapText(true);
        return s;
    }

    private CellStyle moneyStyle(Workbook wb) {
        CellStyle s = bodyStyle(wb);
        s.setDataFormat(wb.createDataFormat().getFormat("￥#,##0.00;-￥#,##0.00"));
        s.setAlignment(HorizontalAlignment.RIGHT);
        return s;
    }

    private CellStyle totalStyle(Workbook wb) {
        CellStyle s = border(wb.createCellStyle());
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setWrapText(true);
        return s;
    }

    private CellStyle border(CellStyle s) {
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        return s;
    }
}
