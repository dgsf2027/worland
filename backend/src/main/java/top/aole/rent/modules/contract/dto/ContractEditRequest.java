package top.aole.rent.modules.contract.dto;

import lombok.Data;

import javax.validation.constraints.DecimalMax;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Digits;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 合同要素编辑。草稿合同全部生效;生效合同中已生成收租单的期次不动,其余期次按新要素重排,押金差额自动补收/退回。
 */
@Data
public class ContractEditRequest {

    @NotNull(message = "客户必填")
    private Long customerId;

    /** 合同性质(固定分期收款销售,禁融资租赁) */
    private String nature;

    @DecimalMin("0") @DecimalMax("1")
    @Digits(integer = 1, fraction = 8)
    private BigDecimal targetIrr;

    @NotNull(message = "租期必填")
    @Min(value = 1, message = "租期至少 1 个月")
    private Integer termMonths;

    @NotNull(message = "月租必填")
    @DecimalMin("0") @Digits(integer = 16, fraction = 2)
    private BigDecimal monthRent;

    @DecimalMin("0") @Digits(integer = 16, fraction = 2)
    private BigDecimal endTransferPrice;

    @DecimalMin("0") @Digits(integer = 16, fraction = 2)
    private BigDecimal deposit;

    @NotNull(message = "起租日必填")
    private LocalDate startDate;

    private LocalDate signDate;

    @Size(max = 255)
    private String remark;

    /** 变更说明(留痕) */
    @Size(max = 255)
    private String detail;
}
