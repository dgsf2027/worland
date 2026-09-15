package top.aole.rent.modules.supplier.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 供应商考察入参/出参(字段对齐「厂家考察汇总表」)。
 */
public final class InspectionDtos {

    private InspectionDtos() {
    }

    /** 新增/编辑考察(任意结果均可编辑公司信息;结果走判定接口) */
    @Data
    public static class SaveRequest {
        @Min(0)
        @ApiModelProperty("序号(列表排序);不传则排到最后")
        private Integer sortNo;

        @NotBlank(message = "公司名称必填")
        @Size(max = 128)
        @ApiModelProperty(value = "公司名称", required = true)
        private String companyName;

        @Size(max = 64)
        @ApiModelProperty("法人")
        private String legalPerson;

        @Size(max = 64)
        @ApiModelProperty("注册资本(万元·原文,如 200 / 60*6)")
        private String registeredCapitalWan;

        @ApiModelProperty("成立时间")
        private LocalDate establishedDate;

        @ApiModelProperty("业务范围:货架/阁楼/播种墙,可多选")
        private List<String> businessScope;

        @Size(max = 255)
        @ApiModelProperty("公司地址")
        private String address;

        @Size(max = 64)
        @ApiModelProperty("主要联系人")
        private String contact;

        @Size(max = 32)
        @ApiModelProperty("主要电话")
        private String phone;

        @Size(max = 255)
        @ApiModelProperty("备注")
        private String remark;
    }

    /** 判定 / 改判考察结果 */
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
        private Integer sortNo;
        private String companyName;
        private String legalPerson;
        /** 注册资本(万元·原文) */
        private String registeredCapitalWan;
        private LocalDate establishedDate;
        private List<String> businessScope;
        private String address;
        private String contact;
        private String phone;
        private String result;
        private String conclusion;
        private String decidedByName;
        private LocalDateTime decidedAt;
        /** 合格时关联的供应商 */
        private Long supplierId;
        private String supplierName;
        private String supplierStatus;
        /** 考察记录压缩包数量 */
        private Integer archiveCount;
        /** 考察记录压缩包文件名(导出用) */
        private List<String> archiveNames = new ArrayList<>();
        private String remark;
        private LocalDateTime createTime;
    }

    /** 判定结果回执 */
    @Data
    public static class DecideResult {
        private String result;
        private Long supplierId;
        /** true=新建了供应商;false=关联到已有同名供应商;null=不合格(未关联) */
        private Boolean supplierCreated;
        private String message;
    }

    /** Excel 导入回执 */
    @Data
    public static class ImportResult {
        /** 数据行数(不含表头/空行) */
        private int total;
        private int created;
        private int updated;
        /** 本次结果被设为/改为 合格 */
        private int passed;
        /** 本次结果被设为/改为 不合格 */
        private int failed;
        /** 跳过的行(缺公司名称/表内重复) */
        private int skipped;
        private List<RowMessage> messages = new ArrayList<>();
    }

    @Data
    public static class RowMessage {
        /** Excel 行号(从 1 起,含表头行) */
        private int row;
        private String companyName;
        /** 提示级别:info/warn */
        private String level;
        private String message;

        public RowMessage() {
        }

        public RowMessage(int row, String companyName, String level, String message) {
            this.row = row;
            this.companyName = companyName;
            this.level = level;
            this.message = message;
        }
    }
}
