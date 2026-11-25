package top.aole.rent.modules.contract.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 合同主表(不可撤销长约 · M1-10)。性质固定"分期收款销售",禁"融资租赁"。
 *
 * <p>签约自动生成 {@code rent_schedule} N 期;勾稽=期数×月租+转让价=客户总付(不含押金)。
 * 变更/作废走 {@code contract_change} 红冲留痕。
 */
@Data
@TableName("yc_rent_contract")
public class Contract {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String no;

    private Long customerId;

    /** 租期(月·=租金计划期数 N) */
    private Integer termMonths;

    /** 月租合计(元;=Σ contract_asset.alloc_rent) */
    private BigDecimal monthRent;

    private BigDecimal deposit;

    /** 期末转让价(元;计入客户总付,不含押金) */
    private BigDecimal endTransferPrice;

    private BigDecimal targetIrr;

    /** 名义年化利率(%,如 20.00) */
    private BigDecimal leaseAnnualRate;

    /** 计息口径:a/b/c */
    private String interestMode;

    /** 合同税率(0-1);合同清单金额为含税价 */
    private BigDecimal taxRate;

    /** 销项专票税率(0-1,默认 0.03) */
    private BigDecimal invoiceTaxRate;

    /** 收款后开票期限(工作日,默认 10) */
    private Integer invoiceDeadlineDays;

    /** 逾期日违约金率(默认 0.0005) */
    private BigDecimal overdueDailyRate;

    /** 设备总价(含税)= 合同清单原价合计扣除整单优惠后的最终成交价 */
    private BigDecimal equipmentTotal;

    /** 清单整单优惠/抹零金额(元) */
    private BigDecimal boqDiscountAmount;

    /** 最终成交价(含税·元) */
    private BigDecimal finalDealPrice;

    /** 合同性质(分期收款销售 / 设备融资租赁) */
    private String nature;

    /** 状态:草稿/生效/到期转让/关闭/已作废 */
    private String status;

    private LocalDate signDate;

    /** 起租日(=租金计划首期起算) */
    private LocalDate startDate;

    /** 每月固定付款日(1-31,如 15) */
    private Integer payDayOfMonth;

    /** 首期付款日(如 2026-11-15) */
    private LocalDate firstPayDate;

    private Long projectId;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
