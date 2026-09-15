package top.aole.rent.modules.customer.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 记一次跟进 / 编辑跟进请求。写入后同步客户 next_follow_date(取最近一条跟进的下次跟进日)。
 */
@Data
public class FollowupRequest {

    @ApiModelProperty(value = "方式:电话/拜访/微信", example = "电话")
    private String method;

    @NotBlank(message = "跟进内容必填")
    @Size(max = 500)
    @ApiModelProperty(value = "跟进内容", required = true)
    private String content;

    @Size(max = 255)
    @ApiModelProperty("结果")
    private String result;

    @ApiModelProperty("下次跟进日 YYYY-MM-DD")
    private LocalDate nextFollowDate;

    @ApiModelProperty("关联合同(可空;须是本客户的合同)")
    private Long contractId;
}
