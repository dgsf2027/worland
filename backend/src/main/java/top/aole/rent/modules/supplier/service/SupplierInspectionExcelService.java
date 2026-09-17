package top.aole.rent.modules.supplier.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.PictureData;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Shape;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.common.util.BusinessScope;
import top.aole.rent.modules.file.domain.FileObject;
import top.aole.rent.modules.file.service.FileStorageService;
import top.aole.rent.modules.supplier.dto.InspectionDtos;
import top.aole.rent.modules.supplier.service.SupplierInspectionService.SheetImage;
import top.aole.rent.modules.supplier.service.SupplierInspectionService.SheetRow;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static top.aole.rent.modules.supplier.service.SupplierInspectionService.COL_IMAGE;
import static top.aole.rent.modules.supplier.service.SupplierInspectionService.COL_IMPRESSION;
import static top.aole.rent.modules.supplier.service.SupplierInspectionService.COL_PERFORMANCE;
import static top.aole.rent.modules.supplier.service.SupplierInspectionService.COL_PROFILE;
import static top.aole.rent.modules.supplier.service.SupplierInspectionService.COL_STAFF;
import static top.aole.rent.modules.supplier.service.SupplierInspectionService.IMAGE_BIZ_TYPE;

/**
 * 供应商考察 ⇄「厂家考察汇总表」Excel(对齐 20260916 版)。导出与导入同一套列,导出的文件改完可直接导回(按公司名称自动更新)。
 *
 * <p>列:序号 / 公司名称 / 法人 / 注册资本/万元 / 成立时间 / 公司业务范围 / 业务类型 / 公司地址 / 主要联系人 / 主要电话 /
 * 业绩/万元 / 社保员工 / 产品图片 / 考察观后感 / 是否合格(是/否) / 是否合格(说明) / 考察记录压缩包。
 * 按表头名称识别列,列顺序可调整;旧版表格(「业务范围」即业务类型、无新列)仍可导入,缺的列保留系统原值。
 *
 * <p>产品图片:导入时提取「产品图片」列里的图片 —— WPS 的单元格内嵌图片(=DISPIMG 公式,xls 存在 ETCellImageData 流、
 * xlsx 存在 xl/cellimages.xml)以及落在该单元格上的浮动图片;按图片内容去重,表格里该行有图时以表格为准同步。
 * 导出时把图片嵌到对应单元格里。说明列与压缩包列由系统生成,导入时忽略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierInspectionExcelService {

    private final SupplierInspectionService inspectionService;
    private final FileStorageService fileStorageService;

    static final String SHEET_NAME = "汇总表";
    static final String[] HEADERS = {
            "序号", "公司名称", "法人", "注册资本/万元", "成立时间", "公司业务范围", "业务类型", "公司地址",
            "主要联系人", "主要电话", "业绩/万元", "社保员工", "产品图片", "考察观后感", "是否合格", "是否合格", "考察记录压缩包"
    };
    private static final int[] COL_WIDTHS = {9, 29, 13, 15, 15, 40, 12, 25, 11, 13, 14, 14, 26, 40, 14, 36, 18};
    static final int IMAGE_COL = 12;
    static final String PASS_NOTE = "列入“供应商上游”模块，做好关联关系";
    static final String FAIL_NOTE = "不列入“供应商上游”模块，不做关联关系";
    /** 导入的产品图片文件名前缀 */
    static final String IMAGE_NAME_PREFIX = "产品图片";

    /** 表格含嵌入图片,体积较大 */
    static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final float IMAGE_ROW_HEIGHT = 110f;
    private static final Pattern DISPIMG_ID = Pattern.compile("(ID_[0-9A-Za-z]+)");
    private static final List<DateTimeFormatter> DATE_FORMATS = Arrays.asList(
            DateTimeFormatter.ofPattern("yyyy-M-d"), DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"), DateTimeFormatter.ofPattern("yyyy年M月d日"),
            DateTimeFormatter.ofPattern("M/d/yy", Locale.US), DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US));

    // ============================== 导出 ==============================

    /** @param template true=只含表头的空模板 */
    public byte[] export(boolean template) {
        List<InspectionDtos.Item> items = template ? new ArrayList<>() : inspectionService.listAll();
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            XSSFSheet sh = wb.createSheet(SHEET_NAME);
            CellStyle head = headStyle(wb);
            CellStyle body = bodyStyle(wb);
            Row hr = sh.createRow(0);
            hr.setHeightInPoints(30);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(head);
                sh.setColumnWidth(i, COL_WIDTHS[i] * 256);
            }
            XSSFDrawing drawing = sh.createDrawingPatriarch();
            int r = 1;
            int seq = 1;
            for (InspectionDtos.Item it : items) {
                Row row = sh.createRow(r);
                int c = 0;
                put(row, c++, it.getSortNo() == null ? String.valueOf(seq) : String.valueOf(it.getSortNo()), body);
                seq++;
                put(row, c++, it.getCompanyName(), body);
                put(row, c++, it.getLegalPerson(), body);
                put(row, c++, it.getRegisteredCapitalWan(), body);
                put(row, c++, it.getEstablishedDate() == null ? null : it.getEstablishedDate().toString(), body);
                put(row, c++, it.getCompanyProfile(), body);
                put(row, c++, String.join("/", it.getBusinessScope()), body);
                put(row, c++, it.getAddress(), body);
                put(row, c++, it.getContact(), body);
                put(row, c++, it.getPhone(), body);
                put(row, c++, it.getPerformanceWan(), body);
                put(row, c++, it.getSocialStaff(), body);
                int embedded = it.getId() == null ? 0 : embedImages(wb, drawing, sh, r, it.getId());
                put(row, c++, embedded > 0 ? "" : it.getProductImageNote(), body);
                put(row, c++, it.getImpression(), body);
                put(row, c++, yesNo(it.getResult()), body);
                put(row, c++, note(it.getResult()), body);
                put(row, c, String.join("、", it.getArchiveNames()), body);
                if (embedded > 0) {
                    row.setHeightInPoints(IMAGE_ROW_HEIGHT);
                }
                r++;
            }
            sh.createFreezePane(2, 1);
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "考察汇总表导出失败:" + e.getMessage());
        }
    }

    /** 把该考察的产品图片嵌进「产品图片」单元格(多张并排)。返回嵌入张数。 */
    private int embedImages(XSSFWorkbook wb, XSSFDrawing drawing, Sheet sh, int rowIdx, Long inspectionId) {
        List<byte[]> datas = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        for (FileObject fo : fileStorageService.listRaw(IMAGE_BIZ_TYPE, inspectionId)) {
            Integer type = pictureType(fo.getFileName());
            byte[] data = type == null ? null : fileStorageService.readBytes(fo);
            if (data != null) {
                datas.add(data);
                types.add(type);
            }
        }
        if (datas.isEmpty()) {
            return 0;
        }
        int cellWidthEmu = Units.pixelToEMU(Math.round(sh.getColumnWidthInPixels(IMAGE_COL)));
        int cellHeightEmu = Units.toEMU(IMAGE_ROW_HEIGHT);
        int pad = Units.pixelToEMU(3);
        int slot = cellWidthEmu / datas.size();
        for (int i = 0; i < datas.size(); i++) {
            int idx = wb.addPicture(datas.get(i), types.get(i));
            XSSFClientAnchor anchor = new XSSFClientAnchor(i * slot + pad, pad, (i + 1) * slot - pad, cellHeightEmu - pad,
                    IMAGE_COL, rowIdx, IMAGE_COL, rowIdx);
            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
            drawing.createPicture(anchor, idx);
        }
        return datas.size();
    }

    private static Integer pictureType(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) {
            return Workbook.PICTURE_TYPE_PNG;
        }
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) {
            return Workbook.PICTURE_TYPE_JPEG;
        }
        if (n.endsWith(".gif")) {
            return XSSFWorkbook.PICTURE_TYPE_GIF;
        }
        return null; // webp/heic 等 Excel 不支持嵌入
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
            throw new BizException(400, "导入文件超过 " + (MAX_BYTES / 1024 / 1024) + "MB 上限");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xls") && !name.endsWith(".xlsx")) {
            throw new BizException(400, "仅支持 .xls / .xlsx 格式");
        }
        List<SheetRow> rows;
        try (InputStream in = file.getInputStream()) {
            rows = parse(readAll(in));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "Excel 解析失败,请确认文件未损坏:" + e.getMessage());
        }
        InspectionDtos.ImportResult res = inspectionService.importRows(rows);
        syncImages(rows, res);
        return res;
    }

    /** 产品图片同步:该行表格里有图时,新增表里有而系统没有的图,删除系统里有而表里没有的图(按内容比对)。 */
    void syncImages(List<SheetRow> rows, InspectionDtos.ImportResult res) {
        for (SheetRow sr : rows) {
            if (sr.getInspectionId() == null || sr.getImages().isEmpty()) {
                continue;
            }
            try {
                Map<String, SheetImage> wanted = new LinkedHashMap<>();
                for (SheetImage img : sr.getImages()) {
                    wanted.putIfAbsent(md5(img.getData()), img);
                }
                Map<String, FileObject> existing = new HashMap<>();
                List<FileObject> stale = new ArrayList<>();
                for (FileObject fo : fileStorageService.listRaw(IMAGE_BIZ_TYPE, sr.getInspectionId())) {
                    byte[] data = fileStorageService.readBytes(fo);
                    String h = data == null ? null : md5(data);
                    if (h != null && wanted.containsKey(h) && !existing.containsKey(h)) {
                        existing.put(h, fo);
                    } else {
                        stale.add(fo);
                    }
                }
                int added = 0;
                int seq = existing.size() + 1;
                for (Map.Entry<String, SheetImage> e : wanted.entrySet()) {
                    if (existing.containsKey(e.getKey())) {
                        continue;
                    }
                    SheetImage img = e.getValue();
                    String ext = img.getExt() == null ? "png" : img.getExt();
                    fileStorageService.storeBytes(img.getData(), IMAGE_NAME_PREFIX + "-" + (seq++) + "." + ext,
                            "image/" + ("jpg".equals(ext) ? "jpeg" : ext), IMAGE_BIZ_TYPE, sr.getInspectionId());
                    added++;
                }
                for (FileObject fo : stale) {
                    fileStorageService.removeRaw(fo.getId());
                }
                res.setImagesAdded(res.getImagesAdded() + added);
                if (added > 0 || !stale.isEmpty()) {
                    res.getMessages().add(new InspectionDtos.RowMessage(sr.getRowNum(), sr.getCompanyName(), "info",
                            "产品图片已同步:新增 " + added + " 张" + (stale.isEmpty() ? "" : ",移除 " + stale.size() + " 张")
                                    + ",现有 " + wanted.size() + " 张"));
                }
            } catch (Exception e) {
                log.warn("考察产品图片同步失败: row={}, id={}", sr.getRowNum(), sr.getInspectionId(), e);
                res.getMessages().add(new InspectionDtos.RowMessage(sr.getRowNum(), sr.getCompanyName(), "warn",
                        "产品图片保存失败:" + e.getMessage()));
            }
        }
    }

    /** 解析首个含「公司名称」表头的工作表。 */
    List<SheetRow> parse(byte[] data) throws Exception {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Map<String, SheetImage> cellImages = wpsCellImages(wb, data);
            DataFormatter fmt = new DataFormatter(Locale.CHINA);
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sh = wb.getSheetAt(s);
                for (int h = 0; h <= Math.min(5, sh.getLastRowNum()); h++) {
                    Map<String, Integer> cols = headerMap(sh.getRow(h), fmt);
                    if (cols.containsKey("公司名称")) {
                        Map<Integer, List<SheetImage>> floating = cols.containsKey(COL_IMAGE)
                                ? floatingImages(sh, cols.get(COL_IMAGE)) : new HashMap<>();
                        return parseRows(sh, h, cols, fmt, cellImages, floating);
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
        // 新版「公司业务范围」是公司简介;「业务类型」(旧版叫「业务范围」)才是货架/阁楼/播种墙
        if (h.equals(COL_PROFILE) || h.contains("公司简介") || h.contains("经营范围")) return COL_PROFILE;
        if (h.startsWith("业务类型") || h.startsWith("业务范围")) return "业务类型";
        if (h.contains("地址")) return "公司地址";
        if (h.contains("联系人")) return "主要联系人";
        if (h.contains("电话") || h.contains("联系方式") || h.contains("手机")) return "主要电话";
        if (h.startsWith("业绩")) return COL_PERFORMANCE;
        if (h.contains("社保")) return COL_STAFF;
        if (h.contains("图片")) return COL_IMAGE;
        if (h.contains("观后感")) return COL_IMPRESSION;
        if (h.startsWith("是否合格") || h.equals("考察结果")) return "是否合格";
        return null;
    }

    private List<SheetRow> parseRows(Sheet sh, int headerRow, Map<String, Integer> cols, DataFormatter fmt,
                                     Map<String, SheetImage> cellImages, Map<Integer, List<SheetImage>> floating) {
        List<SheetRow> out = new ArrayList<>();
        Integer imageCol = cols.get(COL_IMAGE);
        for (int r = headerRow + 1; r <= sh.getLastRowNum(); r++) {
            Row row = sh.getRow(r);
            if (row == null || isBlankRow(row, cols, fmt)) {
                continue;
            }
            SheetRow sr = new SheetRow();
            sr.setRowNum(r + 1);
            sr.setCompanyName(text(row, cols.get("公司名称"), fmt));
            sr.setLegalPerson(text(row, cols.get("法人"), fmt));
            sr.setRegisteredCapitalWan(text(row, cols.get("注册资本"), fmt));
            sr.setAddress(text(row, cols.get("公司地址"), fmt));
            sr.setContact(text(row, cols.get("主要联系人"), fmt));
            sr.setPhone(text(row, cols.get("主要电话"), fmt));
            for (String opt : Arrays.asList(COL_PROFILE, COL_PERFORMANCE, COL_STAFF, COL_IMAGE, COL_IMPRESSION)) {
                if (cols.containsKey(opt)) {
                    sr.getColumns().add(opt);
                }
            }
            sr.setCompanyProfile(text(row, cols.get(COL_PROFILE), fmt));
            sr.setPerformanceWan(text(row, cols.get(COL_PERFORMANCE), fmt));
            sr.setSocialStaff(text(row, cols.get(COL_STAFF), fmt));
            sr.setImpression(text(row, cols.get(COL_IMPRESSION), fmt));
            if (imageCol != null) {
                readImageCell(row.getCell(imageCol), fmt, cellImages, sr);
                sr.getImages().addAll(floating.getOrDefault(r, new ArrayList<>()));
            }

            String seq = text(row, cols.get("序号"), fmt);
            if (seq != null) {
                try {
                    sr.setSortNo(new BigDecimal(seq).intValueExact());
                } catch (Exception e) {
                    sr.getWarnings().add("序号「" + seq + "」不是整数,已按末尾排序");
                }
            }
            sr.setEstablishedDate(date(row, cols.get("成立时间"), fmt, sr));
            sr.setBusinessScope(scope(text(row, cols.get("业务类型"), fmt), sr));
            sr.setResult(result(text(row, cols.get("是否合格"), fmt), sr));
            out.add(sr);
        }
        return out;
    }

    /** 产品图片单元格:WPS =DISPIMG("ID_xxx",1) 取内嵌图片;其它文字作为图片说明(如「无播种墙图片」)。 */
    private void readImageCell(Cell cell, DataFormatter fmt, Map<String, SheetImage> cellImages, SheetRow sr) {
        if (cell == null) {
            return;
        }
        String raw = cell.getCellType() == CellType.FORMULA ? cell.getCellFormula() : fmt.formatCellValue(cell);
        if (raw == null) {
            return;
        }
        if (raw.contains("DISPIMG")) {
            Matcher m = DISPIMG_ID.matcher(raw);
            if (m.find()) {
                SheetImage img = cellImages.get(m.group(1));
                if (img != null) {
                    sr.getImages().add(img);
                } else {
                    sr.getWarnings().add("产品图片没能从表格里读出来(请用 WPS 另存后重试,或在系统里手动上传)");
                }
            }
            return;
        }
        String note = raw.trim();
        sr.setProductImageNote(note.isEmpty() ? null : note);
    }

    private boolean isBlankRow(Row row, Map<String, Integer> cols, DataFormatter fmt) {
        for (Map.Entry<String, Integer> e : cols.entrySet()) {
            if (!"序号".equals(e.getKey()) && !COL_IMAGE.equals(e.getKey()) && text(row, e.getValue(), fmt) != null) {
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

    private LocalDate date(Row row, Integer col, DataFormatter fmt, SheetRow sr) {
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

    private List<String> scope(String raw, SheetRow sr) {
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
                sr.getWarnings().add("业务类型「" + t + "」不在 货架/阁楼/播种墙 之内,已忽略");
            }
        }
        return picked;
    }

    private String result(String raw, SheetRow sr) {
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

    // ============================== 图片提取 ==============================

    /**
     * WPS 单元格内嵌图片:图片 ID → 图片。xls 存在 OLE 流 ETCellImageData(一个 zip 包),
     * xlsx 直接在包里;包内 xl/cellimages.xml 给出 ID → rId,xl/_rels/cellimages.xml.rels 给出 rId → 图片路径。
     */
    static Map<String, SheetImage> wpsCellImages(Workbook wb, byte[] fileBytes) {
        byte[] zip = null;
        try {
            if (wb instanceof HSSFWorkbook) {
                DirectoryNode dir = ((HSSFWorkbook) wb).getDirectory();
                if (dir.hasEntry("ETCellImageData")) {
                    try (DocumentInputStream in = dir.createDocumentInputStream("ETCellImageData")) {
                        zip = readAll(in);
                    }
                }
            } else {
                zip = fileBytes;
            }
            return zip == null ? new HashMap<>() : cellImagesFromPackage(zip);
        } catch (Exception e) {
            log.warn("读取 WPS 单元格图片失败", e);
            return new HashMap<>();
        }
    }

    static Map<String, SheetImage> cellImagesFromPackage(byte[] zip) throws Exception {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName().toLowerCase(Locale.ROOT);
                if (!e.isDirectory() && (n.equals("xl/cellimages.xml") || n.equals("xl/_rels/cellimages.xml.rels")
                        || n.startsWith("xl/media/"))) {
                    parts.put(n, readAll(zin));
                }
            }
        }
        Map<String, SheetImage> out = new HashMap<>();
        byte[] xml = parts.get("xl/cellimages.xml");
        byte[] rels = parts.get("xl/_rels/cellimages.xml.rels");
        if (xml == null || rels == null) {
            return out;
        }
        Map<String, String> targetByRid = new HashMap<>();
        Matcher rm = Pattern.compile("<Relationship\\b[^>]*>").matcher(new String(rels, StandardCharsets.UTF_8));
        while (rm.find()) {
            String id = attr(rm.group(), "Id");
            String target = attr(rm.group(), "Target");
            if (id != null && target != null) {
                targetByRid.put(id, target);
            }
        }
        Matcher cm = Pattern.compile("<(?:\\w+:)?cellImage\\b.*?</(?:\\w+:)?cellImage>", Pattern.DOTALL)
                .matcher(new String(xml, StandardCharsets.UTF_8));
        while (cm.find()) {
            String block = cm.group();
            Matcher nm = Pattern.compile("<(?:\\w+:)?cNvPr\\b[^>]*>").matcher(block);
            Matcher bm = Pattern.compile("<(?:\\w+:)?blip\\b[^>]*>").matcher(block);
            if (!nm.find() || !bm.find()) {
                continue;
            }
            String name = attr(nm.group(), "name");
            String rid = attr(bm.group(), "r:embed");
            String target = rid == null ? null : targetByRid.get(rid);
            if (name == null || target == null) {
                continue;
            }
            String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
            byte[] data = parts.get(path.toLowerCase(Locale.ROOT));
            if (data != null) {
                out.put(name, new SheetImage(data, extOf(path)));
            }
        }
        return out;
    }

    /** 左上角落在「产品图片」列的浮动图片:行号 → 图片。 */
    static Map<Integer, List<SheetImage>> floatingImages(Sheet sh, int imageCol) {
        Map<Integer, List<SheetImage>> out = new HashMap<>();
        Drawing<?> drawing = sh.getDrawingPatriarch();
        if (drawing == null) {
            return out;
        }
        for (Shape shape : drawing) {
            if (!(shape instanceof Picture)) {
                continue;
            }
            Picture pic = (Picture) shape;
            ClientAnchor a = pic.getClientAnchor();
            PictureData pd = pic.getPictureData();
            if (a == null || pd == null || a.getCol1() != imageCol) {
                continue;
            }
            out.computeIfAbsent(a.getRow1(), k -> new ArrayList<>())
                    .add(new SheetImage(pd.getData(), normalizeExt(pd.suggestFileExtension())));
        }
        return out;
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\s" + Pattern.quote(name) + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static String extOf(String path) {
        int dot = path.lastIndexOf('.');
        return normalizeExt(dot < 0 ? "png" : path.substring(dot + 1));
    }

    private static String normalizeExt(String ext) {
        String e = ext == null ? "png" : ext.toLowerCase(Locale.ROOT);
        return "jpeg".equals(e) ? "jpg" : e;
    }

    /** 读完输入流(不关闭),超过 MAX_BYTES 报错。 */
    static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BYTES) {
                throw new BizException(400, "文件超过 " + (MAX_BYTES / 1024 / 1024) + "MB 上限");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    static String md5(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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
        s.setWrapText(true);
        return s;
    }

    private CellStyle bodyStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        s.setWrapText(true);
        return s;
    }
}
