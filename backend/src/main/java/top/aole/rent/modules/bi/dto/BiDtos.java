package top.aole.rent.modules.bi.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * BI 多维矩阵返回体(M5-04 · 业务流§8.4)。四指标(在租率/加权回报/应收账龄/资产周转)
 * 统一「总览 overall + 下钻维度 rows」结构;账龄另带分桶。趋势独立 Trend。
 */
public class BiDtos {

    /** 单指标多维矩阵:总览 + 按维度下钻行 */
    @Data
    public static class MatrixResp {
        private String metric;
        private String metricLabel;
        private String dimension;
        private String unit;
        /** 总览值(全量口径) */
        private BigDecimal overall;
        private String overallDesc;
        private List<MatrixRow> rows = new ArrayList<>();
        /** 口径说明 + 数据源(1秒懂·§4.12) */
        private String source;
    }

    /** 下钻行(某维度取值下的指标) */
    @Data
    public static class MatrixRow {
        private String dimKey;
        private BigDecimal value;
        /** 分子(如在租数/在租设备) */
        private BigDecimal numerator;
        /** 分母(如可投放总数) */
        private BigDecimal denominator;
        /** 权重(加权回报的权重=月租) */
        private BigDecimal weight;
        private String light;
        private String note;

        public MatrixRow() {
        }

        public MatrixRow(String dimKey) {
            this.dimKey = dimKey;
        }
    }

    /** 应收账龄:分桶 */
    @Data
    public static class AgingResp {
        private String dimension;
        private BigDecimal totalOverdue;
        /** 分桶:0-30 / 31-60 / 61-90 / 90+ */
        private List<AgingBucket> buckets = new ArrayList<>();
        /** 下钻(按客户) */
        private List<MatrixRow> rows = new ArrayList<>();
        private String source;
    }

    @Data
    public static class AgingBucket {
        private String bucket;
        private BigDecimal amount;
        private Integer count;

        public AgingBucket(String bucket) {
            this.bucket = bucket;
            this.amount = BigDecimal.ZERO;
            this.count = 0;
        }
    }

    /** 趋势(近 N 月) */
    @Data
    public static class TrendResp {
        private String metric;
        private String metricLabel;
        private String unit;
        private List<TrendPoint> points = new ArrayList<>();
        private String source;
    }

    @Data
    public static class TrendPoint {
        private String period;
        private BigDecimal value;

        public TrendPoint(String period, BigDecimal value) {
            this.period = period;
            this.value = value;
        }
    }
}
