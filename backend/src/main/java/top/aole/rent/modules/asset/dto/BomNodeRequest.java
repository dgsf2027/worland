package top.aole.rent.modules.asset.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 新增/编辑配件树节点入参。parentId 空=一级总成。
 */
@Data
public class BomNodeRequest {

    @NotBlank(message = "配件名必填")
    private String name;

    private Long parentId;
    private BigDecimal qty;
    private BigDecimal unitCost;
    private Long supplierId;
    private BigDecimal lifeYears;
    private LocalDate warrantyUntil;
    private Boolean repairable;
    private Integer faultCount;
    private BigDecimal residualRate;
    private String remark;
}
