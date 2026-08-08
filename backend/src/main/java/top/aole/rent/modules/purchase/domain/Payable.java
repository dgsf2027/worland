package top.aole.rent.modules.purchase.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 应付计划(首付/验收/尾款 · M1-13)。
 *
 * <p><b>负债口径</b>:{@code status=待付} 的 payable 即层级②"负债"(M3 兑付缺口 T-N 扫描读 due_date/amount);
 * 退货红冲插"退款红字"负数行并把原行置 {@code 红冲}(不计入负债)。
 */
@Data
@TableName("yc_rent_payable")
public class Payable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long purchaseInId;

    /** 阶段:首付/验收/尾款/退款红字 */
    private String stage;

    private LocalDate dueDate;

    /** 应付金额(元·退款红字为负) */
    private BigDecimal amount;

    /** 状态:待付/已付/红冲 */
    private String status;

    private LocalDate paidDate;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
