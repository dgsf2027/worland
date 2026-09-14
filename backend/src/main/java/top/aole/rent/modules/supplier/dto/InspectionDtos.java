package top.aole.rent.modules.supplier.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 供应商考察入参/出参。
 */
public final class InspectionDtos {

    private InspectionDtos() {
    }

    /** 新增/编辑考察(仅待考察状态可编辑) */
    @Data
    public static class SaveRequest {
        @NotBlank(message = "公司名称必填")
        @Size(max = 128)
        @ApiModelProperty(value = "公司名称", required = true)
        private String companyName;

        @Size(max = 64)
        @ApiModelProperty("法人")
        private String legalPerson;

        @DecimalMin("0")
        @Digits(integer = 16, fraction = 2)
        @ApiModelProperty("注册资本(元)")
        private BigDecimal registeredCapital;

        @ApiModelProperty("业务范围:货架/阁楼/播种墙,可多选")
        private List<String> businessScope;

        @Size(max = 64)
        @ApiModelProperty("主要联系人")
        private String contact;

        @Size(max = 32)
        @ApiModelProperty("联系方式")
        private String phone;

        @Size(max = 255)
        @ApiModelProperty("备注")
        private String remark;
    }

    /** 判定考察结果 */
    @Data
    public static class DecideRequest {
        @NotBlank(message = "考察结果必填")
        @ApiModelProperty(value = "合格/不合格", required = true)
        private String result;

        @Size(max = 255)
        @ApiModelProperty("考察结论说明")
        private String conclusion;
    }

    /** 列表行 / 详情 */
    @Data
    public static class Item {
        private Long id;
        private String companyName;
        private String legalPerson;
        private BigDecimal registeredCapital;
        private List<String> businessScope;
        private String contact;
        private String phone;
        private String result;
        private String conclusion;
        private String decidedByName;
        private LocalDateTime decidedAt;
        /** 合格后关联的供应商 */
        private Long supplierId;
        private String supplierName;
        private String supplierStatus;
        /** 考察记录压缩包数量 */
        private Integer archiveCount;
        private String remark;
        private LocalDateTime createTime;
    }

    /** 判定结果回执 */
    @Data
    public static class DecideResult {
        private String result;
        private Long supplierId;
        /** true=合格时新建了供应商;false=关联到供应商池已有同名公司 */
        private Boolean supplierCreated;
        private String message;
    }
}
