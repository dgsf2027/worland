package top.aole.rent.modules.asset.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Size;
import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * 新增/编辑设备入参(逐件建档)。
 */
@Data
public class AssetSaveRequest {

    /** 合同编号(多台设备可共用);留空则不关联合同 */
    @Size(max = 64)
    private String contractNo;

    /** 序列号(系统内部唯一标识);留空自动生成 */
    @Size(max = 64)
    private String serialNo;

    @NotBlank(message = "品类必填")
    private String category;

    private String model;
    private BigDecimal marketPrice;
    private BigDecimal purchasePrice;
    /** 合同税率(0-1,如 0.13) */
    @DecimalMin("0")
    @DecimalMax("1")
    private BigDecimal taxRate;
    private Long supplierId;
    private BigDecimal monthlyLaborValue;
    private BigDecimal replaceHeadcount;
    private String remark;
}
