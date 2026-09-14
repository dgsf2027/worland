package top.aole.rent.modules.customer.service;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.customer.dto.CustomerPoolItem;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 客户信息一键导出(Excel)。复用客户池查询,<b>行级/字段级隔离与页面一致</b>:
 * 业务只导出自己名下+公海;投资人角色的敏感金额列导出为「无权限」。
 */
@Service
@RequiredArgsConstructor
public class CustomerExportService {

    private final CustomerService customerService;

    private static final String[] HEADERS = {
            "公司名称", "法人", "注册资本(万元)", "业务范围", "主要联系人", "联系方式", "行业",
            "阶段", "负责业务", "评级", "可在租合同(份)", "合同总数(份)", "在租设备(台)",
            "在租/商机额(元)", "逾期应收(元)", "下次跟进"
    };
    /** 列宽(字符数),与 HEADERS 一一对应 */
    private static final int[] COL_WIDTHS = {30, 10, 14, 18, 12, 16, 10, 8, 10, 8, 14, 12, 12, 16, 14, 12};

    public byte[] exportExcel(String phase, String rating, Long owner, String keyword) {
        List<CustomerPoolItem> rows = customerService
                .pool(phase, rating, owner, keyword, 1, Integer.MAX_VALUE).getRecords();
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("客户信息");
            CellStyle head = headStyle(wb);
            Row hr = sh.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(HEADERS[i]);
                c.setCellStyle(head);
            }
            int r = 1;
            for (CustomerPoolItem it : rows) {
                Row row = sh.createRow(r++);
                int c = 0;
                text(row, c++, it.getName());
                text(row, c++, it.getLegalPerson());
                num(row, c++, it.getRegisteredCapital() == null ? null
                        : it.getRegisteredCapital().divide(BigDecimal.valueOf(10000), 2, RoundingMode.HALF_UP));
                text(row, c++, String.join("/", it.getBusinessScope()));
                text(row, c++, it.getContact());
                text(row, c++, it.getPhone());
                text(row, c++, it.getIndustry());
                text(row, c++, it.getPhase());
                text(row, c++, it.getOwnerName());
                text(row, c++, it.getRating() == null ? null
                        : (Boolean.TRUE.equals(it.getRatingPredicted()) ? "预" : "") + it.getRating());
                num(row, c++, BigDecimal.valueOf(it.getActiveContractCount()));
                num(row, c++, BigDecimal.valueOf(it.getContractTotal()));
                num(row, c++, BigDecimal.valueOf(it.getActiveAssetCount()));
                sensitive(row, c++, it.getExposureOrOppAmount(), it.getSensitiveMasked());
                sensitive(row, c++, it.getReceivableOverdue(), it.getSensitiveMasked());
                text(row, c, it.getNextFollowDate() == null ? null : it.getNextFollowDate().toString());
            }
            sh.createFreezePane(1, 1);
            // 固定列宽(不用 autoSizeColumn:服务器 jre 镜像缺字体时会抛异常,与月报导出同口径)
            for (int i = 0; i < HEADERS.length; i++) {
                sh.setColumnWidth(i, COL_WIDTHS[i] * 256);
            }
            wb.write(bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new BizException(500, "客户信息导出失败:" + e.getMessage());
        }
    }

    private void text(Row row, int col, String v) {
        row.createCell(col).setCellValue(v == null ? "" : v);
    }

    private void num(Row row, int col, BigDecimal v) {
        Cell c = row.createCell(col);
        if (v != null) {
            c.setCellValue(v.doubleValue());
        }
    }

    private void sensitive(Row row, int col, BigDecimal v, Boolean masked) {
        if (Boolean.TRUE.equals(masked)) {
            text(row, col, "无权限");
        } else {
            num(row, col, v);
        }
    }

    private CellStyle headStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        return s;
    }
}
