package top.aole.rent.modules.asset.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 配件 BOM 明细 Excel 导入出参(列与合同清单一致,另带 BOM 自己的字段)。 */
public final class BomSheetDtos {

    private BomSheetDtos() {
    }

    @Data
    public static class Row {
        private Integer seq;
        /** 上级序号;空=一级总成 */
        private Integer parentSeq;
        private String name;
        private String model;
        private String spec;
        private String unit;
        private BigDecimal qty;
        private BigDecimal unitCost;
        /** 金额;与 数量×单价 不一致时按手动合价存 */
        private BigDecimal amount;
        private Boolean amountManual;
        private String supplierName;
        private Long supplierId;
        private BigDecimal lifeYears;
        private LocalDate warrantyUntil;
        private Boolean repairable;
        private Integer faultCount;
        /** 残值率(0-1) */
        private BigDecimal residualRate;
        private String remark;
        /** 解析阶段的提示 */
        private List<String> warnings = new ArrayList<>();
    }

    @Data
    public static class ImportResult {
        private int total;
        private int imported;
        private int skipped;
        private BigDecimal bomTotal;
        private List<String> messages = new ArrayList<>();
    }
}
