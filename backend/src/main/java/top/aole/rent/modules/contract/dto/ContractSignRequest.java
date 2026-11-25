package top.aole.rent.modules.contract.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 签约入参(录合同要素 → 挂 N 台设备 → 自动生成租金计划 N 期)。
 *
 * <p>性质固定"分期收款销售",禁"融资租赁"(服务端校验)。
 * {@code activate=true}(默认) 时执行完整签约(设备转在租 + 生成计划 + 押金台账);false 仅存草稿。
 */
@Data
public class ContractSignRequest {

    @NotBlank(message = "合同编号必填")
    private String no;

    @NotNull(message = "客户必填")
    private Long customerId;

    /** 租期(月);缺省按品类 term_months 或设备品类推断 */
    private Integer termMonths;

    /** 月租合计;缺省=Σ assets.allocRent */
    private BigDecimal monthRent;

    /** 押金;缺省=月租×押金月数(rule_config deposit_months) */
    private BigDecimal deposit;

    private BigDecimal endTransferPrice;

    private BigDecimal targetIrr;

    /** 名义年化利率(%,如 20.00) */
    private BigDecimal leaseAnnualRate;

    /** 计息口径:a(扣残值等额本息)/b(全额等额本息)/c(平息) */
    private String interestMode;

    /** 最终成交价(含税·元,可从报价器带入) */
    private BigDecimal finalDealPrice;

    /** 整单优惠/抹零金额(元) */
    private BigDecimal boqDiscountAmount;

    /** 合同税率(0-1,如 0.13) */
    @DecimalMin("0")
    @DecimalMax("1")
    private BigDecimal taxRate;

    /** 销项专票税率(0-1,默认 0.03) */
    private BigDecimal invoiceTaxRate;

    /** 收款后开票期限(工作日,默认 10) */
    private Integer invoiceDeadlineDays;

    /** 逾期日违约金率(默认 0.0005) */
    private BigDecimal overdueDailyRate;

    /** 合同性质(分期收款销售 / 设备融资租赁) */
    private String nature;

    private LocalDate signDate;

    /** 起租日(默认签约日/今天) */
    private LocalDate startDate;

    /** 每月固定付款日(1-31,如 15) */
    private Integer payDayOfMonth;

    /** 首期付款日(如 2026-11-15) */
    private LocalDate firstPayDate;

    /** 可选:直接透传逐期租金计划(含本金/利息/期初与期末本金余额,报价器一键转合同时使用) */
    private List<ScheduleInput> scheduleItems;

    private String remark;

    /** 是否立即生效签约(默认 true);false=仅存草稿不产生副作用 */
    private Boolean activate;

    /** 挂载设备(至少 1 台;设备须已存在) */
    @NotNull(message = "至少挂 1 台设备")
    private List<AssetLink> assets;

    @Data
    public static class AssetLink {
        @NotNull(message = "设备主键必填")
        private Long assetId;
        /** 单台月租分摊;缺省=月租均摊 */
        private BigDecimal allocRent;
    }

    @Data
    public static class ScheduleInput {
        private Integer periodNo;
        private LocalDate dueDate;
        private BigDecimal amount;
        private BigDecimal openingPrincipal;
        private BigDecimal interestAmount;
        private BigDecimal principalAmount;
        private BigDecimal closingPrincipal;
    }
}
