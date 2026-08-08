package top.aole.rent.modules.billing.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 逾期案(三步走 · M2-05)。延期→罚息→锁机→收回→关闭;还款恢复关闭。
 * 内外一视同仁,物权在我方。每案必"裁决人 owner + 期限 deadline"。
 */
@Data
@TableName("yc_rent_overdue_case")
public class OverdueCase {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long rentBillId;

    private Long contractId;

    /** 延期/罚息/锁机/收回/关闭 */
    private String step;

    /** 开启/关闭 */
    private String status;

    private BigDecimal penaltyAmount;

    private String nextAction;

    private LocalDate deadline;

    private String owner;

    private LocalDateTime openedAt;

    private LocalDateTime closedAt;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
