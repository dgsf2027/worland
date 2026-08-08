package top.aole.rent.modules.supplier.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 供应商淘汰/停用请求(留痕)。
 */
@Data
public class RetireRequest {

    @NotBlank(message = "淘汰原因必填(留痕)")
    @ApiModelProperty(value = "淘汰/停用原因", required = true)
    private String reason;
}
