package top.aole.rent.modules.finance.service;

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
import top.aole.rent.modules.finance.dto.VoucherDtos;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 凭证中心 · 双账/折旧 → Excel(只导出,不导入)。
 *
 * <p>四张表:「凭证」单头、「凭证分录」借贷行(按凭证号关联单头)、「折旧明细」逐台逐期、
 * 「营收红线」500万口径与按月收入对照。
 *
 * <p><b>为什么没有导入</b>:凭证是业财一体的产物 —— 收租核销/采购应付/折旧计提/转让处置
 * 各自在同一事务里生成凭证与 ledger_book 流水,凭证是账的结果而不是账的入口。
 * 从表格灌凭证会让凭证和它的来源单据对不上,也绕开红冲留痕,所以这里只给导出。
 * 导出口径与页面筛选一致(账套/来源单据/记账期/是否红冲),页面上看到的就是导出的。
 */
@Service
@RequiredArgsConstructor
public class VoucherExcelService {

    private final VoucherService voucherService;
    private final DepreciationService depreciationService;
    private final TaxThresholdService taxThresholdService;

    private static final String[] VOUCHER_HEADERS = {
            "凭证号", "账套", "来源单据", "来源单据id", "性质", "记账期", "业务日期",
            "金额(借方合计)", "借贷平衡", "类型", "红冲指向原凭证id", "摘要"
    };
    private static final int[] VOUCHER_WIDTHS = {34, 22, 12, 12, 8, 10, 12, 16, 10, 8, 18, 44};

    private static final String[] LINE_HEADERS = {
            "凭证号", "账套", "记账期", "科目编码", "会计科目", "借方", "贷方", "行摘要"
    };
    private static final int[] LINE_WIDTHS = {34, 22, 10, 10, 30, 14, 14, 30};

    private static final String[] DEPR_HEADERS = {
            "设备序列号", "设备id", "账套", "折旧期次", "记账期", "本期折旧(元)",
            "折旧后账面净值(元)", "业务日期", "折旧凭证id"
    };
    private static final int[] DEPR_WIDTHS = {22, 10, 10, 10, 10, 15, 20, 12, 13};

    private static final Map<String, String> BOOK_LABEL = new HashMap<>();
    private static final Map<String, String> SRC_LABEL = new HashMap<>();

    static {
        BOOK_LABEL.put("tax", "税务账(分期收款销售)");
        BOOK_LABEL.put("ops", "经营账(三层回报)");
        SRC_LABEL.put("rent_bill", "收租");
        SRC_LABEL.put("purchase_in", "采购应付");
        SRC_LABEL.put("depreciation", "折旧");
        SRC_LABEL.put("transfer_line", "转让残值");
        SRC_LABEL.put("manual", "手工");
    }

    /**
     * 导出凭证工作簿。筛选参数与 {@code GET /rent/vouchers} 同义,全部可空 = 不限。
     *
     * @param period 记账期 YYYY-MM;折旧明细与它同期(为空=全部期)
     */
    public byte[] export(String book, String sourceDocType, String period, Boolean isReversal) {
        List<VoucherDtos.VoucherItem> vouchers = voucherService.listAll(book, sourceDocType, period, isReversal);
        List<Long> voucherIds = new ArrayList<>();
        for (VoucherDtos.VoucherItem v : vouchers) {
            voucherIds.add(v.getId());
        }
        Map<Long, List<VoucherDtos.LineItem>> lines = voucherService.linesByVoucher(voucherIds);
        List<VoucherDtos.DepreciationLineItem> deprLines = depreciationService.linesForExport(period);
        VoucherDtos.TaxThreshold th = taxThresholdService.threshold(null);

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            CellStyle head = headStyle(wb);

            // ---- 1. 凭证单头 ----
            Sheet s1 = wb.createSheet("凭证");
            header(s1, head, VOUCHER_HEADERS, VOUCHER_WIDTHS);
            int r = 1;
            for (VoucherDtos.VoucherItem v : vouchers) {
                Row row = s1.createRow(r++);
                int c = 0;
                text(row, c++, v.getVoucherNo());
                text(row, c++, label(BOOK_LABEL, v.getBook()));
                text(row, c++, label(SRC_LABEL, v.getSourceDocType()));
                num(row, c++, v.getSourceDocId());
                text(row, c++, v.getEntryType());
                text(row, c++, v.getPeriod());
                text(row, c++, v.getBizDate() == null ? null : v.getBizDate().toString());
                money(row, c++, v.getTotalAmount());
                text(row, c++, Boolean.TRUE.equals(v.getBalanced()) ? "借=贷" : "不平");
                text(row, c++, Boolean.TRUE.equals(v.getIsReversal()) ? "红冲" : "原始");
                num(row, c++, v.getReversesId());
                text(row, c, v.getSummary());
            }
            s1.createFreezePane(1, 1);

            // ---- 2. 凭证分录 ----
            Sheet s2 = wb.createSheet("凭证分录");
            header(s2, head, LINE_HEADERS, LINE_WIDTHS);
            r = 1;
            for (VoucherDtos.VoucherItem v : vouchers) {
                for (VoucherDtos.LineItem l : lines.getOrDefault(v.getId(), Collections.emptyList())) {
                    Row row = s2.createRow(r++);
                    int c = 0;
                    text(row, c++, v.getVoucherNo());
                    text(row, c++, label(BOOK_LABEL, v.getBook()));
                    text(row, c++, v.getPeriod());
                    text(row, c++, l.getAccountCode());
                    text(row, c++, l.getAccountName());
                    // 借贷分列,方向由落在哪一列表达,合计可直接在表里对平
                    if (VoucherService.DR.equals(l.getDirection())) {
                        money(row, c++, l.getAmount());
                        c++;
                    } else {
                        c++;
                        money(row, c++, l.getAmount());
                    }
                    text(row, c, l.getRemark());
                }
            }
            s2.createFreezePane(1, 1);

            // ---- 3. 折旧明细 ----
            Sheet s3 = wb.createSheet("折旧明细");
            header(s3, head, DEPR_HEADERS, DEPR_WIDTHS);
            r = 1;
            for (VoucherDtos.DepreciationLineItem d : deprLines) {
                Row row = s3.createRow(r++);
                int c = 0;
                text(row, c++, d.getSerialNo());
                num(row, c++, d.getAssetId());
                text(row, c++, "经营账(ops·折旧只落经营账)");
                num(row, c++, d.getPeriodNo() == null ? null : d.getPeriodNo().longValue());
                text(row, c++, d.getPeriod());
                money(row, c++, d.getDeprAmount());
                money(row, c++, d.getBookValueAfter());
                text(row, c++, d.getBizDate() == null ? null : d.getBizDate().toString());
                num(row, c, d.getVoucherId());
            }
            s3.createFreezePane(1, 1);

            // ---- 4. 营收红线 ----
            Sheet s4 = wb.createSheet("营收红线");
            s4.setColumnWidth(0, 24 * 256);
            s4.setColumnWidth(1, 18 * 256);
            s4.setColumnWidth(2, 18 * 256);
            r = 0;
            r = kv(s4, r, head, "年度", String.valueOf(th.getYear()));
            r = kv(s4, r, head, "口径", "税务账(ops≠tax·折旧只落经营账)");
            r = kvMoney(s4, r, head, "阈值", th.getThreshold());
            r = kv(s4, r, head, "预警线", pct(th.getWarnRatio()));
            r = kvMoney(s4, r, head, "已确认(税务账)", th.getCurrentRevenue());
            r = kvMoney(s4, r, head, "剩余额度", th.getRemaining());
            r = kv(s4, r, head, "已用占比", pct(th.getUsedRatio()));
            r = kv(s4, r, head, "等级", th.getLevel());
            r = kvMoney(s4, r, head, "经营账对照", th.getOpsRevenue());
            r++;
            Row mh = s4.createRow(r++);
            String[] monthHeaders = {"月份", "税务账收入(元)", "经营账收入(元)"};
            for (int i = 0; i < monthHeaders.length; i++) {
                Cell c = mh.createCell(i);
                c.setCellValue(monthHeaders[i]);
                c.setCellStyle(head);
            }
            if (th.getByMonth() != null) {
                for (VoucherDtos.MonthRevenue m : th.getByMonth()) {
                    Row row = s4.createRow(r++);
                    text(row, 0, m.getPeriod());
                    money(row, 1, m.getTaxRevenue());
                    money(row, 2, m.getOpsRevenue());
                }
            }

            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "凭证导出失败:" + e.getMessage());
        }
    }

    /** 导出文件名:带筛选条件与日期,便于归档时一眼看出是哪一刀。 */
    public String fileName(String book, String period) {
        StringBuilder sb = new StringBuilder("凭证双账折旧");
        if (book != null && !book.isEmpty()) {
            sb.append('-').append("tax".equals(book) ? "税务账" : "ops".equals(book) ? "经营账" : book);
        }
        if (period != null && !period.isEmpty()) {
            sb.append('-').append(period);
        }
        return sb.append('-').append(LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE))
                .append(".xlsx").toString();
    }

    // ============================== 单元格 ==============================

    private static String label(Map<String, String> m, String key) {
        if (key == null) {
            return null;
        }
        String v = m.get(key);
        return v != null ? v : key;
    }

    private static String pct(BigDecimal v) {
        return v == null ? "" : v.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString() + "%";
    }

    private int kv(Sheet sh, int r, CellStyle head, String k, String v) {
        Row row = sh.createRow(r);
        Cell kc = row.createCell(0);
        kc.setCellValue(k);
        kc.setCellStyle(head);
        row.createCell(1).setCellValue(v == null ? "" : v);
        return r + 1;
    }

    private int kvMoney(Sheet sh, int r, CellStyle head, String k, BigDecimal v) {
        Row row = sh.createRow(r);
        Cell kc = row.createCell(0);
        kc.setCellValue(k);
        kc.setCellStyle(head);
        if (v != null) {
            row.createCell(1).setCellValue(v.doubleValue());
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
