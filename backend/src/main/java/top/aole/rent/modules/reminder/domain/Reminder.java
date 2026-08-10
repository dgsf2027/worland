package top.aole.rent.modules.reminder.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 到期提醒(yc_rent_reminder):cron 扫描落库。
 * type=contract_expiry(合同到期前30天转让提醒) / followup_due(潜客下次跟进到期)。
 * 幂等:同 type+ref_id+due_date 只留一条(cron 重复跑不重复插)。
 */
@Data
@TableName("yc_rent_reminder")
public class Reminder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String type;

    private Long refId;

    private String refNo;

    private String title;

    private LocalDate dueDate;

    private Integer daysLeft;

    private String ownerRole;

    private String status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
