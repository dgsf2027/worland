package top.aole.rent.modules.asset.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 空置亮灯(M1-14 · 流程6)。回收待处置 + 投放超 N 天未起租的设备清单(老板驾驶舱"空置"红点数据源)。
 */
@Data
public class IdleAlertResponse {

    /** 亮灯阈值(天·rule idle_alert_days) */
    private Integer thresholdDays;

    /** 亮灯设备数(驾驶舱红点计数) */
    private Integer alertCount;

    private List<IdleItem> items;

    @Data
    public static class IdleItem {
        private Long assetId;
        private String serialNo;
        private String category;
        private String model;
        private String status;
        /** 亮灯原因:投放超期未起租 / 收回待处置 */
        private String reason;
        /** 空置天数(自投放/收回事件起算) */
        private Integer idleDays;
        /** 空置起算日 */
        private LocalDate sinceDate;
        /** 承压残值(市场价×品类转让率·处置回收参考) */
        private java.math.BigDecimal residualValue;
    }
}
