package top.aole.rent.modules.billing.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 收租单(收款唯一真相源 · M2-01/02/03)。
 *
 * <p>§4.24 单一真相源:{@code status}(待收/已核销/逾期/红冲)为收款态 owner,
 * {@code rent_schedule.plan_status} 单向回写(未到期/已生成单)。
 * <p>P0-F:{@code reverses_id} 唯一约束 = 红冲幂等键;红冲=负额新行,原单转红冲,单事务原子。
 */
@Data
@TableName("yc_rent_rent_bill")
public class RentBill {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String billNo;

    private Long contractId;

    private Integer periodNo;

    private LocalDate dueDate;

    private BigDecimal amount;

    private BigDecimal receivedAmount;

    /** 待收/已核销/逾期/红冲 */
    private String status;

    /** 正常/红冲/退款/罚息 */
    private String billKind;

    private LocalDateTime matchedAt;

    /** 记账期 YYYY-MM(核销时=matched 月·锁账守卫基准) */
    private String accountPeriod;

    /** 红冲指向的原单 id(幂等键·唯一约束) */
    private Long reversesId;

    /** 退款指向的原核销单 id */
    private Long refBillId;

    /** 【M3 钩子·预留】收入凭证 id(M2 恒 NULL) */
    private Long voucherId;

    private Long operatorId;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
