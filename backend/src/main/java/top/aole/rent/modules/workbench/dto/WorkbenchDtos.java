package top.aole.rent.modules.workbench.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工作台聚合 DTO(WT-01)。各角色待办 + 亮灯红点 + 老板驾驶舱 KPI,按角色投影可见。
 */
public class WorkbenchDtos {

    @Data
    public static class Workbench {
        private String role;
        private String userName;
        private String scopeNote;
        /** 老板驾驶舱 KPI(按角色投影·不可见项为 null) */
        private Kpi kpi;
        /** 亮灯红点(按角色过滤) */
        private List<RedPoint> redPoints;
        /** 我的待办(按角色/人过滤) */
        private List<TaskBrief> myTasks;
        private int myTaskCount;
    }

    /** 老板驾驶舱 KPI。可见性:在租率(除LP)、应收/本月分配(老板/财务)、加权回报(老板/财务/LP)。 */
    @Data
    public static class Kpi {
        private BigDecimal rentedRate;       // 在租率 = 在租数 / 有效设备数
        private Integer rentedCount;
        private Integer activeAssetCount;    // 有效设备(排除已转让/报废)
        private BigDecimal receivableTotal;  // 应收合计(元·敏感)
        private BigDecimal monthDistribution;// 本月分配(distributable·敏感)
        private BigDecimal weightedReturn;   // 加权回报(总税后 IRR)
        private String period;               // 本月(yyyy-MM)
    }

    /** 亮灯红点。 */
    @Data
    public static class RedPoint {
        private String key;    // overdue/idle/coverage_gap/tax/contract_expiry
        private String label;
        private int count;
        private String level;  // 正常/预警/超限/红灯
        private String link;   // 前端路由
    }

    /** 待办摘要。 */
    @Data
    public static class TaskBrief {
        private Long id;
        private String no;
        private String title;
        private String type;
        private String source;
        private String status;
        private String priority;
        private Boolean overdue;
        private String dueDate;
    }
}
