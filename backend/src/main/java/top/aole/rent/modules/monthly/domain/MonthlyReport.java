package top.aole.rent.modules.monthly.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 月度报表快照(yc_rent_monthly_report):一 period 一条。
 * package_json=六件套聚合;analysis_json=七节;calendar_status=财务日历状态机(DESIGN_DOC §4.3)。
 */
@Data
@TableName("yc_rent_monthly_report")
public class MonthlyReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String period;

    private String packageJson;

    private String analysisJson;

    private String calendarStatus;

    private Long llmCallId;

    private String generatedBy;

    private LocalDateTime generatedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
