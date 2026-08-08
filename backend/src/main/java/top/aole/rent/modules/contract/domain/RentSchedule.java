package top.aole.rent.modules.contract.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 租金计划(应收计划·逐期明细行 · M1-17)。
 *
 * <p>⚠ 只计划态({@code plan_status}=未到期/已生成单);收款/逾期/红冲态归 {@code rent_bill}(M2 收款唯一真相源),
 * 本表 {@code rent_bill_id} 回填(§4.24 单一真相源)。
 */
@Data
@TableName("yc_rent_rent_schedule")
public class RentSchedule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long contractId;

    private Integer periodNo;

    private LocalDate dueDate;

    private BigDecimal amount;

    private Long rentBillId;

    /** 计划态:未到期/已生成单 */
    private String planStatus;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
