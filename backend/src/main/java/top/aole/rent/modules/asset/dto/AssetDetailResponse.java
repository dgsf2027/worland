package top.aole.rent.modules.asset.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备详情:状态机时间轴 + 配件树 BOM(递归) + 成本拆解 + 残值构成 + 故障档案 + 单台收益。
 * 对齐 UI mockup 设备详情页。敏感财务字段(集采价/账面价/成本)按角色投影。
 */
@Data
public class AssetDetailResponse {

    private Long id;
    private String serialNo;
    private String category;
    private String model;
    private String status;
    private BigDecimal marketPrice;
    private BigDecimal purchasePrice;   // 敏感
    private BigDecimal monthlyLaborValue;
    private BigDecimal replaceHeadcount;
    private String supplierName;
    private String currentHolderName;
    private Long contractId;
    private String remark;
    private Boolean sensitiveMasked;

    /** 派生·即时算:经营口径账面价(采购价-直线折旧占位·M3精确化) */
    private BigDecimal bookValue;
    /** 派生·即时算:残值=市场价×品类转让率 */
    private BigDecimal residualValue;
    /** 派生·即时算:自购回本期(月)=市场价/月替代人工价值 */
    private BigDecimal selfPurchasePayback;

    /** 配件树 BOM(递归,仅一级根节点,children 内嵌) */
    private List<BomNode> bom;
    /** 成本拆解(Σ 一级总成) */
    private CostBreakdown costBreakdown;
    /** 残值构成(Σ 部件残值) */
    private ResidualBreakdown residualBreakdown;
    /** 故障档案(按配件,fault_count 降序) */
    private List<FaultItem> faultArchive;
    /** 单台收益 */
    private SingleUnitReturn singleUnitReturn;
    /** 状态机事件时间轴(倒序) */
    private List<EventItem> timeline;

    @Data
    public static class BomNode {
        private Long id;
        private Long parentId;
        private String name;
        private BigDecimal qty;
        private BigDecimal unitCost;      // 敏感
        private BigDecimal subtotal;      // 敏感 qty×unitCost
        private String supplierName;
        private BigDecimal lifeYears;
        private LocalDate warrantyUntil;
        private Boolean repairable;
        private Integer faultCount;
        private BigDecimal residualRate;
        private List<BomNode> children;
    }

    @Data
    public static class CostBreakdown {
        /** 一级总成成本(名称→小计),敏感 */
        private List<CostItem> items;
        private BigDecimal total;
        /** 对比集采价识别虚高:集采价 - BOM 合计 */
        private BigDecimal purchasePrice;
        private BigDecimal gapVsPurchase;
    }

    @Data
    public static class CostItem {
        private String name;
        private BigDecimal amount;
        private BigDecimal ratio;
    }

    @Data
    public static class ResidualBreakdown {
        /** 部件残值(名称→残值=residualRate×subtotal) */
        private List<CostItem> items;
        /** Σ 部件残值 */
        private BigDecimal bomResidualTotal;
        /** 整机残值(市场价×品类转让率;转让定价参考) */
        private BigDecimal categoryResidual;
    }

    @Data
    public static class FaultItem {
        private String name;
        private Integer faultCount;
        private Boolean repairable;
        private LocalDate warrantyUntil;
        private String supplierName;
        /** 质保剩余天数(负=已过保) */
        private Long warrantyDaysLeft;
    }

    @Data
    public static class SingleUnitReturn {
        /** 累计收租(按 alloc_rent×已过期数·占位;M2 以 rent_bill 精确化) */
        private BigDecimal cumulativeRent;
        private BigDecimal allocRent;
        private Integer inServiceDays;
        private Integer idleDays;
        /** 单台回报率(累计收租+残值-集采)/集采,敏感 */
        private BigDecimal returnRate;
        /** 空置亮灯 */
        private Boolean idleAlert;
    }

    @Data
    public static class EventItem {
        private String eventType;
        private LocalDateTime bizTime;
        private String refDocType;
        private Long refDocId;
        private String operatorName;
        private String remark;
    }
}
