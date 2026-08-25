package top.aole.rent.modules.supplier.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 供应商池列表行。集采价/首付/履约分取代表供货项(is_primary),履约分由权重即时算。
 */
@Data
public class SupplierPoolItem {

    private Long id;
    private String name;
    private String contact;
    private String status;
    private String mainCategory;

    /** 公司全称(工商注册名);列表用于区分同简称的不同主体 */
    private String fullName;
    /** 收款账户是否已填全(公司全称+开户行+银行账号);缺则采购付款卡壳 */
    private Boolean billingComplete;

    /** 代表供货项:品类/配件描述 */
    private String itemDesc;
    /** 集采价(元);按图报价=null */
    private BigDecimal quotePrice;
    /** 首付比例(0-1) */
    private BigDecimal firstPayRatio;
    /** 履约加权总分(即时算);无评分=null */
    private Integer scoreTotal;
}
