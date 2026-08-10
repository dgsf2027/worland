package top.aole.rent.modules.task.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 任务(M5-01)。绑角色绑人、系统派单/手动派单、转派留痕(transfer_log 只追加)、完成需校验(verify_evidence)。
 *
 * <p><b>单一真相源(§4.24)</b>:任务是"事"的载体;系统任务(逾期/兑付缺口/到期跟进)由 {@code TaskGenService} 自动派,
 * 幂等键 = biz_type+biz_id(同一业务对象只开一张系统任务);verify_required=1 的任务缺证据拒绝置「已完成」。
 */
@Data
@TableName("yc_rent_task")
public class Task {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String no;

    private String title;

    /** 集采比价/BOM/逾期跟进/催收/合同到期跟进/跟进待办/兑付缺口/投放审批/通用 */
    private String type;

    /** 承接角色:老板/财务/供应链/业务 */
    private String assigneeRole;

    private Long assigneeUserId;

    private String assigneeUserName;

    /** 系统/派单 */
    private String source;

    /** 待开始/进行中/已完成/已作废/超时 */
    private String status;

    /** 高/中/低 */
    private String priority;

    /** 关联业务对象类型:overdue_case/contract/purchase_in/coverage_gap */
    private String bizType;

    private Long bizId;

    private LocalDate dueDate;

    /** 完成是否需校验证据(如 BOM 需上传泵表) */
    private Integer verifyRequired;

    private String verifyEvidence;

    /** 转派留痕 JSON 数组(只追加) */
    private String transferLog;

    private Long creatorId;

    private String creatorName;

    private LocalDateTime finishedAt;

    private String remark;

    private Long projectId;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
