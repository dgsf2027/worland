package top.aole.rent.modules.analytics.service;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.analytics.dto.CashflowDtos;
import top.aole.rent.modules.distribution.dto.DistributionDtos;
import top.aole.rent.modules.distribution.service.DistributionService;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 现金流/分配驾驶舱 → Excel(只导出,不导入)。
 *
 * <p>六张表:「概览」页面上那几个数的快照、「现金流预测」未来 N 月曲线、「兑付缺口」逐笔应付 checkpoint、
 * 「结账分配」分配单台账、「出资人名册」GP/LP 出资与比例、「回报四源」IRR 拆解与勾稽。
 *
 * <p><b>为什么没有导入</b>:驾驶舱上的数全是算出来的 —— 应收取 rent_schedule、应付取 payable、
 * 可动用留存取历史分配的 reserve_after,分配单只能由「运行结账分配」按 rule_config 的阶梯生成。
 * 从表格灌进来的数会和系统算出来的打架,红线预警就不可信了,所以这里只给导出。
 */
@Service
@RequiredArgsConstructor
public class CashflowExcelService {

    private final CashflowService cashflowService;
    private final CoverageGapService coverageGapService;
    private final ReturnAttributionService returnAttributionService;
    private final DistributionService distributionService;

    private static final String[] FORECAST_HEADERS = {
            "记账期", "预计流入(元)", "预计流出(元)", "当月净额(元)", "累计净额(元)"
    };
    private static final int[] FORECAST_WIDTHS = {12, 16, 16, 16, 16};

    private static final String[] GAP_HEADERS = {
            "预警", "阶段", "到期日", "剩余天", "应付到期(元)", "累计回款(元)", "累计应付(元)",
            "预计现金(元)", "缺口(元)", "裁决人", "补款来源", "应付id", "采购单id"
    };
    private static final int[] GAP_WIDTHS = {8, 10, 12, 10, 16, 16, 16, 16, 14, 30, 60, 10, 11};

    private static final String[] DIST_HEADERS = {
            "分配单号", "期", "分配日", "出资合计(元)", "提取前净利(元)", "回报率", "管理费率", "管理费(元)",
            "可分配(元)", "现金50%(元)", "滚存50%(元)", "留存下限(元)", "留存后(元)", "留存达标",
            "状态", "类型", "冲销指向原分配id"
    };
    private static final int[] DIST_WIDTHS = {18, 10, 12, 16, 18, 10, 10, 14, 14, 14, 14, 14, 14, 10, 10, 8, 18};

    private static final String[] INVESTOR_HEADERS = {"出资人", "角色", "出资额(元)", "出资比例"};
    private static final int[] INVESTOR_WIDTHS = {20, 8, 16, 12};

    private static final String[] ATTR_HEADERS = {"来源", "权重", "贡献IRR", "数据来源"};
    private static final int[] ATTR_WIDTHS = {18, 12, 12, 60};

    /**
     * 导出驾驶舱工作簿。参数与页面上那三个控件同义,全部可空取各自默认。
     *
     * @param months      现金流预测未来 N 月(默认 12)
     * @param tMinusDays  兑付缺口提前预警天数(默认 7)
     * @param customerType 回报四源的目标客户类型(默认「其他」)
     */
    public byte[] export(Integer months, Integer tMinusDays, String customerType) {
        CashflowDtos.Cashflow cf = cashflowService.cashflow(months);
        CashflowDtos.CoverageGapReport gap = coverageGapService.coverageGap(tMinusDays);
        CashflowDtos.ReturnAttribution attr = returnAttributionService.attribution(null, customerType);
        List<DistributionDtos.DistributionItem> dists = distributionService.list(null, null);
        List<DistributionDtos.InvestorItem> investors = distributionService.investors();

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            CellStyle head = headStyle(wb);

            // ---- 1. 概览 ----
            Sheet s0 = wb.createSheet("概览");
            s0.setColumnWidth(0, 28 * 256);
            s0.setColumnWidth(1, 20 * 256);
            s0.setColumnWidth(2, 46 * 256);
            int r = 0;
            r = kv(s0, r, head, "数据时点", cf.getAsOf() == null ? "" : cf.getAsOf().toString(), null);
            r = kvMoney(s0, r, head, "应收合计(未收·rent_schedule)", cf.getReceivable().getTotal(),
                    "1年内 " + plain(cf.getReceivable().getWithin1Year())
                            + " · 1年以上 " + plain(cf.getReceivable().getBeyond1Year())
                            + " · " + cf.getReceivable().getCount() + " 笔");
            r = kvMoney(s0, r, head, "应付合计(待付·payable)", cf.getPayable().getTotal(),
                    "1年内 " + plain(cf.getPayable().getWithin1Year())
                            + " · 1年以上 " + plain(cf.getPayable().getBeyond1Year())
                            + " · " + cf.getPayable().getCount() + " 笔");
            r = kvMoney(s0, r, head, "净头寸(应收−应付)", cf.getNetPosition(), "粗口径·精确以兑付缺口现金头寸为准");
            r = kvMoney(s0, r, head, "杠杆①自有资金", cf.getLeverage().getOwnCapital(), null);
            r = kvMoney(s0, r, head, "杠杆②供应商账期", cf.getLeverage().getSupplierCredit(), null);
            r = kvMoney(s0, r, head, "杠杆③融资", cf.getLeverage().getFinancing(), null);
            r = kvMoney(s0, r, head, "三层杠杆合计", cf.getLeverage().getTotal(), cf.getLeverage().getNote());
            r = kvMoney(s0, r, head, "可动用留存", gap.getUsableReserve(), "累计分配留存 − 留存下限");
            r = kv(s0, r, head, "兑付缺口预警", gap.isHasRedAlert() ? "🔴 " + gap.getRedCount() + " 笔缺口" : "🟢 兑付安全",
                    "T-" + gap.getTMinusDays() + " 天");
            r = kv(s0, r, head, "总税后 IRR", pct(attr.getTotalIrr()), "目标客户类型:" + nvl(attr.getCustomerType()));
            r = kv(s0, r, head, "四源之和(勾稽)", pct(attr.getSumCheck()),
                    attr.isReconciled() ? "✓ 与总IRR相等" : "✗ 不平");
            kv(s0, r, head, "分配单数", String.valueOf(dists.size()), "出资人 " + investors.size() + " 位");

            // ---- 2. 现金流预测 ----
            Sheet s1 = wb.createSheet("现金流预测");
            header(s1, head, FORECAST_HEADERS, FORECAST_WIDTHS);
            r = 1;
            if (cf.getForecast() != null) {
                for (CashflowDtos.MonthFlow f : cf.getForecast()) {
                    Row row = s1.createRow(r++);
                    text(row, 0, f.getPeriod());
                    money(row, 1, f.getInflow());
                    money(row, 2, f.getOutflow());
                    money(row, 3, f.getNet());
                    money(row, 4, f.getCumulative());
                }
            }
            s1.createFreezePane(1, 1);

            // ---- 3. 兑付缺口 ----
            Sheet s2 = wb.createSheet("兑付缺口");
            header(s2, head, GAP_HEADERS, GAP_WIDTHS);
            r = 1;
            if (gap.getItems() != null) {
                for (CashflowDtos.GapItem it : gap.getItems()) {
                    Row row = s2.createRow(r++);
                    int c = 0;
                    text(row, c++, it.isRed() ? "红灯" : "充足");
                    text(row, c++, it.getStage());
                    text(row, c++, it.getDueDate() == null ? null : it.getDueDate().toString());
                    num(row, c++, it.getDaysToDue());
                    money(row, c++, it.getPayableAmount());
                    money(row, c++, it.getCumInflow());
                    money(row, c++, it.getCumOutflow());
                    money(row, c++, it.getProjectedCash());
                    money(row, c++, it.getGap());
                    text(row, c++, it.getArbiter());
                    text(row, c++, it.getFundingSources() == null ? null : String.join(" / ", it.getFundingSources()));
                    num(row, c++, it.getPayableId());
                    num(row, c, it.getPurchaseInId());
                }
            }
            s2.createFreezePane(2, 1);

            // ---- 4. 结账分配 ----
            Sheet s3 = wb.createSheet("结账分配");
            header(s3, head, DIST_HEADERS, DIST_WIDTHS);
            r = 1;
            for (DistributionDtos.DistributionItem d : dists) {
                Row row = s3.createRow(r++);
                int c = 0;
                text(row, c++, d.getDistributionNo());
                text(row, c++, d.getPeriod());
                text(row, c++, d.getBizDate() == null ? null : d.getBizDate().toString());
                money(row, c++, d.getTotalCapital());
                money(row, c++, d.getProfitBefore());
                text(row, c++, pct(d.getReturnRate()));
                text(row, c++, pct(d.getMgmtFeeRate()));
                money(row, c++, d.getMgmtFee());
                money(row, c++, d.getDistributable());
                money(row, c++, d.getCash50());
                money(row, c++, d.getRoll50());
                money(row, c++, d.getReserveFloor());
                money(row, c++, d.getReserveAfter());
                text(row, c++, Boolean.TRUE.equals(d.getReserveSufficient()) ? "达标" : "不足");
                text(row, c++, "active".equals(d.getStatus()) ? "生效" : "已冲销");
                text(row, c++, Boolean.TRUE.equals(d.getIsReversal()) ? "冲销" : "原始");
                num(row, c, d.getReversesId());
            }
            s3.createFreezePane(1, 1);

            // ---- 5. 出资人名册 ----
            Sheet s4 = wb.createSheet("出资人名册");
            header(s4, head, INVESTOR_HEADERS, INVESTOR_WIDTHS);
            r = 1;
            for (DistributionDtos.InvestorItem iv : investors) {
                Row row = s4.createRow(r++);
                text(row, 0, iv.getName());
                text(row, 1, iv.getRole());
                money(row, 2, iv.getAmount());
                text(row, 3, pct(iv.getRatio()));
            }
            s4.createFreezePane(1, 1);

            // ---- 6. 回报四源 ----
            Sheet s5 = wb.createSheet("回报四源");
            header(s5, head, ATTR_HEADERS, ATTR_WIDTHS);
            r = 1;
            if (attr.getSources() != null) {
                for (CashflowDtos.AttributionSource src : attr.getSources()) {
                    Row row = s5.createRow(r++);
                    text(row, 0, src.getLabel());
                    text(row, 1, pct(src.getWeight()));
                    text(row, 2, pct(src.getIrrContribution()));
                    text(row, 3, src.getSource());
                }
            }
            Row sum = s5.createRow(r + 1);
            Cell sumK = sum.createCell(0);
            sumK.setCellValue("四源之和 / 总IRR");
            sumK.setCellStyle(head);
            sum.createCell(2).setCellValue(pct(attr.getSumCheck()) + " / " + pct(attr.getTotalIrr()));
            sum.createCell(3).setCellValue(attr.isReconciled() ? "✓ 勾稽平" : "✗ 不平");

            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "驾驶舱导出失败:" + e.getMessage());
        }
    }

    public String fileName() {
        return "现金流分配驾驶舱-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
    }

    // ============================== 单元格 ==============================

    private static String nvl(String v) {
        return v == null ? "" : v;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    private static String pct(BigDecimal v) {
        return v == null ? "" : v.multiply(BigDecimal.valueOf(100))
                .setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%";
    }

    private int kv(Sheet sh, int r, CellStyle head, String k, String v, String note) {
        Row row = sh.createRow(r);
        Cell kc = row.createCell(0);
        kc.setCellValue(k);
        kc.setCellStyle(head);
        row.createCell(1).setCellValue(v == null ? "" : v);
        if (note != null) {
            row.createCell(2).setCellValue(note);
        }
        return r + 1;
    }

    private int kvMoney(Sheet sh, int r, CellStyle head, String k, BigDecimal v, String note) {
        Row row = sh.createRow(r);
        Cell kc = row.createCell(0);
        kc.setCellValue(k);
        kc.setCellStyle(head);
        if (v != null) {
            row.createCell(1).setCellValue(v.doubleValue());
        }
        if (note != null) {
            row.createCell(2).setCellValue(note);
        }
        return r + 1;
    }

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

    private void num(Row row, int col, Long v) {
        Cell c = row.createCell(col);
        if (v != null) {
            c.setCellValue(v);
        }
    }

    private void num(Row row, int col, long v) {
        row.createCell(col).setCellValue(v);
    }

    private void money(Row row, int col, BigDecimal v) {
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
