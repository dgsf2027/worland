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
        private Long assetId;
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
    }

    @Data
    public static class MaintenanceItem {
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
