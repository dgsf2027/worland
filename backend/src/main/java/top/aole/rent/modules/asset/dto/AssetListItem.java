package top.aole.rent.modules.asset.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 设备台账列表项(逐件)。金额类敏感字段(集采价/账面价)按角色投影,GP/LP 打码。
 */
@Data
public class AssetListItem {

    private Long id;
    private String serialNo;
    /** 所属合同编号(来自关联合同,只读) */
    private String contractNo;
    private Long contractId;
    private String category;
    private String model;
    private String status;
    private BigDecimal marketPrice;
    /** 【敏感】集采价;GP/LP 置 null */
    private BigDecimal purchasePrice;
    /** 【敏感·派生·即时算】经营口径账面价 */
    private BigDecimal bookValue;
    /** 【派生·即时算】残值=市场价×品类转让率 */
    private BigDecimal residualValue;
    private Long supplierId;
    private String supplierName;
    private Long currentHolderCustomerId;
    private String currentHolderName;
    /** 意向承接客户(未签约设备预设) */
    private Long intendedCustomerId;
    private String intendedCustomerName;
    /** 敏感字段是否已打码(GP/LP=true) */
    /** 处置状态:最近一次转让/处置的「类型 · 状态」,无记录为 null */
    private String disposalStatus;

    /** 本设备待付应付合计(成本口径·敏感) */
    private BigDecimal payablePending;

    private Boolean sensitiveMasked;
}
