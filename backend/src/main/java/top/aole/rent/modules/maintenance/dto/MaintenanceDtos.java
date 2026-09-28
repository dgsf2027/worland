package top.aole.rent.modules.maintenance.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 维保工单 DTO 集(M4-04)。
 */
public class MaintenanceDtos {

    @Data
    public static class CreateRequest {
        /** 对象类型:asset 设备租赁台账(默认) / inv_item 资产管理仓库物品 */
        private String targetType;
        private Long assetId;
        /** 仓库物品(targetType=inv_item 时必填) */
        private Long invItemId;
        /** 送修数量(仅仓库物品;默认 1,不能超过库存) */
        private Integer qty;
        /** 故障配件(只能用于设备工单) */
        private Long bomId;
        /** 报修/预防/巡检 */
        private String type;
        private String faultDesc;
        private String remark;
    }

    @Data
    public static class AssignRequest {
        private Long assigneeId;
        /** 责任方:我方/供应商 */
        private String responsibleParty;
        private Long supplierId;
        private String remark;
    }

    @Data
    public static class HandleRequest {
        /** 维修费用(元;质保内/供应商责任则不计我方) */
        private BigDecimal cost;
        /** 是否质保内(质保内转供应商·费用不计我方) */
        private Boolean inWarranty;
        private String handleNote;
        /** 是否回写故障计数(fault_count++) */
        private Boolean recordFault;
        /** 仓库物品工单:修不好直接报废(维修中→已报废);否则修好退回库存 */
        private Boolean scrapped;
    }

    @Data
    public static class MaintenanceItem {
        /** 对象类型:asset / inv_item */
        private String targetType;
        /** 对象显示名:设备为「品类 · 序列号」,仓库物品为「名称 · 编号」 */
        private String targetLabel;
        /** 仓库物品 id(设备工单为空) */
        private Long invItemId;
        /** 送修数量(仓库物品工单) */
        private Integer qty;
        private Long id;
        private String no;
        private Long assetId;
        private String serialNo;
        private String assetCategory;
        private Long bomId;
        private String bomName;
        private String type;
        private String status;
        private String faultDesc;
        private Boolean inWarranty;
        private String responsibleParty;
        private Long supplierId;
        private String supplierName;
        private BigDecimal cost;
        /** 计入我方成本(责任方=我方 且 非质保内) */
        private BigDecimal ourCost;
        private String handleNote;
        private LocalDateTime reportedAt;
        private LocalDateTime assignedAt;
        private LocalDateTime finishedAt;
        private String remark;
    }

    /** 高故障配件预警(fault_count 超阈值 → 备件提示)。 */
    @Data
    public static class SparePartAlert {
        private Long assetId;
        private String serialNo;
        private Long bomId;
        private String bomName;
        private Integer faultCount;
        private Boolean repairable;
        private String suggestion;
    }

    @Data
    public static class SparePartAlertResponse {
        private Integer threshold;
        private Integer alertCount;
        private List<SparePartAlert> items;
    }
}
