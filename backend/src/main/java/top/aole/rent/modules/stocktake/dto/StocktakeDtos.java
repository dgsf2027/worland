package top.aole.rent.modules.stocktake.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 盘点模块 DTO 集(M4-05)。
 */
public class StocktakeDtos {

    @Data
    public static class CreateRequest {
        /** 盘点范围(全量/品类名/项目);空=全量 */
        private String scope;
        private String remark;
    }

    /** 扫码盘点提交:只录差异(账面带出,扫到与账面不符的才录)。 */
    @Data
    public static class ScanRequest {
        private List<DiffInput> diffs;
    }

    @Data
    public static class DiffInput {
        private Long assetId;
        /** 实盘状态(扫码录入;丢失/盘亏 → 盘亏) */
        private String actualStatus;
        /** 可选:显式差异类型(盘盈/盘亏/状态不符);空则服务端推断 */
        private String diffType;
        private String remark;
    }

    @Data
    public static class StocktakeItem {
        private Long id;
        private String no;
        private String scope;
        private String status;
        private Integer bookCount;
        private Integer scannedCount;
        private Integer diffCount;
        private LocalDateTime bizTime;
        private LocalDateTime closedAt;
        private String remark;
    }

    @Data
    public static class DiffItem {
        private Long id;
        private Long assetId;
        private String serialNo;
        private String bookStatus;
        private String actualStatus;
        private String diffType;
        private Boolean adjusted;
        private Long adjustEventId;
        private String remark;
    }

    @Data
    public static class StocktakeDetail {
        private StocktakeItem stocktake;
        private List<DiffItem> diffs;
    }

    /** 闭合结果:逐差异生成盘盈亏调整单(走单据不直改台账)。 */
    @Data
    public static class CloseResult {
        private Long stocktakeId;
        private String status;
        private Integer adjusted;
        private Integer profitCount;   // 盘盈
        private Integer lossCount;     // 盘亏
        private Integer mismatchCount; // 状态不符
        private List<String> impact;
    }
}
