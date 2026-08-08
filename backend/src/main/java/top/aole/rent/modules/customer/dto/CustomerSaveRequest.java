package top.aole.rent.modules.customer.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * 客户新增/编辑请求。
 */
@Data
public class CustomerSaveRequest {

    @ApiModelProperty("联系人")
    private String contact;

    @ApiModelProperty("电话")
    private String phone;

    @NotBlank(message = "客户名称必填")
    @ApiModelProperty(value = "客户名称", required = true)
    private String name;

    @ApiModelProperty("行业")
    private String industry;

    @ApiModelProperty(value = "阶段:线索/跟进/商机/成交/在租/流失", example = "线索")
    private String phase;

    @ApiModelProperty("价值分层:战略/普通/观察")
    private String valueTier;

    @ApiModelProperty("负责业务 user 主键;不传=公海")
    private Long ownerUser;

    // 信用画像五维(可空)
    private Integer scoreProfit;
    private Integer scoreCashflow;
    private Integer scoreStability;
    private Integer scoreHistory;
    private Integer scoreIndustry;
}
