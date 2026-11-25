package top.aole.rent.modules.asset.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Size;
import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 新增/编辑设备入参(逐件建档)。
 */
@Data
public class AssetSaveRequest {

    /** 序列号(系统内部唯一标识);留空自动生成 */
    @Size(max = 64)
    private String serialNo;

    @NotBlank(message = "品类必填")
    private String category;

    private String model;
    private BigDecimal marketPrice;
    private BigDecimal purchasePrice;
    private BigDecimal dealPriceOverride;
    private Long supplierId;
    private BigDecimal monthlyLaborValue;
    private BigDecimal replaceHeadcount;
    private Integer warrantyYears;
    private LocalDate warrantyStartDate;
    private LocalDate warrantyEndDate;
    private String deliveryAddress;
    private String deliveryContact;
    private BigDecimal weighTons;
    private String remark;
}
