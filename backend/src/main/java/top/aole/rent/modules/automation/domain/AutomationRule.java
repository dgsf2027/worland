package top.aole.rent.modules.automation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("yc_rent_automation_rule")
public class AutomationRule {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String ruleKey;
    private String ruleName;
    private String category;
    private String scheduleDesc;
    private Integer enabled;
    private Integer leadDays;
    private BigDecimal thresholdAmount;
    private String targetRole;
    private String description;
    private LocalDateTime lastRunAt;
    private String lastStatus;
    private String lastSummary;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer isDeleted;
}
