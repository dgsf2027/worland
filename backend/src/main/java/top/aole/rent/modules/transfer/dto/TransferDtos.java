package top.aole.rent.modules.transfer.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 转让/处置模块 DTO 集(M4-01/02/03)。
 */
public class TransferDtos {

    // ============ 请求 ============

    /** 到期转让:挂合同 N 台;lines 为空则默认取合同全部在租设备,transfer_price 按 endTransferPrice 分摊。 */
    @Data
    public static class ExpiryTransferRequest {
        private Long contractId;
        /** 逐台转让价(可空→默认分摊);key=assetId */
        private List<LineInput> lines;
        /** 名义价守卫触发时的低价理由(强制审批要求) */
        private String approvalReason;
        private String remark;
    }

    @Data
    public static class LineInput {
        private Long assetId;
        private BigDecimal transferPrice;
        private String remark;
    }

    /** 单台处置(复投飞轮 M4-03):收回待处置设备 → 再投放/二手/报废。 */
    @Data
    public static class DisposeRequest {
        private Long assetId;
        /** 动作:再投放/二手/报废 */
        private String action;
        /** 二手处置价(action=二手 必填) */
        private BigDecimal transferPrice;
        private String approvalReason;
        private String remark;
    }

    @Data
    public static class ApproveRequest {
        private String reason;
    }

    // ============ 响应 ============

    @Data
    public static class TransferItem {
        private Long id;
        private String no;
        private Long contractId;
        private String contractNo;
        private String type;
        private Integer assetCount;
        private BigDecimal totalPrice;
        private BigDecimal totalGain;
        private String status;
        private Boolean needApproval;
        private String approvalReason;
        private String approvedByName;
        private LocalDateTime approvedAt;
        private LocalDateTime bizTime;
        private String remark;
    }

    @Data
    public static class TransferLineItem {
        private Long id;
        private Long assetId;
        private String serialNo;
        private String category;
        private BigDecimal bookValue;
        private BigDecimal marketPrice;
        private BigDecimal transferPrice;
        private BigDecimal gain;
        private Boolean nominalFlag;
        private Long voucherId;
        private String remark;
    }

    @Data
    public static class TransferDetail {
        private TransferItem order;
        private List<TransferLineItem> lines;
    }

    /** 到期转让创建结果:含名义价守卫结论 + 逐台损益 + 凭证/出账影响清单。 */
    @Data
    public static class TransferResult {
        private Long transferOrderId;
        private String no;
        private String type;
        private String status;
        private Boolean needApproval;
        private BigDecimal totalPrice;
        private BigDecimal totalGain;
        private Integer assetCount;
        /** 触发名义价守卫的逐台明细(资产序列号) */
        private List<String> nominalGuardHits;
        /** 影响清单(逐台残值凭证/资产出账/合同关闭) */
        private List<String> impact;
    }
}
