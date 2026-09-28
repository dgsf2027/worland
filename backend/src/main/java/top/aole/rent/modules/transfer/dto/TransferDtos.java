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
        /** 合同客户(到期转让必有;二手/报废按台处置时为空) */
        private String customerName;
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
        /** 设备显示名:品类 · 型号(型号为空退回序列号);设备已删则为「#id」 */
        private String assetLabel;
        private String serialNo;
        private String category;
        private String model;
        /** 设备当前台账状态(设备已删为空) */
        private String assetStatus;
        /** 转让当时的账面价快照(存在行上,设备删了也还在) */
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

    /**
     * 设备的一条转让/处置记录(设备租赁台账详情反向展示用)。
     * 台账 → 转让 的反向入口:一台设备可能先被转让、再走二手/报废,逐条列出来。
     */
    @Data
    public static class DisposalLine {
        /** 转让单行 id */
        private Long id;
        private Long orderId;
        private String orderNo;
        /** 类型:转让/收回/二手/报废 */
        private String type;
        private BigDecimal transferPrice;
        /** 处置损益 */
        private BigDecimal gain;
        /** 单据状态:待审批/待过账/已完成/已作废 */
        private String status;
        private LocalDateTime bizTime;
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
