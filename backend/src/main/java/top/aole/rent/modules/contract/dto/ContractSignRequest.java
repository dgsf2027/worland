package top.aole.rent.modules.contract.dto;

import lombok.Data;

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

    /** 合同性质(默认分期收款销售;禁融资租赁) */
    private String nature;

    private LocalDate signDate;

    /** 起租日(默认签约日/今天) */
    private LocalDate startDate;

    private String remark;

    /** 是否立即生效签约(默认 true);false=仅存草稿不产生副作用 */
    private Boolean activate;

    /** 挂载设备(至少 1 台;设备须已存在——先签约后采购挂已建档设备) */
    @NotNull(message = "至少挂 1 台设备")
    private List<AssetLink> assets;

    @Data
    public static class AssetLink {
        @NotNull(message = "设备主键必填")
        private Long assetId;
        /** 单台月租分摊;缺省=月租均摊 */
        private BigDecimal allocRent;
    }
}
