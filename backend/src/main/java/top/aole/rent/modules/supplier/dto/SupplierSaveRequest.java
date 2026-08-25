package top.aole.rent.modules.supplier.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.List;

/**
 * 供应商新增/编辑请求。含供货矩阵行(整机/配件);编辑时 supplies 全量覆盖。
 */
@Data
public class SupplierSaveRequest {

    @ApiModelProperty("联系人")
    private String contact;

    @ApiModelProperty("电话")
    private String phone;

    @NotBlank(message = "供应商名称必填")
    @ApiModelProperty(value = "供应商名称", required = true)
    private String name;

    @ApiModelProperty("公司全称(工商注册名;开票抬头)")
    private String fullName;

    @ApiModelProperty("统一社会信用代码/纳税人识别号")
    private String taxNo;

    @ApiModelProperty("注册地址(开票用)")
    private String regAddress;

    @ApiModelProperty("注册电话(开票用)")
    private String regPhone;

    @ApiModelProperty("开户行(支行全称)")
    private String bankName;

    @ApiModelProperty("银行账号")
    private String bankAccount;

    @ApiModelProperty("收款户名(留空默认取公司全称)")
    private String accountName;

    @ApiModelProperty(value = "发票类型:增值税专用发票/增值税普通发票/无票", example = "增值税专用发票")
    private String invoiceType;

    @ApiModelProperty("主营品类:播种墙/货架/阁楼/配件")
    private String mainCategory;

    @ApiModelProperty(value = "关系阶段:接触/试样/入库/主供/备供/淘汰", example = "接触")
    private String status;

    @ApiModelProperty("备注")
    private String remark;

    @ApiModelProperty("供货矩阵行(可空;编辑时全量覆盖)")
    private List<SupplyItem> supplies;

    @Data
    public static class SupplyItem {
        private String itemType;
        @NotBlank(message = "供货项名称必填")
        private String itemName;
        private String category;
        private BigDecimal quotePrice;
        private BigDecimal firstPayRatio;
        private Integer accountDays;
        private Integer noInterest;
        private Integer canSingleBuy;
        private Integer scoreQuality;
        private Integer scoreDelivery;
        private Integer scoreService;
        private Integer scorePrice;
        private Integer scoreTerm;
        private BigDecimal costMaterial;
        private BigDecimal costProcessing;
        private BigDecimal profitAmount;
        private BigDecimal bomEstimate;
        private Integer isPrimary;
        private String remark;
    }
}
