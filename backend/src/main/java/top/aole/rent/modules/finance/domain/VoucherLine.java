package top.aole.rent.modules.finance.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 凭证分录行(M3-01)。借贷方向 {@code direction}=dr借/cr贷,{@code amount} 恒正(方向由 direction 表达)。
 * 借贷平衡:同凭证 Σ(dr.amount)=Σ(cr.amount)。
 */
@Data
@TableName("yc_rent_voucher_line")
public class VoucherLine {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long voucherId;

    private String accountCode;

    private String accountName;

    /** dr 借 / cr 贷 */
    private String direction;

    private BigDecimal amount;

    private String remark;

    private LocalDateTime createTime;

    private Integer isDeleted;
}
