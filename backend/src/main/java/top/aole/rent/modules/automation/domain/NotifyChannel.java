package top.aole.rent.modules.automation.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("yc_rent_notify_channel")
public class NotifyChannel {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    /** wecom / dingtalk / feishu / generic */
    private String channelType;
    private String webhookUrl;
    private String secret;
    /** 老板 / 财务 / 业务 / 全员 */
    private String targetRole;
    private Integer enabled;
    private LocalDateTime lastSentAt;
    private String lastError;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer isDeleted;
}
