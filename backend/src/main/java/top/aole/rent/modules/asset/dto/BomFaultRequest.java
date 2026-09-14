package top.aole.rent.modules.asset.dto;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 故障档案编辑入参(按配件):故障次数/可维修/质保到期/质保方。
 * 注:维保工单完工也会回写 fault_count(+1),此处为人工校正入口。
 */
@Data
public class BomFaultRequest {

    @NotNull(message = "故障次数必填")
    @Min(0)
    private Integer faultCount;

    private Boolean repairable;

    private LocalDate warrantyUntil;

    /** 质保方(供应商);null = 清空 */
    private Long supplierId;
}
