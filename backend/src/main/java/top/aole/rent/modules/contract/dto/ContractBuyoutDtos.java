package top.aole.rent.modules.contract.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 租中提前买断试算与办理 DTO（Step 3 租后闭环）。
 */
public final class ContractBuyoutDtos {

    private ContractBuyoutDtos() {
    }

    @Data
    public static class Preview {
        private Long contractId;
        private String contractNo;
        private String customerName;
        private LocalDate buyoutDate;
        private int totalPeriods;
        private int settledPeriods;
        private int remainingPeriods;
        /** 剩余未还本金（来自最新已结清期的 closingPrincipal 或折算本金） */
        private BigDecimal remainingPrincipal;
        /** 历史逾期/已出单未收租金 */
        private BigDecimal overdueUnpaidRent;
        /** 提前买断违约金比例（如 0.03 = 3%） */
        private BigDecimal penaltyRate;
        /** 提前买断违约金 = 剩余本金 × 违约金比例 */
        private BigDecimal penaltyAmount;
        /** 合同约定留购价（如 1.00 元） */
        private BigDecimal endTransferPrice;
        /** 在手可抵扣押金 */
        private BigDecimal depositHeld;
        /** 客户应补买断净额 = 剩余本金 + 逾期未收 + 违约金 + 留购价 − 在手押金 */
        private BigDecimal netBuyoutPayable;
        /** 累计已收租金 */
        private BigDecimal collectedRent;
        /** 若按今日买断，我方累计总回收 = 已收租金 + 押金 + 应补买断净额 */
        private BigDecimal totalRecoveryIfBuyout;
        /** 采购投入参考（GP/LP 脱敏为 null） */
        private BigDecimal purchaseCost;
        /** 若按今日买断，预计总毛利（GP/LP 脱敏为 null） */
        private BigDecimal buyoutGrossProfit;
        /** 持有到期原预计毛利（对照用，GP/LP 脱敏为 null） */
        private BigDecimal fullTermGrossProfit;
    }

    @Data
    public static class ExecuteRequest {
        private LocalDate buyoutDate;
        /** 可覆盖违约金率（如 0.03） */
        private BigDecimal penaltyRate;
        /** 协商最终买断金额（留空则采用系统算出的 netBuyoutPayable） */
        private BigDecimal negotiatedAmount;
        private String remark;
    }

    @Data
    public static class ExecuteResult {
        private Long contractId;
        private String contractNo;
        private BigDecimal finalBuyoutAmount;
        private int truncatedPeriods;
        private int transferredAssets;
        private String contractStatus;
    }
}
