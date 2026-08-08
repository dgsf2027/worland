package top.aole.rent.modules.customer.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 销售管道看板:按阶段分组 + 加权预测(Σ 商机额×成交概率)。
 */
@Data
public class PipelineResponse {

    /** 加权预测新签额(元)= Σ open 商机 est×prob */
    private BigDecimal weightedForecast;

    private List<Column> columns;

    @Data
    public static class Column {
        private String phase;
        private Integer count;
        private List<Card> cards;
    }

    @Data
    public static class Card {
        private Long customerId;
        private String name;
        private String ownerName;
        /** 商机额/在租额(元);敏感,按角色投影 */
        private BigDecimal amount;
        private String tag;
    }
}
