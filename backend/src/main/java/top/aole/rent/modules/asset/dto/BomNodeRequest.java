package top.aole.rent.modules.asset.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.Digits;
import javax.validation.constraints.Min;
import javax.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 新增/编辑配件树节点入参。parentId 空=一级总成。
 */
@Data
public class BomNodeRequest {

    @NotBlank(message = "配件名必填")
    @Size(max = 128)
    private String name;

    private Long parentId;
    @DecimalMin("0.01")
    @Digits(integer = 7, fraction = 2)
    private BigDecimal qty;
    @DecimalMin("0")
    @Digits(integer = 16, fraction = 2)
    private BigDecimal unitCost;
    /** 手动小计；null 恢复按数量乘单价自动计算。 */
    @DecimalMin("0")
    @Digits(integer = 16, fraction = 2)
    private BigDecimal subtotalOverride;
    private Long supplierId;
    @DecimalMin("0")
    @Digits(integer = 7, fraction = 2)
    private BigDecimal lifeYears;
    private LocalDate warrantyUntil;
    private Boolean repairable;
    @Min(0)
    private Integer faultCount;
    @DecimalMin("0")
    @DecimalMax("1")
    private BigDecimal residualRate;
    @Size(max = 255)
    private String remark;
}
