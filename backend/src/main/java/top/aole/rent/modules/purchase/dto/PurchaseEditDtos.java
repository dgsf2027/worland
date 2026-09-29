package top.aole.rent.modules.purchase.dto;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 采购单编辑请求集(整单可编辑)。
 *
 * <p>试跑阶段需要把已录的采购单改对,所以单头/明细/应付三块都开放编辑。
 * 门禁只保留两条硬约束:已红冲的单只读;已付款的应付不能直接删(要先撤销付款)。
 */
public final class PurchaseEditDtos {

    private PurchaseEditDtos() {
    }

    /** 单头编辑:传进来的字段才改,null 表示不动(单号除外,单号为空视为不改)。 */
    @Data
    public static class HeaderRequest {
        @ApiModelProperty(value = "采购单号(改动时查重)", example = "CG-2026-001")
        private String no;

        @ApiModelProperty("整机供应商")
        private Long supplierId;

        @ApiModelProperty(value = "下单日", example = "2026-03-01")
        private LocalDate orderDate;

        @ApiModelProperty(value = "预计入库日(改了会重算本单未入库阶段的预估到期日)", example = "2026-04-01")
        private LocalDate expectReceiveDate;

        @ApiModelProperty(value = "入库日(仅已入库的单可改;改了会重算已兑现的到期日)", example = "2026-03-20")
        private LocalDate receiveDate;

        private String remark;
    }

    /** 明细整体替换:勾选该合同下的台账设备。 */
    @Data
    public static class ItemsRequest {
        @NotEmpty(message = "至少 1 台设备")
        @ApiModelProperty("本单采购的设备 id 列表(整体替换;移除已付款的设备会被拒)")
        private List<Long> assetIds;
    }

    /** 单条应付编辑:传进来的字段才改。 */
    @Data
    public static class PayableRequest {
        @ApiModelProperty("付款阶段名(留空不改)")
        private String stage;

        @ApiModelProperty("应付金额(改了会标记为手工调整,后续重算不再覆盖本行)")
        private BigDecimal amount;

        @ApiModelProperty("到期日")
        private LocalDate dueDate;

        @ApiModelProperty("到期日是否为预估(手工改到期日时一般置 false)")
        private Boolean dueProvisional;

        private String remark;
    }

    /** 登记付款:实付小于应付时自动拆成「已付 + 待付差额」两行。 */
    @Data
    public static class PayRequest {
        @NotNull(message = "实付金额必填")
        @ApiModelProperty(value = "实付金额(可小于应付,差额自动留一行待付)", example = "65710.80")
        private BigDecimal paidAmount;

        @ApiModelProperty(value = "实付日期(空=今天)", example = "2026-03-05")
        private LocalDate paidDate;

        private String remark;
    }
}
