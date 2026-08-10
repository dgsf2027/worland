package top.aole.rent.modules.roster.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 花名册/提成 DTO 容器(M5-03)。
 */
public class RosterDtos {

    /** 花名册行(角色权限矩阵)。 */
    @Data
    public static class RosterItem {
        private Long id;
        private Long userId;
        private String userName;
        private String role;
        private String dataScope;
        private Boolean costVisible;
        private Boolean ownerScoped;
        private Long projectId;
        private Boolean active;
        private String remark;
    }

    /** 角色权限改动请求(限老板+入 audit)。 */
    @Data
    public static class RoleUpdateRequest {
        private String role;
        private String dataScope;
        private Boolean costVisible;
        private Boolean ownerScoped;
        private Long projectId;
        private Boolean active;
        private String remark;
    }

    /** 提成明细行。 */
    @Data
    public static class CommissionLine {
        private Long id;
        private String period;
        private Long userId;
        private String userName;
        private String role;
        private String type;
        private BigDecimal baseAmount;
        private BigDecimal rate;
        private BigDecimal commissionAmount;
        private String fundedFrom;
        private String sourceRef;
        private Integer orderCount;
        private LocalDateTime createTime;
    }

    /** 某员工某期提成拆解汇总。 */
    @Data
    public static class CommissionSummary {
        private String period;
        private Long userId;
        private String userName;
        private String role;
        /** 集采降本:单数/降本额/提成 */
        private int costCutOrders;
        private BigDecimal costCutBase = BigDecimal.ZERO;
        private BigDecimal costCutCommission = BigDecimal.ZERO;
        /** 成交贡献:单数/成交额/提成 */
        private int dealOrders;
        private BigDecimal dealBase = BigDecimal.ZERO;
        private BigDecimal dealCommission = BigDecimal.ZERO;
        /** 合计提成(从管理费列支) */
        private BigDecimal totalCommission = BigDecimal.ZERO;
        private List<CommissionLine> lines;
    }

    /** 提成计提结果。 */
    @Data
    public static class ComputeResult {
        private String period;
        private int costCutRows;
        private int dealRows;
        private int skipped;
        private BigDecimal totalCommission = BigDecimal.ZERO;
    }
}
