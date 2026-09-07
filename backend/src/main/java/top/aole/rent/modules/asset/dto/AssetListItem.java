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
    /** 敏感字段是否已打码(GP/LP=true) */
    private Boolean sensitiveMasked;
}
