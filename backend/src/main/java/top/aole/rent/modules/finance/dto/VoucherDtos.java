package top.aole.rent.modules.finance.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 凭证/折旧/500万红线模块 DTO 汇总(M3 Wave A)。
 */
public class VoucherDtos {

    // ---------- 凭证列表/详情 ----------

    @Data
    public static class VoucherItem {
        private Long id;
        private String voucherNo;
        private String sourceDocType;
        private Long sourceDocId;
        private String book;         // tax/ops
        private String period;
        private LocalDate bizDate;
        private BigDecimal totalAmount;
        private String entryType;
        private String summary;
        private Boolean isReversal;
        private Long reversesId;
        private Boolean balanced;    // Σdr=Σcr 校验(展示用)
        private LocalDateTime createTime;
    }

    @Data
    public static class LineItem {
        private Long id;
        private String accountCode;
        private String accountName;
        private String direction;    // dr/cr
        private BigDecimal amount;
        private String remark;
    }

    @Data
    public static class VoucherDetail {
        private VoucherItem voucher;
        private List<LineItem> lines;
        private BigDecimal debitTotal;
        private BigDecimal creditTotal;
        private boolean balanced;
        /** 同源单据的对家账套凭证(双账口径对照:tax↔ops) */
        private List<VoucherItem> siblingBooks;
        /** 若本凭证已被红冲 */
        private Long reversedByVoucherId;
    }

    // ---------- 红冲(P0-F) ----------

    @Data
    public static class ReverseRequest {
        private String reason;
    }

    @Data
    public static class VoucherReverseImpact {
        private Long originalVoucherId;
        private String originalVoucherNo;
        private Long reversalVoucherId;
        private String reversalVoucherNo;
        private String book;
        private String period;
        private BigDecimal amount;
        private List<String> items;   // 逐条影响清单
    }

    // ---------- 收租核销回填凭证(消稽核 matchedNoVoucher) ----------

    @Data
    public static class BackfillResult {
        private int scanned;          // 已核销缺凭证单数
        private int posted;           // 生成凭证套数(每套 tax+ops 两张)
        private int voucherCount;     // 生成凭证张数
        private List<String> details;
    }

    // ---------- 折旧计提(cron) ----------

    @Data
    public static class DepreciationRunResult {
        private String period;        // 计提期 YYYY-MM
        private int assetsScanned;    // 参与计提设备数
        private int linesGenerated;   // 新生成折旧行数
        private int skipped;          // 已计提/终态/无投放跳过
        private int vouchersPosted;   // 折旧凭证数(ops)
        private BigDecimal totalDepr; // 本期折旧总额
        private List<String> details;
    }

    @Data
    public static class DepreciationLineItem {
        private Long id;
        private Long assetId;
        private String serialNo;
        private Integer periodNo;
        private String period;
        private BigDecimal deprAmount;
        private BigDecimal bookValueAfter;
        private Long voucherId;
        private LocalDate bizDate;
    }

    // ---------- 500万营收红线 ----------

    @Data
    public static class TaxThreshold {
        private int year;
        private String book;              // 恒 tax(营收红线取税务账)
        private BigDecimal threshold;     // 500万
        private BigDecimal warnRatio;     // 预警占比 0.8
        private BigDecimal currentRevenue;// 本年 tax 账套已确认收入(净红冲)
        private BigDecimal remaining;     // 剩余额度
        private BigDecimal usedRatio;     // 已用占比
        private String level;             // 正常/预警/超限
        private BigDecimal opsRevenue;    // 对照:经营账收入(ops≠tax 佐证)
        private List<MonthRevenue> byMonth;
    }

    @Data
    public static class MonthRevenue {
        private String period;
        private BigDecimal taxRevenue;
        private BigDecimal opsRevenue;
    }
}
