package top.aole.rent.modules.automation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("yc_rent_automation_log")
public class AutomationLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ruleKey;
    private String ruleName;
    /** CRON / MANUAL / EVENT */
    private String triggerSource;
    /** SUCCESS / SKIPPED / FAILED */
    private String status;
    private Integer affectedCount;
    private Integer pushedCount;
    private String summary;
    private String detailText;
    private Long durationMs;
    private LocalDateTime executedAt;
}
