package top.aole.rent.modules.asset.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.BorderStyle;
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
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.DataScope;
import top.aole.rent.common.auth.UserContext;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.domain.AssetBom;
import top.aole.rent.modules.asset.dto.BomSheetDtos;
import top.aole.rent.modules.asset.mapper.AssetBomMapper;
import top.aole.rent.modules.file.domain.FileObject;
import top.aole.rent.modules.file.mapper.FileObjectMapper;
import top.aole.rent.modules.supplier.domain.Supplier;
import top.aole.rent.modules.supplier.mapper.SupplierMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 配件 BOM 明细 ⇄ Excel。列与合同清单(《工程量清单计价表》)一致,后面接 BOM 自己的字段:
 * 序号/上级序号/名称/型号/规格/单位/数量/单价/金额/供应商/寿命(年)/质保到期/可维修/故障次数/残值率/备注。
 *
 * <p>「上级序号」填某一行的序号 = 挂在那一行下面(二级配件),留空 = 一级总成。
 * 导入为整表替换:先删本设备现有 BOM 再按表重建;已挂附件的配件会拦下来(附件要跟着节点走)。
 * BOM 只记配件构成与故障档案,<b>不参与合同金额</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssetBomExcelService {

    static final String SHEET_NAME = "配件BOM明细";
    static final String[] HEADERS = {"序号", "上级序号", "名称", "型号", "规格", "单位", "数量", "单价", "金额",
            "供应商", "寿命(年)", "质保到期", "可维修", "故障次数", "残值率(%)", "备注"};
    private static final int[] COL_WIDTHS = {7, 10, 22, 18, 24, 7, 8, 12, 14, 18, 10, 13, 9, 10, 12, 30};
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final List<DateTimeFormatter> DATE_FORMATS = Arrays.asList(
            DateTimeFormatter.ofPattern("yyyy-M-d"), DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("yyyy.M.d"), DateTimeFormatter.ofPattern("yyyy年M月d日"),
            DateTimeFormatter.ofPattern("M/d/yy", Locale.US), DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US));

    private final AssetBomMapper bomMapper;
    private final SupplierMapper supplierMapper;
    private final FileObjectMapper fileObjectMapper;
    private final AuditLogService auditLogService;

    // ============================== 导出 ==============================

    /** @param template true=只含表头的空模板 */
    public byte[] export(Asset asset, boolean template) {
        List<AssetBom> rows = template ? new ArrayList<>() : load(asset.getId());
        // 先父后子排好序,并给每行定序号(导出的序号/上级序号能自洽,改完可直接导回)
        List<AssetBom> ordered = orderByTree(rows);
        Map<Long, Integer> seqById = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            seqById.put(ordered.get(i).getId(), i + 1);
        }
        Map<Long, String> supplierNames = supplierNames(ordered);

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet(SHEET_NAME);
            CellStyle title = titleStyle(wb);
            CellStyle head = headStyle(wb);
            CellStyle body = bodyStyle(wb);
            CellStyle money = moneyStyle(wb);

            Row tr = sh.createRow(0);
            tr.setHeightInPoints(29);
            Cell tc = tr.createCell(0);
            tc.setCellValue("配件 BOM 明细" + (asset.getSerialNo() == null ? "" : "（" + asset.getSerialNo() + "）")
                    + "  —— 只记配件构成与故障档案，不参与合同金额");
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
            for (AssetBom b : ordered) {
                Row row = sh.createRow(r++);
                text(row, 0, String.valueOf(seqById.get(b.getId())), body);
                text(row, 1, b.getParentId() == null ? "" : String.valueOf(seqById.getOrDefault(b.getParentId(), 0)), body);
                text(row, 2, b.getName(), body);
                text(row, 3, b.getModel(), body);
                text(row, 4, b.getSpec(), body);
                text(row, 5, b.getUnit(), body);
                number(row, 6, b.getQty(), body);
                number(row, 7, b.getUnitCost(), money);
                number(row, 8, subtotal(b), money);
                text(row, 9, supplierNames.get(b.getSupplierId()), body);
                number(row, 10, b.getLifeYears(), body);
                text(row, 11, b.getWarrantyUntil() == null ? null : b.getWarrantyUntil().toString(), body);
                text(row, 12, Integer.valueOf(0).equals(b.getRepairable()) ? "否" : "是", body);
                number(row, 13, b.getFaultCount() == null ? null : BigDecimal.valueOf(b.getFaultCount()), body);
                number(row, 14, b.getResidualRate() == null ? null
                        : b.getResidualRate().multiply(BigDecimal.valueOf(100)).stripTrailingZeros(), body);
                text(row, 15, b.getRemark(), body);
            }
            sh.createFreezePane(3, 2);
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "配件 BOM 导出失败:" + e.getMessage());
        }
    }

    private static void requireCostRole(String action) {
        if (!DataScope.canSeeCost(UserContext.getRole())) {
            throw new BizException(403, "当前角色无权" + action);
        }
    }

    /** 合价:手动合价优先,否则 数量×单价。 */
    private static BigDecimal subtotal(AssetBom b) {
        if (b.getSubtotalOverride() != null) {
            return b.getSubtotalOverride();
        }
        if (b.getQty() == null || b.getUnitCost() == null) {
            return null;
        }
        return b.getQty().multiply(b.getUnitCost()).setScale(2, RoundingMode.HALF_UP);
    }

    /** 先父后子(同级按 seq/id),保证导出的「上级序号」总指向前面的行。 */
    private List<AssetBom> orderByTree(List<AssetBom> rows) {
        Map<Long, List<AssetBom>> byParent = new LinkedHashMap<>();
        for (AssetBom b : rows) {
            byParent.computeIfAbsent(b.getParentId(), k -> new ArrayList<>()).add(b);
        }
        for (List<AssetBom> list : byParent.values()) {
            list.sort(Comparator.comparing((AssetBom x) -> x.getSeq() == null ? Integer.MAX_VALUE : x.getSeq())
                    .thenComparing(AssetBom::getId));
        }
        List<AssetBom> out = new ArrayList<>();
        appendChildren(byParent, null, out, new HashSet<>());
        // 父节点已被删/不在本设备时兜底补上,避免漏行
        for (AssetBom b : rows) {
            if (!out.contains(b)) {
                out.add(b);
            }
        }
        return out;
    }

    private void appendChildren(Map<Long, List<AssetBom>> byParent, Long parentId, List<AssetBom> out, Set<Long> seen) {
        for (AssetBom b : byParent.getOrDefault(parentId, new ArrayList<>())) {
            if (!seen.add(b.getId())) {
                continue;
            }
            out.add(b);
            appendChildren(byParent, b.getId(), out, seen);
        }
    }

    private Map<Long, String> supplierNames(List<AssetBom> rows) {
        Set<Long> ids = new HashSet<>();
        for (AssetBom b : rows) {
            if (b.getSupplierId() != null) {
                ids.add(b.getSupplierId());
            }
        }
        Map<Long, String> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Supplier s : supplierMapper.selectBatchIds(ids)) {
            out.put(s.getId(), s.getName());
        }
        return out;
    }

    // ============================== 导入 ==============================

    @Transactional
    public BomSheetDtos.ImportResult importFile(Asset asset, MultipartFile file) {
        requireCostRole("导入配件 BOM 明细");
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
        List<BomSheetDtos.Row> rows;
        try (InputStream in = file.getInputStream()) {
            rows = parse(in);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "Excel 解析失败,请确认文件未损坏:" + e.getMessage());
        }
        if (rows.isEmpty()) {
            throw new BizException(400, "没解析到配件明细行,请确认表格里有「名称」列且下方有数据");
        }
        return replaceAll(asset, rows);
    }

    /** 整表替换:删掉本设备现有 BOM(有附件的拦下),再按表重建父子关系。 */
    @Transactional
    public BomSheetDtos.ImportResult replaceAll(Asset asset, List<BomSheetDtos.Row> rows) {
        List<AssetBom> existing = load(asset.getId());
        List<String> withFiles = new ArrayList<>();
        Set<Long> bomIds = new HashSet<>();
        for (AssetBom b : existing) {
            bomIds.add(b.getId());
        }
        if (!bomIds.isEmpty()) {
            Map<Long, AssetBom> byId = new HashMap<>();
            for (AssetBom b : existing) {
                byId.put(b.getId(), b);
            }
            for (FileObject fo : fileObjectMapper.selectList(new LambdaQueryWrapper<FileObject>()
                    .eq(FileObject::getBizType, "asset_bom")
                    .in(FileObject::getBizId, bomIds))) {
                AssetBom b = byId.get(fo.getBizId());
                if (b != null && !withFiles.contains(b.getName())) {
                    withFiles.add(b.getName());
                }
            }
        }
        if (!withFiles.isEmpty()) {
            throw new BizException(400, "配件「" + String.join("、", withFiles)
                    + "」挂了附件,整表导入会把附件挂空;请先删除这些配件的附件,或改用页面逐项编辑");
        }

        BomSheetDtos.ImportResult res = new BomSheetDtos.ImportResult();
        for (AssetBom b : existing) {
            bomMapper.deleteById(b.getId());
        }
        Map<Integer, Long> idBySeq = new HashMap<>();
        // 先建一级,再建有上级的(上级序号必须指向本表里已出现的行)
        for (BomSheetDtos.Row r : rows) {
            res.setTotal(res.getTotal() + 1);
            for (String w : r.getWarnings()) {
                res.getMessages().add("第 " + r.getSeq() + " 行「" + r.getName() + "」:" + w);
            }
        }
        List<BomSheetDtos.Row> pending = new ArrayList<>(rows);
        int guard = 0;
        while (!pending.isEmpty() && guard++ <= rows.size() + 1) {
            List<BomSheetDtos.Row> next = new ArrayList<>();
            for (BomSheetDtos.Row r : pending) {
                Long parentId = null;
                if (r.getParentSeq() != null) {
                    parentId = idBySeq.get(r.getParentSeq());
                    if (parentId == null) {
                        next.add(r); // 上级还没建,下一轮再来
                        continue;
                    }
                }
                AssetBom b = new AssetBom();
                b.setAssetId(asset.getId());
                b.setParentId(parentId);
                b.setSeq(r.getSeq());
                b.setName(r.getName());
                b.setModel(r.getModel());
                b.setSpec(r.getSpec());
                b.setUnit(r.getUnit());
                b.setQty(r.getQty() == null ? BigDecimal.ONE : r.getQty());
                b.setUnitCost(r.getUnitCost());
                b.setSubtotalOverride(Boolean.TRUE.equals(r.getAmountManual()) ? r.getAmount() : null);
                b.setSupplierId(r.getSupplierId());
                b.setLifeYears(r.getLifeYears());
                b.setWarrantyUntil(r.getWarrantyUntil());
                b.setRepairable(Boolean.FALSE.equals(r.getRepairable()) ? 0 : 1);
                b.setFaultCount(r.getFaultCount() == null ? 0 : r.getFaultCount());
                b.setResidualRate(r.getResidualRate());
                b.setRemark(r.getRemark());
                bomMapper.insert(b);
                if (r.getSeq() != null) {
                    idBySeq.put(r.getSeq(), b.getId());
                }
                res.setImported(res.getImported() + 1);
            }
            if (next.size() == pending.size()) {
                for (BomSheetDtos.Row r : next) {
                    res.setSkipped(res.getSkipped() + 1);
                    res.getMessages().add("第 " + r.getSeq() + " 行「" + r.getName() + "」的上级序号 "
                            + r.getParentSeq() + " 在表里找不到,已跳过");
                }
                break;
            }
            pending = next;
        }
        res.setBomTotal(topLevelTotal(asset.getId()));
        auditLogService.record("配件BOM导入", "asset", asset.getId(), AuditLogService.EXECUTED,
                "导入 " + res.getImported() + " 行,跳过 " + res.getSkipped());
        return res;
    }

    /** 一级项合价合计(只做展示,不影响合同金额)。 */
    private BigDecimal topLevelTotal(Long assetId) {
        BigDecimal total = BigDecimal.ZERO;
        for (AssetBom b : load(assetId)) {
            if (b.getParentId() == null) {
                BigDecimal v = subtotal(b);
                total = total.add(v == null ? BigDecimal.ZERO : v);
            }
        }
        return total.setScale(2, RoundingMode.HALF_UP);
    }

    private List<AssetBom> load(Long assetId) {
        List<AssetBom> rows = bomMapper.selectList(new LambdaQueryWrapper<AssetBom>()
                .eq(AssetBom::getAssetId, assetId));
        rows.sort(Comparator.comparing(AssetBom::getId));
        return rows;
    }

    // ============================== 解析 ==============================

    List<BomSheetDtos.Row> parse(InputStream in) throws Exception {
        try (Workbook wb = WorkbookFactory.create(in)) {
            DataFormatter fmt = new DataFormatter(Locale.CHINA);
            Map<String, Long> suppliersByName = new HashMap<>();
            for (Supplier s : supplierMapper.selectList(new LambdaQueryWrapper<>())) {
                if (s.getName() != null) {
                    suppliersByName.putIfAbsent(s.getName().replaceAll("\\s+", ""), s.getId());
                }
            }
            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet sh = wb.getSheetAt(si);
                for (int h = 0; h <= Math.min(8, sh.getLastRowNum()); h++) {
                    Map<String, Integer> cols = headerMap(sh.getRow(h), fmt);
                    if (cols.containsKey("名称")) {
                        return parseRows(sh, h, cols, fmt, suppliersByName);
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
            String key = canonicalHeader(fmt.formatCellValue(cell).replaceAll("\\s+", ""));
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
        if (h.startsWith("上级")) return "上级序号";
        if (h.equals("序号")) return "序号";
        if (h.equals("名称") || h.equals("配件名称") || h.equals("项目名称")) return "名称";
        if (h.equals("型号")) return "型号";
        if (h.equals("规格") || h.equals("配置")) return "规格";
        if (h.equals("单位") || h.equals("计量单位")) return "单位";
        if (h.equals("数量")) return "数量";
        if (h.startsWith("单价")) return "单价";
        if (h.equals("金额") || h.equals("合价") || h.equals("小计")) return "金额";
        if (h.contains("供应商") || h.contains("质保方")) return "供应商";
        if (h.startsWith("寿命")) return "寿命";
        if (h.contains("质保到期") || h.equals("质保")) return "质保到期";
        if (h.startsWith("可维修")) return "可维修";
        if (h.contains("故障")) return "故障次数";
        if (h.startsWith("残值率")) return "残值率";
        if (h.startsWith("备注") || h.equals("说明")) return "备注";
        return null;
    }

    private List<BomSheetDtos.Row> parseRows(Sheet sh, int headerRow, Map<String, Integer> cols, DataFormatter fmt,
                                             Map<String, Long> suppliersByName) {
        List<BomSheetDtos.Row> out = new ArrayList<>();
        int auto = 0;
        for (int r = headerRow + 1; r <= sh.getLastRowNum(); r++) {
            Row row = sh.getRow(r);
            if (row == null) {
                continue;
            }
            String name = text(row, cols.get("名称"), fmt);
            if (name == null) {
                continue;
            }
            auto++;
            BomSheetDtos.Row x = new BomSheetDtos.Row();
            BigDecimal seq = decimal(row, cols.get("序号"), fmt);
            x.setSeq(seq == null ? auto : seq.intValue());
            BigDecimal parentSeq = decimal(row, cols.get("上级序号"), fmt);
            x.setParentSeq(parentSeq == null ? null : parentSeq.intValue());
            x.setName(name);
            x.setModel(text(row, cols.get("型号"), fmt));
            x.setSpec(text(row, cols.get("规格"), fmt));
            x.setUnit(text(row, cols.get("单位"), fmt));
            x.setQty(decimal(row, cols.get("数量"), fmt));
            x.setUnitCost(decimal(row, cols.get("单价"), fmt));
            BigDecimal amount = decimal(row, cols.get("金额"), fmt);
            BigDecimal autoAmount = x.getQty() == null || x.getUnitCost() == null ? null
                    : x.getQty().multiply(x.getUnitCost()).setScale(2, RoundingMode.HALF_UP);
            boolean manual = amount != null && (autoAmount == null || amount.compareTo(autoAmount) != 0);
            x.setAmountManual(manual);
            x.setAmount(manual ? amount : autoAmount);

            String supplier = text(row, cols.get("供应商"), fmt);
            x.setSupplierName(supplier);
            if (supplier != null) {
                Long sid = suppliersByName.get(supplier.replaceAll("\\s+", ""));
                if (sid == null) {
                    x.getWarnings().add("供应商「" + supplier + "」在供应商·上游里找不到,已留空");
                }
                x.setSupplierId(sid);
            }
            x.setLifeYears(decimal(row, cols.get("寿命"), fmt));
            x.setWarrantyUntil(date(row, cols.get("质保到期"), fmt, x));
            String repairable = text(row, cols.get("可维修"), fmt);
            x.setRepairable(repairable == null || !("否".equals(repairable) || "N".equalsIgnoreCase(repairable)
                    || "false".equalsIgnoreCase(repairable) || "0".equals(repairable)));
            BigDecimal fault = decimal(row, cols.get("故障次数"), fmt);
            x.setFaultCount(fault == null ? 0 : Math.max(0, fault.intValue()));
            BigDecimal residual = decimal(row, cols.get("残值率"), fmt);
            if (residual != null) {
                // 支持「12」与「0.12」两种写法
                BigDecimal rate = residual.compareTo(BigDecimal.ONE) > 0
                        ? residual.divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP) : residual;
                if (rate.compareTo(BigDecimal.ONE) > 0) {
                    x.getWarnings().add("残值率「" + residual.toPlainString() + "」超过 100%,已留空");
                } else {
                    x.setResidualRate(rate);
                }
            }
            x.setRemark(text(row, cols.get("备注"), fmt));
            out.add(x);
        }
        return out;
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
        String cleaned = fmt.formatCellValue(cell).trim()
                .replace("￥", "").replace("¥", "").replace(",", "").replace("%", "").replace("元", "").trim();
        if (cleaned.isEmpty() || "-".equals(cleaned) || "—".equals(cleaned) || "/".equals(cleaned)) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private LocalDate date(Row row, Integer col, DataFormatter fmt, BomSheetDtos.Row x) {
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
        x.getWarnings().add("质保到期「" + raw + "」无法识别,已留空(请用 2027-08-01 这类格式)");
        return null;
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
        f.setFontHeightInPoints((short) 13);
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

    private CellStyle border(CellStyle s) {
        s.setBorderTop(BorderStyle.THIN);
        s.setBorderBottom(BorderStyle.THIN);
        s.setBorderLeft(BorderStyle.THIN);
        s.setBorderRight(BorderStyle.THIN);
        return s;
    }
}
