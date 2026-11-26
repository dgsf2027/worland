package top.aole.rent.modules.project.dto;

import lombok.Data;
import top.aole.rent.modules.contract.dto.ProjectPanoramaDtos;

import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 租赁项目（一单到底）DTO。
 */
public final class ProjectDtos {

    private ProjectDtos() {
    }

    @Data
    public static class SaveRequest {
        private String projectNo;
        @NotBlank(message = "项目名称必填")
        private String name;
        private String assetMode;
        private Long customerId;
        private Long supplierId;
        private Long rentContractId;
        private Long purchaseContractId;
        private Long primaryAssetId;
        private Integer palletSlots;
        private BigDecimal plannedTons;
        private String warehouseZone;
        private String deliveryAddress;
        private String deliveryContact;
        private Integer warrantyYears;
        private BigDecimal quotePrincipal;
        private BigDecimal quoteAnnualRate;
        private Integer quoteTermMonths;
        private BigDecimal quoteMonthRent;
        private BigDecimal quoteBuyoutPrice;
        private String remark;
    }

    @Data
    public static class DeliveryRequest {
        private LocalDate productionStartDate;
        private LocalDate shippedDate;
        private LocalDate installDoneDate;
        private LocalDate loadTestDate;
        private BigDecimal actualWeighTons;
        private LocalDate acceptanceDate;
        private LocalDate auditPassDate;
        private Integer warrantyYears;
        private Integer palletSlots;
        private String warehouseZone;
        private String deliveryAddress;
        private String deliveryContact;
    }

    @Data
    public static class CreateKitAssetRequest {
        private String category;
        private String model;
        private BigDecimal purchasePrice;
        private BigDecimal marketPrice;
        private Integer palletSlots;
        private BigDecimal weighTons;
        private String warehouseZone;
        private String remark;
    }

    @Data
    public static class StepInfo {
        private int stepNo;
        private String key;
        private String title;
        /** done / active / pending */
        private String status;
        private String summary;
        private String dateText;
    }

    @Data
    public static class ListItem {
        private Long id;
        private String projectNo;
        private String name;
        private String assetMode;
        private String stage;
        private int currentStepNo;
        private Long customerId;
        private String customerName;
        private Long supplierId;
        private String supplierName;
        private Long rentContractId;
        private String rentContractNo;
        private Long purchaseContractId;
        private String purchaseContractNo;
        private Long primaryAssetId;
        private String primaryAssetNo;
        private Integer palletSlots;
        private BigDecimal plannedTons;
        private BigDecimal actualWeighTons;
        private String warehouseZone;
        private LocalDate acceptanceDate;
        private LocalDate warrantyEndDate;
        private boolean costVisible;
        private BigDecimal purchaseTotal;
        private BigDecimal receivedRent;
        private BigDecimal actualNetCashflow;
        private BigDecimal expectedGrossProfit;
        private BigDecimal paybackProgress;
        private Integer paybackPeriodNo;
        private List<StepInfo> steps = new ArrayList<>();
    }

    @Data
    public static class Detail {
        private Long id;
        private String projectNo;
        private String name;
        private String assetMode;
        private String stage;
        private int currentStepNo;
        private Long customerId;
        private String customerName;
        private Long supplierId;
        private String supplierName;
        private Long rentContractId;
        private String rentContractNo;
        private String rentContractStatus;
        private Long purchaseContractId;
        private String purchaseContractNo;
        private String purchaseContractStatus;
        private Long primaryAssetId;
        private String primaryAssetNo;
        private String primaryAssetLabel;
        private Integer palletSlots;
        private BigDecimal plannedTons;
        private BigDecimal actualWeighTons;
        private BigDecimal weighDiffPct;
        private Boolean weighWithinTolerance;
        private String warehouseZone;
        private String deliveryAddress;
        private String deliveryContact;
        private LocalDate productionStartDate;
        private LocalDate shippedDate;
        private LocalDate installDoneDate;
        private LocalDate loadTestDate;
        private LocalDate acceptanceDate;
        private LocalDate auditPassDate;
        private Integer warrantyYears;
        private LocalDate warrantyEndDate;
        private BigDecimal quotePrincipal;
        private BigDecimal quoteAnnualRate;
        private Integer quoteTermMonths;
        private BigDecimal quoteMonthRent;
        private BigDecimal quoteBuyoutPrice;
        private String remark;
        private boolean costVisible;
        private List<StepInfo> steps = new ArrayList<>();
        /** 嵌入实时项目真实利润全景（已绑定租赁合同时返回） */
        private ProjectPanoramaDtos.Panorama panorama;
    }

    @Data
    public static class SyncResult {
        private int createdCount;
        private List<String> createdNos = new ArrayList<>();
    }
}
