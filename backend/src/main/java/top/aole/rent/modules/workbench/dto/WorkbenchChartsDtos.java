package top.aole.rent.modules.workbench.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 工作台图表专用投影。仅返回聚合量，不携带客户卡片、提醒明细或投资人资金信息。 */
public final class WorkbenchChartsDtos {

    private WorkbenchChartsDtos() {
    }

    @Data
    public static class Charts {
        private int months;
        private Resource<List<CustomerStage>> customerStages;
        private Resource<InventorySummary> inventory;
        private Resource<RentTrend> rentTrend;
        private Resource<CashflowSummary> cashflow;
    }

    /** restricted/error 必须显式返回 data:null，不能伪装成全零数据。 */
    @Data
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Resource<T> {
        private String status; // ready / restricted / error
        private T data;
        private String source;
    }

    @Data
    public static class CustomerStage {
        private String phase;
        private Integer count;
    }

    @Data
    public static class InventorySummary {
        private int itemCount;
        private int activeRentalCount;
        private int overdueRentalCount;
    }

    @Data
    public static class RentTrend {
        private List<RentTrendPoint> points;
    }

    @Data
    public static class RentTrendPoint {
        private String period;
        private BigDecimal receivable;
        private BigDecimal collected;
    }

    @Data
    public static class CashflowSummary {
        private LocalDate asOf;
        private Bucket receivable;
        private Bucket payable;
        private List<ForecastPoint> forecast;
    }

    @Data
    public static class Bucket {
        private BigDecimal within1Year;
        private BigDecimal beyond1Year;
        private BigDecimal total;
        private int count;
    }

    @Data
    public static class ForecastPoint {
        private String period;
        private BigDecimal inflow;
        private BigDecimal outflow;
        private BigDecimal net;
    }
}
