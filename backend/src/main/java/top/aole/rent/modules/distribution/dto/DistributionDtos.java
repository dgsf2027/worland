package top.aole.rent.modules.distribution.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 结账分配模块 DTO 汇总(M3 Wave B · M3-03/04)。
 */
public class DistributionDtos {

    // ---------- 分配运行请求 ----------

    @Data
    public static class RunRequest {
        /** 分配期 YYYY-MM(必填) */
        private String period;
        /**
         * 提取前净利(元·可选)。缺省 = 该期经营账 ledger_book 收入−成本自动汇总。
         * 手工传入用于结账调整/演示(方案书§13.2:50万净利)。
         */
        private BigDecimal profitBefore;
        /**
         * 公司回报率(可选)。缺省 = profit_before / 实缴出资合计(方案书§11.1/§13.2 口径:50万/200万=25%)。
         * 管理费阶梯据此选档。
         */
        private BigDecimal returnRate;
        /** 分配业务日期(可选·缺省当期 5 号) */
        private String bizDate;
        /** 幂等冲突时是否强制:true=先冲销该期既有 active 分配再重算 */
        private Boolean force;
        private String remark;
    }

    @Data
    public static class ReverseRequest {
        private String reason;
    }

    // ---------- 分配头 + 每人份额 ----------

    @Data
    public static class DistributionItem {
        private Long id;
        private String distributionNo;
        private String period;
        private LocalDate bizDate;
        private BigDecimal totalCapital;
        private BigDecimal profitBefore;
        private BigDecimal returnRate;
        private BigDecimal mgmtFeeRate;
        private BigDecimal mgmtFee;
        private BigDecimal distributable;
        private BigDecimal cash50;
        private BigDecimal roll50;
        private BigDecimal reserveFloor;
        private BigDecimal reserveAfter;
        private Boolean reserveSufficient;
        private String status;
        private Boolean isReversal;
        private Long reversesId;
        private LocalDateTime createTime;
    }

    /** 每人份额(现场按分配快照 × investor.ratio 即时算·P0-E 角色可见投影)。 */
    @Data
    public static class ShareItem {
        private Long investorId;
        private String name;
        private String role;          // GP/LP
        private BigDecimal amount;     // 出资额
        private BigDecimal ratio;      // 出资比例
        private BigDecimal cashShare;  // 现金份额 = cash_50 × ratio
        private BigDecimal rollShare;  // 滚存份额 = roll_50 × ratio
        private BigDecimal mgmtFee;    // GP 另得管理费(仅数智云仓非零)
        private BigDecimal totalGain;  // 本期总收益 = cashShare + rollShare + mgmtFee
        private Boolean self;          // 是否当前登录用户本人那份
    }

    @Data
    public static class DistributionDetail {
        private DistributionItem distribution;
        /** 每人份额(角色投影:LP 仅见自己那份;老板/财务见全量) */
        private List<ShareItem> shares;
        /** 分配计算链路(利润→管理费阶梯→可分配→50/50→留存)逐步说明 */
        private List<String> steps;
    }

    // ---------- 冲销影响 ----------

    @Data
    public static class ReverseImpact {
        private Long originalId;
        private String originalNo;
        private Long reversalId;
        private String reversalNo;
        private String period;
        private BigDecimal distributable;
        private List<String> items;
    }

    // ---------- 出资人 ----------

    @Data
    public static class InvestorItem {
        private Long id;
        private String name;
        private String role;
        private BigDecimal amount;
        private BigDecimal ratio;
        private Boolean self;
    }
}
