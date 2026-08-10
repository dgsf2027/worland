package top.aole.rent.modules.pdca.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * PDCA 改进项(M5-05 · DESIGN §4.4 action_item)。每指标红绿灯 → 改进措施 → 到期回查唯一落点。
 *
 * <p>结构化验证指标(metricKey + targetValue + compareOp)到期自动回查:取指标当前值 vs 目标 →
 * 通过关闭 / 未达升级 / 取不到需人工判定。登记时自动取 baselineValue(改进前起点)。
 */
@Data
@TableName("yc_rent_action_item")
public class ActionItem {

    public static final String ST_OPEN = "进行中";
    public static final String ST_PASSED = "验证通过";
    public static final String ST_ESCALATED = "未见效升级";
    public static final String ST_CLOSED = "已关闭";

    public static final String OP_GE = ">=";
    public static final String OP_LE = "<=";

    public static final String VR_PASS = "通过";
    public static final String VR_FAIL = "未达";
    public static final String VR_MANUAL = "需人工判定";

    @TableId(type = IdType.AUTO)
    private Long id;

    private String no;

    /** 来源指标环节:在租率/加权回报/应收账龄/资产周转/回款率 */
    private String metricScene;

    private String issue;

    private String action;

    /** 验证指标键(PdcaMetricService 注册表);NULL=需人工判定 */
    private String metricKey;

    private String metricParam;

    private BigDecimal targetValue;

    /** 比较方向:>=(越高越好)/<=(越低越好) */
    private String compareOp;

    private BigDecimal baselineValue;

    private BigDecimal verifyValue;

    private String verifyResult;

    private String verifyNote;

    private LocalDateTime verifiedAt;

    private LocalDate recheckDate;

    private String ownerRole;

    private Long ownerUserId;

    private String ownerUserName;

    private String status;

    private Long taskId;

    private Integer aiDraft;

    private Long llmCallId;

    private Long creatorId;

    private String creatorName;

    private Long projectId;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
