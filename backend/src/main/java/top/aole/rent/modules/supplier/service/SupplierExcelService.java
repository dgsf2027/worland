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
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.domain.SupplierSupply;
import top.aole.rent.modules.supplier.dto.SupplierEditDtos;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 供应商·上游 ⇄ Excel。两张表:「供应商」(基本信息+履约五维)与「供货矩阵」(整机/配件+报价账期+价格构成)。
 * 导出的文件改完可直接导回:供应商按名称、供货项按 供应商+供何物 自动新增或更新。
 *
 * <p>按表头名称识别列;表头里没有的列导入时保留原值,列在但单元格为空则清空。
 * 「加权总分」「报价vs BOM」为系统计算列,导入时忽略;成本类字段对无权限角色导出为「无权限」,导入时视为未提供。
 */
@Service
@RequiredArgsConstructor
public class SupplierExcelService {

    private final SupplierService supplierService;

    static final String SHEET_SUPPLIER = "供应商";
    static final String SHEET_SUPPLY = "供货矩阵";
    static final String NO_ACCESS = "无权限";

    static final String[] SUPPLIER_HEADERS = {
            "供应商名称", "联系人", "电话", "公司账户", "开户银行", "主营品类", "关系阶段",
            "品质(故障率)", "交期(准时率)", "服务(响应)", "价格(vs市场)", "账期(首付低)", "加权总分(自动计算)", "备注"
    };
    private static final int[] SUPPLIER_WIDTHS = {24, 10, 15, 22, 22, 10, 10, 12, 12, 11, 12, 12, 16, 30};

    static final String[] SUPPLY_HEADERS = {
            "供应商名称", "类型", "供何物", "品类", "集采报价(元)", "首付比例", "账期(天)", "账期无息", "可单采", "代表项",
            "材料(元)", "加工(元)", "利润(元)", "我方BOM估算(元)", "报价vs BOM(自动计算)", "备注"
    };
    private static final int[] SUPPLY_WIDTHS = {24, 8, 18, 10, 14, 10, 10, 10, 8, 8, 12, 12, 12, 16, 18, 30};

    private static final long MAX_BYTES = 10L * 1024 * 1024;

    // ============================== 导出 ==============================

    public byte[] export(boolean template) {
        boolean seeCost = supplierService.canSeeCostNow();
        List<Supplier> suppliers = template ? Collections.emptyList() : supplierService.allSuppliers();
        Map<Long, List<SupplierSupply>> supplies = template ? Collections.emptyMap() : supplierService.suppliesBySupplier();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            CellStyle head = headStyle(wb);

            Sheet s1 = wb.createSheet(SHEET_SUPPLIER);
            header(s1, head, SUPPLIER_HEADERS, SUPPLIER_WIDTHS);
            int r = 1;
            for (Supplier s : suppliers) {
                List<SupplierSupply> list = supplies.getOrDefault(s.getId(), Collections.emptyList());
                SupplierSupply p = supplierService.primaryOf(list);
                Row row = s1.createRow(r++);
                int c = 0;
                text(row, c++, s.getName());
                text(row, c++, s.getContact());
                text(row, c++, s.getPhone());
                text(row, c++, s.getCompanyAccount());
                text(row, c++, s.getOpeningBank());
                text(row, c++, s.getMainCategory());
                text(row, c++, s.getStatus());
                num(row, c++, p == null ? null : p.getScoreQuality());
                num(row, c++, p == null ? null : p.getScoreDelivery());
                num(row, c++, p == null ? null : p.getScoreService());
                num(row, c++, p == null ? null : p.getScorePrice());
                num(row, c++, p == null ? null : p.getScoreTerm());
                num(row, c++, p == null ? null : supplierService.weightedTotal(p));
                text(row, c, s.getRemark());
            }
            s1.createFreezePane(1, 1);

            Sheet s2 = wb.createSheet(SHEET_SUPPLY);
            header(s2, head, SUPPLY_HEADERS, SUPPLY_WIDTHS);
            r = 1;
            for (Supplier s : suppliers) {
                SupplierSupply p = supplierService.primaryOf(supplies.getOrDefault(s.getId(), Collections.emptyList()));
                for (SupplierSupply sp : supplies.getOrDefault(s.getId(), Collections.emptyList())) {
                    Row row = s2.createRow(r++);
                    int c = 0;
                    text(row, c++, s.getName());
                    text(row, c++, sp.getItemType());
                    text(row, c++, sp.getItemName());
                    text(row, c++, sp.getCategory());
                    money(row, c++, sp.getQuotePrice(), seeCost);
                    if (seeCost) {
                        text(row, c++, sp.getFirstPayRatio() == null ? null
                                : sp.getFirstPayRatio().multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%");
                    } else {
                        text(row, c++, NO_ACCESS);
                    }
                    if (seeCost) {
                        num(row, c++, sp.getAccountDays());
                    } else {
                        text(row, c++, NO_ACCESS);
                    }
                    text(row, c++, yesNo(sp.getNoInterest()));
                    text(row, c++, yesNo(sp.getCanSingleBuy()));
                    text(row, c++, p != null && p.getId().equals(sp.getId()) ? "是" : "否");
                    money(row, c++, sp.getCostMaterial(), seeCost);
                    money(row, c++, sp.getCostProcessing(), seeCost);
                    money(row, c++, sp.getProfitAmount(), seeCost);
                    money(row, c++, sp.getBomEstimate(), seeCost);
                    text(row, c++, seeCost ? verdict(sp) : NO_ACCESS);
                    text(row, c, sp.getRemark());
                }
            }
            s2.createFreezePane(3, 1);

            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "供应商导出失败:" + e.getMessage());
        }
    }

    static String verdict(SupplierSupply sp) {
        if (sp.getQuotePrice() == null || sp.getBomEstimate() == null) {
            return "";
        }
        return sp.getQuotePrice().compareTo(sp.getBomEstimate().multiply(new BigDecimal("1.10"))) <= 0 ? "合理" : "偏高(疑虚高)";
    }

    private static String yesNo(Integer v) {
        return v == null ? "" : (v == 1 ? "是" : "否");
    }

    // ============================== 导入 ==============================

    public SupplierEditDtos.ImportResult importFile(MultipartFile file) {
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
            Parsed p = parse(in);
            return supplierService.importSheets(p.suppliers, p.supplies);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "Excel 解析失败,请确认文件未损坏:" + e.getMessage());
        }
    }

    static class Parsed {
        List<SupplierService.SupplierSheetRow> suppliers = new ArrayList<>();
        List<SupplierService.SupplySheetRow> supplies = new ArrayList<>();
    }

    /** 按表头识别两张表:含「供何物」的是供货矩阵,否则含「供应商名称」的是供应商表。 */
    Parsed parse(InputStream in) throws Exception {
        Parsed out = new Parsed();
        boolean any = false;
        try (Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter fmt = new DataFormatter(Locale.CHINA);
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sh = wb.getSheetAt(s);
                for (int h = 0; h <= Math.min(5, sh.getLastRowNum()); h++) {
                    Map<String, Integer> cols = headerMap(sh.getRow(h), fmt);
                    if (!cols.containsKey("supplierName")) {
                        continue;
                    }
                    if (cols.containsKey("itemName")) {
                        parseSupplies(sh, h, cols, fmt, out.supplies);
                    } else {
                        parseSuppliers(sh, h, cols, fmt, out.suppliers);
                    }
                    any = true;
                    break;
                }
            }
        }
        if (!any) {
            throw new BizException(400, "没找到表头:请确认表格里有「供应商名称」列(可先导出模板对照)");
        }
        return out;
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
            String key = canonical(h);
            if (key != null) {
                cols.putIfAbsent(key, c);
            }
        }
        return cols;
    }

    private String canonical(String h) {
        if (h.isEmpty() || h.contains("自动计算")) return null;
        if (h.equals("供应商名称") || h.equals("供应商") || h.equals("公司名称")) return "supplierName";
        if (h.equals("供何物") || h.equals("供货项")) return "itemName";
        if (h.equals("联系人")) return "contact";
        if (h.equals("电话") || h.equals("联系电话")) return "phone";
        if (h.equals("公司账户")) return "companyAccount";
        if (h.equals("开户银行")) return "openingBank";
        if (h.equals("主营品类")) return "mainCategory";
        if (h.equals("关系阶段") || h.equals("状态")) return "status";
        if (h.startsWith("品质")) return "quality";
        if (h.startsWith("交期")) return "delivery";
        if (h.startsWith("服务")) return "service";
        if (h.startsWith("价格(") || h.startsWith("价格（") || h.equals("价格分")) return "price";
        if (h.startsWith("账期(首付") || h.startsWith("账期（首付") || h.equals("账期分")) return "term";
        if (h.equals("备注")) return "remark";
        if (h.equals("类型")) return "itemType";
        if (h.equals("品类")) return "category";
        if (h.startsWith("集采报价") || h.startsWith("报价")) return "quotePrice";
        if (h.startsWith("首付")) return "firstPayRatio";
        if (h.startsWith("账期(天") || h.startsWith("账期（天") || h.equals("账期天数")) return "accountDays";
        if (h.contains("无息")) return "noInterest";
        if (h.contains("单采")) return "canSingleBuy";
        if (h.startsWith("代表")) return "primary";
        if (h.startsWith("材料")) return "material";
        if (h.startsWith("加工")) return "processing";
        if (h.startsWith("利润")) return "profit";
        if (h.contains("BOM")) return "bomEstimate";
        return null;
    }

    private void parseSuppliers(Sheet sh, int headerRow, Map<String, Integer> cols, DataFormatter fmt,
                                List<SupplierService.SupplierSheetRow> out) {
        boolean hasScores = cols.containsKey("quality") || cols.containsKey("delivery") || cols.containsKey("service")
                || cols.containsKey("price") || cols.containsKey("term");
        for (int r = headerRow + 1; r <= sh.getLastRowNum(); r++) {
            Row row = sh.getRow(r);
            if (row == null || blank(row, cols, fmt)) {
                continue;
            }
            SupplierService.SupplierSheetRow sr = new SupplierService.SupplierSheetRow();
            sr.setRowNum(r + 1);
            sr.setName(text(row, cols.get("supplierName"), fmt));
            for (String k : new String[]{"contact", "phone", "companyAccount", "openingBank", "mainCategory", "status", "remark"}) {
                if (!cols.containsKey(k)) {
                    continue;
                }
                String v = text(row, cols.get(k), fmt);
                if (NO_ACCESS.equals(v)) {
                    continue;
                }
                sr.getPresent().add(k);
                switch (k) {
                    case "contact": sr.setContact(v); break;
                    case "phone": sr.setPhone(v); break;
                    case "companyAccount": sr.setCompanyAccount(v); break;
                    case "openingBank": sr.setOpeningBank(v); break;
                    case "mainCategory": sr.setMainCategory(v); break;
                    case "status": sr.setStatus(v); break;
                    default: sr.setRemark(v); break;
                }
            }
            if (hasScores) {
                sr.getPresent().add("scores");
                sr.setQuality(score(row, cols.get("quality"), fmt, "品质", sr.getWarnings()));
                sr.setDelivery(score(row, cols.get("delivery"), fmt, "交期", sr.getWarnings()));
                sr.setService(score(row, cols.get("service"), fmt, "服务", sr.getWarnings()));
                sr.setPrice(score(row, cols.get("price"), fmt, "价格", sr.getWarnings()));
                sr.setTerm(score(row, cols.get("term"), fmt, "账期", sr.getWarnings()));
            }
            out.add(sr);
        }
    }

    private void parseSupplies(Sheet sh, int headerRow, Map<String, Integer> cols, DataFormatter fmt,
                               List<SupplierService.SupplySheetRow> out) {
        for (int r = headerRow + 1; r <= sh.getLastRowNum(); r++) {
            Row row = sh.getRow(r);
            if (row == null || blank(row, cols, fmt)) {
                continue;
            }
            SupplierService.SupplySheetRow sr = new SupplierService.SupplySheetRow();
            sr.setRowNum(r + 1);
            sr.setSupplierName(text(row, cols.get("supplierName"), fmt));
            sr.setItemName(text(row, cols.get("itemName"), fmt));
            List<String> w = sr.getWarnings();
            for (Map.Entry<String, Integer> e : cols.entrySet()) {
                String k = e.getKey();
                String v = text(row, e.getValue(), fmt);
                if (NO_ACCESS.equals(v) || "supplierName".equals(k) || "itemName".equals(k)) {
                    continue;
                }
                switch (k) {
                    case "itemType":
                        if (v != null && !"整机".equals(v) && !"配件".equals(v)) {
                            w.add("类型「" + v + "」应为 整机/配件,已保留原值");
                            continue;
                        }
                        sr.setItemType(v);
                        break;
                    case "category": sr.setCategory(v); break;
                    case "remark": sr.setRemark(v); break;
                    case "quotePrice": sr.setQuotePrice(decimal(v, "集采报价", w)); break;
                    case "material": sr.setMaterial(decimal(v, "材料", w)); break;
                    case "processing": sr.setProcessing(decimal(v, "加工", w)); break;
                    case "profit": sr.setProfit(decimal(v, "利润", w)); break;
                    case "bomEstimate": sr.setBomEstimate(decimal(v, "BOM估算", w)); break;
                    case "firstPayRatio": sr.setFirstPayRatio(ratio(v, w)); break;
                    case "accountDays": {
                        BigDecimal d = decimal(v, "账期天数", w);
                        sr.setAccountDays(d == null ? null : d.intValue());
                        break;
                    }
                    case "noInterest": sr.setNoInterest(bool(v, "账期无息", w)); break;
                    case "canSingleBuy": sr.setCanSingleBuy(bool(v, "可单采", w)); break;
                    case "primary": sr.setPrimary(bool(v, "代表项", w)); break;
                    default: continue;
                }
                sr.getPresent().add(k);
            }
            out.add(sr);
        }
    }

    // ============================== 单元格解析 ==============================

    private boolean blank(Row row, Map<String, Integer> cols, DataFormatter fmt) {
        for (Integer c : cols.values()) {
            if (text(row, c, fmt) != null) {
                return false;
            }
        }
        return true;
    }

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

    private Integer score(Row row, Integer col, DataFormatter fmt, String label, List<String> w) {
        String v = text(row, col, fmt);
        if (v == null) {
            return null;
        }
        try {
            int n = new BigDecimal(v).setScale(0, RoundingMode.HALF_UP).intValueExact();
            if (n < 0 || n > 100) {
                w.add(label + "分「" + v + "」应在 0-100 之间,已置空");
                return null;
            }
            return n;
        } catch (Exception e) {
            w.add(label + "分「" + v + "」不是数字,已置空");
            return null;
        }
    }

    private BigDecimal decimal(String v, String label, List<String> w) {
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(v.replace(",", "").replace("，", "").replace("¥", "").replace("元", "").trim());
        } catch (Exception e) {
            w.add(label + "「" + v + "」不是数字,已置空");
            return null;
        }
    }

    /** 首付比例:「30%」「30」「0.3」均按 30% 处理。 */
    private BigDecimal ratio(String v, List<String> w) {
        if (v == null) {
            return null;
        }
        boolean pct = v.endsWith("%");
        BigDecimal d = decimal(pct ? v.substring(0, v.length() - 1) : v, "首付比例", w);
        if (d == null) {
            return null;
        }
        if (pct || d.compareTo(BigDecimal.ONE) > 0) {
            d = d.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        }
        if (d.signum() < 0 || d.compareTo(BigDecimal.ONE) > 0) {
            w.add("首付比例「" + v + "」超出 0-100%,已置空");
            return null;
        }
        return d;
    }

    private Integer bool(String v, String label, List<String> w) {
        if (v == null) {
            return null;
        }
        switch (v) {
            case "是": case "Y": case "y": case "1": return 1;
            case "否": case "N": case "n": case "0": return 0;
            default:
                w.add(label + "「" + v + "」应填 是/否,已保留原值");
                return null;
        }
    }

    // ============================== 样式 ==============================

    private void header(Sheet sh, CellStyle head, String[] headers, int[] widths) {
        Row hr = sh.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell c = hr.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(head);
            sh.setColumnWidth(i, widths[i] * 256);
        }
    }

    private void text(Row row, int col, String v) {
        row.createCell(col).setCellValue(v == null ? "" : v);
    }

    private void num(Row row, int col, Integer v) {
        Cell c = row.createCell(col);
        if (v != null) {
            c.setCellValue(v);
        }
    }

    private void money(Row row, int col, BigDecimal v, boolean seeCost) {
        if (!seeCost) {
            text(row, col, NO_ACCESS);
            return;
        }
        Cell c = row.createCell(col);
        if (v != null) {
            c.setCellValue(v.doubleValue());
        }
    }

    private CellStyle headStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        return s;
    }
}
