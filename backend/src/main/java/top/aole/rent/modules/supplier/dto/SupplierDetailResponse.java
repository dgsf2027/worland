package top.aole.rent.modules.supplier.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 供应商详情:履约雷达(多维+加权总分) + 供货矩阵 + 价格构成(vs BOM)。业务流§8.2。
 */
@Data
public class SupplierDetailResponse {

    private Long id;
    private String name;
    private String contact;
    private String phone;
    private String companyAccount;
    private String openingBank;
    private String status;
    private String mainCategory;
    private String remark;

    /** 履约评分雷达(取代表供货项五维 + 加权总分) */
    private ScoreRadar scoreRadar;

    /** 供货矩阵(整机+配件) */
    private List<SupplyRow> supplyMatrix;

    /** 价格构成(取代表供货项;无成本拆解=null) */
    private PriceComposition priceComposition;

    /** 敏感字段是否已按角色打码(LP 不可见成本/价格构成) */
    private Boolean costMasked;

    @Data
    public static class ScoreRadar {
        private Integer quality;
        private Integer delivery;
        private Integer service;
        private Integer price;
        private Integer term;
        /** 加权总分(即时算,权重来自 rule_config) */
        private Integer total;
    }

    @Data
    public static class SupplyRow {
        private Long id;
        private String itemType;
        private String itemName;
        private String category;
        private BigDecimal quotePrice;
        private BigDecimal firstPayRatio;
        private Integer accountDays;
        private Boolean canSingleBuy;
        private Integer scoreTotal;
    }

    @Data
    public static class PriceComposition {
        private BigDecimal material;
        private BigDecimal processing;
        private BigDecimal profit;
        private BigDecimal quote;
        private BigDecimal bomEstimate;
        /** 报价 vs BOM 估算结论:合理/偏高 */
        private String verdict;
    }
}
