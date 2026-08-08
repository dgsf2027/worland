package top.aole.rent.modules.rule.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 版本化规则配置项(S0-05)。口径常量单一真相源。
 */
@Data
@TableName("yc_rent_rule_config")
public class RuleConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 规则键,如 tax_vat / transfer_rate / speed_coeff / mgmt_fee_ladder */
    private String ruleKey;

    /** 限定维度(品类/客户类型/档位),无维度=空串 */
    private String scopeKey;

    /** 标量值(比率/金额/月数) */
    private BigDecimal ruleValue;

    /** 结构化值(如管理费阶梯 JSON) */
    private String ruleJson;

    /** 值类型:rate/money/months/json */
    private String valueType;

    private Integer version;

    private LocalDate effectiveFrom;

    private LocalDate effectiveTo;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;
}
