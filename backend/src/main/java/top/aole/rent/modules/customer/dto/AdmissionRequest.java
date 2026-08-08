package top.aole.rent.modules.customer.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 风控准入请求。默认按评级建议值落定;可覆盖(审批调整);拒绝也留痕。
 */
@Data
public class AdmissionRequest {

    @ApiModelProperty("授信额度覆盖(元);不传=用评级建议值")
    private BigDecimal creditLimit;

    @ApiModelProperty("押金月数覆盖;不传=用评级建议值")
    private BigDecimal depositMonths;

    @ApiModelProperty("目标IRR覆盖(0-1);不传=用评级建议值")
    private BigDecimal targetIrr;

    @ApiModelProperty("准入结论备注(拒绝原因也在此留痕)")
    private String note;

    @ApiModelProperty(value = "是否准入通过:true落定授信 / false拒绝留痕", example = "true")
    private Boolean approved;
}
