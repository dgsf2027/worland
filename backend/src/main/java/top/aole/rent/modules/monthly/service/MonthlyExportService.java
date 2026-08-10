package top.aole.rent.modules.monthly.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.monthly.dto.MonthlyDtos;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 月度报表包一键导出(M3-07):
 * - Excel(POI XSSF):六件套各一 sheet(收租台账/利润表/现金流水/往来/资产快照/分配表🔒);
 * - Word(POI XWPF):第七件《月度经营分析报告》,AI 综述 + 七节标题/叙述/数据表(带溯源)。
 *
 * 只负责渲染,不做任何计算(数字来自 MonthlyReportService / MonthlyAnalysisService)。
 * 分配表 sheet 按角色可见:不可见时输出一句屏蔽说明(与聚合服务口径一致)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonthlyExportService {

    private final MonthlyReportService monthlyReportService;
    private final MonthlyAnalysisService monthlyAnalysisService;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ============================== Excel ==============================

    public byte[] exportExcel(String period) {
        MonthlyDtos.PackageResp pkg = monthlyReportService.buildPackage(period);
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            CellStyle head = headStyle(wb);
            CellStyle title = titleStyle(wb);

            sheetRentLedger(wb, head, title, pkg);
            sheetProfit(wb, head, title, pkg);
            sheetCashflow(wb, head, title, pkg);
            sheetDue(wb, head, title, pkg);
            sheetAsset(wb, head, title, pkg);
            sheetDistribution(wb, head, title, pkg);

            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "月度报表包 Excel 导出失败:" + e.getMessage());
        }
    }

    private void sheetRentLedger(Workbook wb, CellStyle head, CellStyle title, MonthlyDtos.PackageResp pkg) {
        Sheet sh = wb.createSheet("①收租台账");
        MonthlyDtos.RentLedger led = pkg.getRentLedger();
        int r = titleRow(sh, 0, title, "收租台账 · " + pkg.getPeriod());
        r = headRow(sh, r, head, new String[]{"收租单号", "合同", "期次", "到期日", "应收(元)", "已收(元)", "状态", "类型", "逾期"});
        for (MonthlyDtos.RentLedgerRow row : led.getRows()) {
            Row rr = sh.createRow(r++);
            int c = 0;
            cell(rr, c++, row.getBillNo());
            cell(rr, c++, row.getContractNo());
            num(rr, c++, row.getPeriodNo() == null ? null : BigDecimal.valueOf(row.getPeriodNo()));
            cell(rr, c++, row.getDueDate() == null ? "" : row.getDueDate().toString());
            num(rr, c++, row.getAmount());
            num(rr, c++, row.getReceivedAmount());
            cell(rr, c++, row.getStatus());
            cell(rr, c++, row.getBillKind());
            cell(rr, c, row.isOverdue() ? "逾期" : "");
        }
        Row tr = sh.createRow(r);
        cell(tr, 0, "合计");
        num(tr, 4, led.getTotalReceivable());
        num(tr, 5, led.getTotalReceived());
        cell(tr, 6, "回款率 " + led.getCollectionRate() + "%");
        autosize(sh, 9);
    }

    private void sheetProfit(Workbook wb, CellStyle head, CellStyle title, MonthlyDtos.PackageResp pkg) {
        Sheet sh = wb.createSheet("②利润表");
        MonthlyDtos.ProfitStatement p = pkg.getProfit();
        int r = titleRow(sh, 0, title, "利润表(经营账 ops)· " + pkg.getPeriod() + (p.isLocked() ? "(已锁账)" : ""));
        r = headRow(sh, r, head, new String[]{"项目", "金额(对经营利润贡献·元)", "口径说明"});
        for (MonthlyDtos.PlRow row : p.getRows()) {
            Row rr = sh.createRow(r++);
            cell(rr, 0, row.isSubtotal() ? "【" + row.getLabel() + "】" : row.getLabel());
            num(rr, 1, row.getAmount());
            cell(rr, 2, row.getNote());
        }
        autosize(sh, 3);
    }

    private void sheetCashflow(Workbook wb, CellStyle head, CellStyle title, MonthlyDtos.PackageResp pkg) {
        Sheet sh = wb.createSheet("③现金流水");
        MonthlyDtos.CashflowSheet cf = pkg.getCashflow();
        int r = titleRow(sh, 0, title, "现金流水(银行存款)· " + pkg.getPeriod());
        r = headRow(sh, r, head, new String[]{"项目", "金额(元)"});
        r = kv(sh, r, "本期现金流入(dr 银行存款)", cf.getInflow());
        r = kv(sh, r, "本期现金流出(cr 银行存款)", cf.getOutflow());
        r = kv(sh, r, "现金净流入", cf.getNet());
        r = kv(sh, r, "净头寸(应收−应付)", cf.getNetPosition());
        r = kv(sh, r, "层① 自有资金", cf.getOwnCapital());
        r = kv(sh, r, "层② 供应商账期(无息)", cf.getSupplierCredit());
        kv(sh, r, "层③ 融资", cf.getFinancing());
        autosize(sh, 2);
    }

    private void sheetDue(Workbook wb, CellStyle head, CellStyle title, MonthlyDtos.PackageResp pkg) {
        Sheet sh = wb.createSheet("④往来");
        MonthlyDtos.DueSheet du = pkg.getDueSheet();
        int r = titleRow(sh, 0, title, "往来表 · 应收未收 + 应付待付(截至 " + du.getAsOf() + ")");
        r = headRow(sh, r, head, new String[]{"类别", "1年内(元)", "1年以上(元)", "合计(元)", "笔数"});
        Row r1 = sh.createRow(r++);
        cell(r1, 0, "应收未收");
        num(r1, 1, du.getReceivableWithin1Y());
        num(r1, 2, du.getReceivableBeyond1Y());
        num(r1, 3, du.getReceivableTotal());
        num(r1, 4, BigDecimal.valueOf(du.getReceivableCount()));
        Row r2 = sh.createRow(r++);
        cell(r2, 0, "应付待付");
        num(r2, 1, du.getPayableWithin1Y());
        num(r2, 2, du.getPayableBeyond1Y());
        num(r2, 3, du.getPayableTotal());
        num(r2, 4, BigDecimal.valueOf(du.getPayableCount()));
        r++;
        subTitle(sh, r++, "应付待付明细");
        r = headRow(sh, r, head, new String[]{"应付ID", "采购入库ID", "阶段", "到期日", "金额(元)", "逾期"});
        for (MonthlyDtos.PayableRow pr : du.getPayables()) {
            Row rr = sh.createRow(r++);
            int c = 0;
            num(rr, c++, pr.getPayableId() == null ? null : BigDecimal.valueOf(pr.getPayableId()));
            num(rr, c++, pr.getPurchaseInId() == null ? null : BigDecimal.valueOf(pr.getPurchaseInId()));
            cell(rr, c++, pr.getStage());
            cell(rr, c++, pr.getDueDate() == null ? "" : pr.getDueDate().toString());
            num(rr, c++, pr.getAmount());
            cell(rr, c, pr.isOverdue() ? "逾期" : "");
        }
        autosize(sh, 6);
    }

    private void sheetAsset(Workbook wb, CellStyle head, CellStyle title, MonthlyDtos.PackageResp pkg) {
        Sheet sh = wb.createSheet("⑤资产快照");
        MonthlyDtos.AssetSnapshot a = pkg.getAsset();
        int r = titleRow(sh, 0, title, "资产快照 · " + pkg.getPeriod());
        r = headRow(sh, r, head, new String[]{"项目", "值"});
        r = kv(sh, r, "在册设备(台)", BigDecimal.valueOf(a.getTotal()));
        r = kv(sh, r, "在租(台)", BigDecimal.valueOf(a.getRentedCount()));
        r = kv(sh, r, "闲置(台)", BigDecimal.valueOf(a.getIdleCount()));
        r = kv(sh, r, "待处置(台)", BigDecimal.valueOf(a.getPendingDisposal()));
        r = kv(sh, r, "在租率(%)", a.getRentedRatio());
        r = kv(sh, r, "市场价家底(元)", a.getMarketPriceTotal());
        r = kv(sh, r, "账面净值(元)", a.getBookValueTotal());
        r++;
        subTitle(sh, r++, "按状态计数");
        r = headRow(sh, r, head, new String[]{"状态", "台数"});
        for (MonthlyDtos.StatusCount sc : a.getByStatus()) {
            Row rr = sh.createRow(r++);
            cell(rr, 0, sc.getStatus());
            num(rr, 1, BigDecimal.valueOf(sc.getCount()));
        }
        autosize(sh, 2);
    }

    private void sheetDistribution(Workbook wb, CellStyle head, CellStyle title, MonthlyDtos.PackageResp pkg) {
        Sheet sh = wb.createSheet("⑥分配表");
        int r = titleRow(sh, 0, title, "分配表(🔒 按角色可见)· " + pkg.getPeriod());
        if (!pkg.isDistributionVisible() || pkg.getDistribution() == null) {
            Row rr = sh.createRow(r);
            cell(rr, 0, pkg.getDistributionMaskNote() == null ? "分配表按角色隐藏" : pkg.getDistributionMaskNote());
            autosize(sh, 1);
            return;
        }
        MonthlyDtos.DistributionSheet d = pkg.getDistribution();
        if (!d.isPresent()) {
            cell(sh.createRow(r), 0, d.getScopeNote());
            autosize(sh, 1);
            return;
        }
        r = kv(sh, r, "可分配利润(元)", d.getDistributable());
        r = kv(sh, r, "管理费(元)", d.getMgmtFee());
        r = kv(sh, r, "现金分配 50%(元)", d.getCash50());
        r = kv(sh, r, "滚存 50%(元)", d.getRoll50());
        r = kv(sh, r, "留存后(元)", d.getReserveAfter());
        r++;
        subTitle(sh, r++, "每人份额(" + d.getScopeNote() + ")");
        r = headRow(sh, r, head, new String[]{"出资人", "角色", "比例", "现金份额", "滚存份额", "管理费", "本期收益", "本人"});
        for (MonthlyDtos.ShareRow s : d.getShares()) {
            Row rr = sh.createRow(r++);
            int c = 0;
            cell(rr, c++, s.getName());
            cell(rr, c++, s.getRole());
            num(rr, c++, s.getRatio());
            num(rr, c++, s.getCashShare());
            num(rr, c++, s.getRollShare());
            num(rr, c++, s.getMgmtFee());
            num(rr, c++, s.getTotalGain());
            cell(rr, c, s.isSelf() ? "★" : "");
        }
        autosize(sh, 8);
    }

    // ============================== Word(第七件)==============================

    public byte[] exportWord(String period) {
        MonthlyDtos.AnalysisReport report = monthlyAnalysisService.analyze(period, false);
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            XWPFParagraph tp = doc.createParagraph();
            tp.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun tr = tp.createRun();
            tr.setBold(true);
            tr.setFontSize(20);
            tr.setText(report.getPeriod() + " 月度经营分析报告");

            XWPFParagraph meta = doc.createParagraph();
            meta.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun mr = meta.createRun();
            mr.setFontSize(9);
            mr.setColor("888888");
            mr.setText("系统自动生成 · " + LocalDateTime.now().format(TS)
                    + " · AI 起草模型:" + report.getModel()
                    + (report.isMock() ? " · [MOCK 占位]" : ""));

            heading(doc, "AI 综述(可改两句即交)");
            para(doc, report.getAiText());

            for (MonthlyDtos.AnalysisSection s : report.getSections()) {
                heading(doc, s.getTitle());
                para(doc, s.getNarrative());
                if (!s.getDataPoints().isEmpty()) {
                    dataTable(doc, s.getDataPoints());
                }
            }
            doc.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "月度经营分析报告 Word 导出失败:" + e.getMessage());
        }
    }

    private void heading(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(180);
        XWPFRun r = p.createRun();
        r.setBold(true);
        r.setFontSize(14);
        r.setText(text);
    }

    private void para(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setFontSize(11);
        r.setText(text == null ? "" : text);
    }

    private void dataTable(XWPFDocument doc, List<MonthlyDtos.DataPoint> points) {
        XWPFTable table = doc.createTable(points.size() + 1, 3);
        table.setWidth("100%");
        XWPFTableRow h = table.getRow(0);
        setCell(h.getCell(0), "指标", true);
        setCell(h.getCell(1), "数值", true);
        setCell(h.getCell(2), "数据溯源", true);
        int i = 1;
        for (MonthlyDtos.DataPoint dp : points) {
            XWPFTableRow row = table.getRow(i++);
            setCell(row.getCell(0), dp.getLabel(), false);
            setCell(row.getCell(1), dp.getValue(), false);
            setCell(row.getCell(2), dp.getSource(), false);
        }
    }

    private void setCell(org.apache.poi.xwpf.usermodel.XWPFTableCell cell, String text, boolean boldHead) {
        cell.removeParagraph(0);
        XWPFParagraph p = cell.addParagraph();
        XWPFRun r = p.createRun();
        r.setFontSize(10);
        r.setBold(boldHead);
        if (boldHead) {
            cell.setColor("EFEBDD");
        }
        r.setText(text == null ? "" : text);
    }

    // ============================== POI 小工具 ==============================

    private CellStyle headStyle(Workbook wb) {
        CellStyle st = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        st.setFont(f);
        return st;
    }

    private CellStyle titleStyle(Workbook wb) {
        CellStyle st = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setFontHeightInPoints((short) 13);
        st.setFont(f);
        return st;
    }

    private int titleRow(Sheet sh, int r, CellStyle style, String text) {
        Row row = sh.createRow(r++);
        Cell c = row.createCell(0);
        c.setCellValue(text);
        c.setCellStyle(style);
        return r;
    }

    private void subTitle(Sheet sh, int r, String text) {
        sh.createRow(r).createCell(0).setCellValue("— " + text + " —");
    }

    private int headRow(Sheet sh, int r, CellStyle style, String[] cols) {
        Row row = sh.createRow(r++);
        for (int i = 0; i < cols.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(cols[i]);
            c.setCellStyle(style);
        }
        return r;
    }

    private int kv(Sheet sh, int r, String k, BigDecimal v) {
        Row row = sh.createRow(r++);
        cell(row, 0, k);
        num(row, 1, v);
        return r;
    }

    private void cell(Row row, int c, String v) {
        row.createCell(c).setCellValue(v == null ? "" : v);
    }

    private void num(Row row, int c, BigDecimal v) {
        Cell cell = row.createCell(c);
        if (v != null) {
            cell.setCellValue(v.doubleValue());
        }
    }

    /** 固定列宽(不用 autoSizeColumn:headless 无字体环境会依赖 AWT)。 */
    private void autosize(Sheet sh, int cols) {
        for (int i = 0; i < cols; i++) {
            sh.setColumnWidth(i, i <= 1 ? 5600 : 3600);
        }
    }
}
