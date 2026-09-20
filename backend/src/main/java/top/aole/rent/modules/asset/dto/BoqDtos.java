package top.aole.rent.modules.asset.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 合同清单(《工程量清单计价表》格式)出入参。金额均为含税价,税额按设备合同税率拆出。
 */
public final class BoqDtos {

    private BoqDtos() {
    }

    @Data
    public static class Line {
        private Long id;
        private Integer seq;
        private String name;
        private String model;
        private String spec;
        private String unit;
        private BigDecimal qty;
        /** 单价(含税) */
        private BigDecimal unitPrice;
        /** 金额(含税);空 = 表格里的「-」(赠送/不计价) */
        private BigDecimal amount;
        /** true=金额手填(赠送/优惠行),false=数量×单价自动算 */
        private Boolean amountManual;
        private String remark;
    }

    /** 整表保存:带 id 的更新,不带 id 的新增,表里没有的删除 */
    @Data
    public static class SaveRequest {
        private List<Line> lines = new ArrayList<>();
    }

    @Data
    public static class Boq {
        private List<Line> lines = new ArrayList<>();
        /** 合计(含税) = Σ 金额 */
        private BigDecimal totalWithTax;
        /** 不含税金额 = 含税合计 / (1 + 税率) */
        private BigDecimal totalWithoutTax;
        /** 税额 = 含税合计 - 不含税金额 */
        private BigDecimal taxAmount;
        /** 合同税率(0-1) */
        private BigDecimal taxRate;
        /** 合计大写(人民币) */
        private String totalUpper;
        /** 合同价是否已与合同清单联动(清单有行时为 true) */
        private Boolean linked;
    }

    /** Excel 导入回执 */
    @Data
    public static class ImportResult {
        private int total;
        private int imported;
        private int skipped;
        private BigDecimal totalWithTax;
        private List<String> messages = new ArrayList<>();
    }
}
