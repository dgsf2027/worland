package top.aole.rent.modules.supplier.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 供应商详情分块编辑 + Excel 导入回执。
 */
public final class SupplierEditDtos {

    private SupplierEditDtos() {
    }

    /** 履约评分(五维 0-100,可空=该维未评;加权总分由权重即时算) */
    @Data
    public static class ScoreRequest {
        @Min(0) @Max(100)
        private Integer quality;
        @Min(0) @Max(100)
        private Integer delivery;
        @Min(0) @Max(100)
        private Integer service;
        @Min(0) @Max(100)
        private Integer price;
        @Min(0) @Max(100)
        private Integer term;
    }

    /** 价格构成(vs 我方 BOM 估算识别虚高) */
    @Data
    public static class PriceRequest {
        @DecimalMin("0") @Digits(integer = 16, fraction = 2)
        private BigDecimal material;
        @DecimalMin("0") @Digits(integer = 16, fraction = 2)
        private BigDecimal processing;
        @Digits(integer = 16, fraction = 2)
        private BigDecimal profit;
        @DecimalMin("0") @Digits(integer = 16, fraction = 2)
        @ApiModelProperty("报价(元);写回代表供货项的集采报价")
        private BigDecimal quote;
        @DecimalMin("0") @Digits(integer = 16, fraction = 2)
        private BigDecimal bomEstimate;
    }

    /** 供货矩阵单行新增/编辑 */
    @Data
    public static class SupplyRequest {
        @ApiModelProperty("整机/配件")
        private String itemType;
        @NotBlank(message = "供货项名称必填")
        @Size(max = 128)
        private String itemName;
        @Size(max = 32)
        private String category;
        @DecimalMin("0") @Digits(integer = 16, fraction = 2)
        private BigDecimal quotePrice;
        @DecimalMin("0") @DecimalMax("1")
        private BigDecimal firstPayRatio;
        @Min(0)
        private Integer accountDays;
        private Boolean noInterest;
        private Boolean canSingleBuy;
        @ApiModelProperty("设为代表供货项(同一供应商只保留一个)")
        private Boolean primary;
        @Size(max = 255)
        private String remark;
    }

    /** Excel 导入回执 */
    @Data
    public static class ImportResult {
        private int supplierRows;
        private int suppliersCreated;
        private int suppliersUpdated;
        private int supplyRows;
        private int suppliesCreated;
        private int suppliesUpdated;
        private int skipped;
        private List<RowMessage> messages = new ArrayList<>();
    }

    @Data
    public static class RowMessage {
        private String sheet;
        private int row;
        private String name;
        private String level;
        private String message;

        public RowMessage() {
        }

        public RowMessage(String sheet, int row, String name, String level, String message) {
            this.sheet = sheet;
            this.row = row;
            this.name = name;
            this.level = level;
            this.message = message;
        }
    }
}
