package top.aole.rent.modules.customer.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * 客户新增/编辑请求。编辑时未传(null)的旧字段保留原值;法人/注册资本/业务范围按传入值覆盖(可清空)。
 */
@Data
public class CustomerSaveRequest {

    @Size(max = 64)
    @ApiModelProperty("主要联系人")
    private String contact;

    @Size(max = 32)
    @ApiModelProperty("联系方式")
    private String phone;

    @NotBlank(message = "公司名称必填")
    @Size(max = 128)
    @ApiModelProperty(value = "公司名称", required = true)
    private String name;

    @Size(max = 64)
    @ApiModelProperty("法人")
    private String legalPerson;

    @DecimalMin("0")
    @Digits(integer = 16, fraction = 2)
    @ApiModelProperty("注册资本(元)")
    private BigDecimal registeredCapital;

    @ApiModelProperty("业务范围:货架/阁楼/播种墙,可多选")
    private List<String> businessScope;

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
