package top.aole.rent.modules.contract.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 合同详情:要点 + 勾稽校验行 + 回款进度 + 租金计划逐期 + 每期租金构成 + 单笔 P&L。
 * 对齐 UI mockup 合同页。敏感财务字段(集采/资金成本/P&L 明细)按角色投影。
 */
@Data
public class ContractDetailResponse {

    // ---- 合同要点 ----
    private Long id;
    private String no;
    private Long customerId;
    private String customerName;
    private String status;
    private String nature;
    private Integer termMonths;
    private BigDecimal monthRent;
    private BigDecimal deposit;
    private BigDecimal endTransferPrice;
    private BigDecimal targetIrr;
    private LocalDate signDate;
    private LocalDate startDate;
    private String remark;
    private Boolean sensitiveMasked;

    /** 挂载设备(逐台分摊) */
    private List<AssetLine> assets;
    /** 勾稽校验行 */
    private Reconciliation reconciliation;
    /** 回款进度(计划态口径) */
    private Repayment repayment;
    /** 租金计划逐期表 */
    private List<ScheduleLine> schedule;
    /** 每期租金构成拆分(本金摊/资金成本/差价/残值预留) */
    private RentComposition rentComposition;
    /** 单笔 P&L(按合同归集;敏感) */
    private Pnl pnl;
    /** 押金台账 */
    private List<DepositLine> depositLedger;
    /** 变更留痕 */
    private List<ChangeLine> changes;

    @Data
    public static class AssetLine {
        private Long assetId;
        private String serialNo;
        private String category;
        private String model;
        private String assetStatus;
        private BigDecimal allocRent;
    }

    @Data
    public static class Reconciliation {
        /** 期数 */
        private Integer periods;
        private BigDecimal monthRent;
        /** 期数×月租 */
        private BigDecimal rentTotal;
        /** 租金计划实际合计(Σ schedule.amount) */
        private BigDecimal scheduleSum;
        private BigDecimal endTransferPrice;
        /** 客户总付(不含押金)= 期数×月租 + 转让价 */
        private BigDecimal customerTotal;
        /** 计划生成是否对平(scheduleSum ≈ 期数×月租) */
        private Boolean scheduleBalanced;
        /** 勾稽是否成立(rentTotal + transfer == customerTotal) */
        private Boolean balanced;
        /** 押金(单列,不进客户总付) */
        private BigDecimal deposit;
    }

    @Data
    public static class Repayment {
        private Integer totalPeriods;
        /** 已过期数(due_date<=今天) */
        private Integer elapsedPeriods;
        /** 已生成收租单期数(plan_status=已生成单) */
        private Integer billedPeriods;
        /** 回款进度 = 已过期数 / 总期数(计划态口径;真实收款态归 M2 rent_bill) */
        private BigDecimal progressRatio;
    }

    @Data
    public static class ScheduleLine {
        private Integer periodNo;
        private LocalDate dueDate;
        private BigDecimal amount;
        private String planStatus;
        private Long rentBillId;
        /** 相对今天:未到期/已到期 */
        private String dueState;
    }

    @Data
    public static class RentComposition {
        private BigDecimal monthRent;
        /** 本金摊 = 集采成本 / 期数(敏感) */
        private BigDecimal principal;
        /** 资金成本 = 集采成本 × 融资成本/12(敏感) */
        private BigDecimal capitalCost;
        /** 残值预留 = 整机残值合计 / 期数(敏感) */
        private BigDecimal residualReserve;
        /** 差价分摊(毛利)= 月租 - 本金摊 - 资金成本 - 残值预留(敏感) */
        private BigDecimal margin;
        private String note;
    }

    @Data
    public static class Pnl {
        /** 收租总额 = 期数×月租 */
        private BigDecimal rentTotal;
        /** 期末转让价 */
        private BigDecimal transferPrice;
        /** 集采成本 = Σ 设备集采价 */
        private BigDecimal purchaseCost;
        /** 资金成本 = 集采成本 × 融资成本 × 期数/12 */
        private BigDecimal capitalCost;
        /** 坏账拨备 = 收租总额 × 坏账率 */
        private BigDecimal badDebtReserve;
        /** 税后净利(经营口径简化)= 收租总额+转让价-集采-资金成本-坏账拨备 */
        private BigDecimal netProfit;
        /** 净利率 = 净利/收租总额 */
        private BigDecimal netMargin;
        private String note;
    }

    @Data
    public static class DepositLine {
        private String direction;
        private BigDecimal amount;
        private java.time.LocalDateTime bizTime;
        private String remark;
    }

    @Data
    public static class ChangeLine {
        private String changeType;
        private Boolean isReverse;
        private String detail;
        private java.time.LocalDateTime bizTime;
        private String operatorName;
    }
}
