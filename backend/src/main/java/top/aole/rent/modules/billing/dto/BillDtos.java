package top.aole.rent.modules.billing.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 收租/逾期模块 DTO 汇总(M2)。请求体与返回体集中一处,便于前端对齐。
 */
public class BillDtos {

    // ---------- 收租单列表/详情 ----------

    @Data
    public static class BillItem {
        private Long id;
        private String billNo;
        private Long contractId;
        private String contractNo;
        private String customerName;
        private Integer periodNo;
        private LocalDate dueDate;
        private BigDecimal amount;
        private BigDecimal receivedAmount;
        private String status;
        private String billKind;
        private LocalDateTime matchedAt;
        private String accountPeriod;
        private Long reversesId;
        private Long voucherId;
        private Boolean overdue;      // 计算:待收且已过到期日
        private Integer overdueDays;  // 逾期天数
        private String remark;
    }

    @Data
    public static class BillDetail {
        private BillItem bill;
        private List<BillItem> related;   // 红冲/退款/罚息 关联单
        private Long overdueCaseId;       // 若已开逾期案
    }

    // ---------- 请求体 ----------

    /** 到账核销:receivedAmount 缺省=应收全额;不一致人工处理。 */
    @Data
    public static class MatchRequest {
        private BigDecimal receivedAmount;
        private String remark;
    }

    @Data
    public static class BatchMatchRequest {
        private List<Long> ids;
    }

    @Data
    public static class ReverseRequest {
        private String reason;
    }

    @Data
    public static class RefundRequest {
        private BigDecimal amount;   // 缺省=原核销单已收额
        private String reason;
    }

    // ---------- 红冲影响清单(P0-F) ----------

    @Data
    public static class ReverseImpact {
        private Long originalBillId;
        private Long reversalBillId;
        private BigDecimal originalAmount;
        private BigDecimal reversalAmount;   // 负数
        private String accountPeriod;
        private List<String> items;          // 逐条影响说明
    }

    // ---------- 生成结果(cron) ----------

    @Data
    public static class GenResult {
        private int generated;
        private int skipped;
        private LocalDate horizon;   // 生成窗口截止日(today + lead)
        private List<String> bills;  // 新生成单号
    }

    // ---------- 钱该动没动稽核(M2-04) ----------

    @Data
    public static class CashCheck {
        private int billNotGenerated;    // 到期未生成收租单
        private int receivedNotMatched;  // 已到账未核销
        private int matchedNoVoucher;    // 已核销缺凭证
        private List<String> billNotGeneratedDetail;
        private List<String> receivedNotMatchedDetail;
        private List<String> matchedNoVoucherDetail;
        private boolean allClear;
    }

    // ---------- 逾期案 ----------

    @Data
    public static class OverdueItem {
        private Long id;
        private Long rentBillId;
        private String billNo;
        private Long contractId;
        private String contractNo;
        private String customerName;
        private String step;
        private String status;
        private BigDecimal penaltyAmount;
        private String nextAction;
        private LocalDate deadline;
        private String owner;
        private LocalDateTime openedAt;
        private LocalDateTime closedAt;
        private String remark;
    }

    /** 逾期处置(延期/罚息/锁机/收回)通用入参:必带裁决人 owner + 期限 deadline。 */
    @Data
    public static class OverdueActionRequest {
        private String owner;      // 裁决人(必填)
        private LocalDate deadline; // 期限
        private Integer days;      // 延期天数 / 罚息计算逾期天数(缺省=实际逾期天数)
        private String reason;
    }

    @Data
    public static class OverdueScanResult {
        private int opened;
        private int marked;        // 收租单标逾期数
        private List<String> cases;
    }

    @Data
    public static class RepaymentResult {
        private Long caseId;
        private String caseStatus;
        private Long matchedBillId;
        private boolean assetsRestored;
    }
}
