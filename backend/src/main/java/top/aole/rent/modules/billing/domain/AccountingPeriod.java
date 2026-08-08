package top.aole.rent.modules.billing.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 期间锁(S0-02 建表 · M2 首次消费)。落 is_locked 的期间,红冲/凭证写一律拒绝,只走上期调整(P0-F 锁账守卫)。
 */
@Data
@TableName("yc_rent_accounting_period")
public class AccountingPeriod {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会计期间 YYYY-MM */
    private String period;

    /** 账套口径:tax 税务 / ops 经营 */
    private String book;

    private Integer isLocked;

    private Long lockedBy;

    private LocalDateTime lockedAt;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
