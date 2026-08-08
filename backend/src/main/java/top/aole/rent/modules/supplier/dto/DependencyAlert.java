package top.aole.rent.modules.supplier.dto;

import lombok.Data;

import java.util.List;

/**
 * 单一依赖预警:某品类可用供应商(入库/主供/备供)不足下限则亮灯。
 */
@Data
public class DependencyAlert {

    /** 下限(来自 rule_config[supplier_min_per_category]) */
    private Integer minPerCategory;

    /** 存在风险的品类清单 */
    private List<CategoryRisk> risks;

    @Data
    public static class CategoryRisk {
        private String category;
        /** 当前可用家数(入库/主供/备供) */
        private Integer available;
        private String message;
    }
}
