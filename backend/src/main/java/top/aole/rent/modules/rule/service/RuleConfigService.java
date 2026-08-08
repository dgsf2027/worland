package top.aole.rent.modules.rule.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.rule.domain.RuleConfig;
import top.aole.rent.modules.rule.mapper.RuleConfigMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 规则配置取值服务(S0-05)。按业务日期取生效版本 —— 报价/账务口径的唯一取数入口,禁硬编码。
 *
 * <p>生效判定:effective_from ≤ bizDate 且 (effective_to 为空 或 effective_to ≥ bizDate);
 * 若命中多条(理论不应),取 version 最大。税率跨 sunset(2027-12-31)命中不同版本。
 */
@Service
@RequiredArgsConstructor
public class RuleConfigService {

    private final RuleConfigMapper mapper;

    /** 取生效规则项;找不到抛业务异常(配置缺失应显式暴露,不静默兜 0)。 */
    public RuleConfig getEffective(String ruleKey, String scopeKey, LocalDate bizDate) {
        String scope = scopeKey == null ? "" : scopeKey;
        List<RuleConfig> all = mapper.selectList(new LambdaQueryWrapper<RuleConfig>()
                .eq(RuleConfig::getRuleKey, ruleKey)
                .eq(RuleConfig::getScopeKey, scope));
        RuleConfig hit = all.stream()
                .filter(rc -> !rc.getEffectiveFrom().isAfter(bizDate))
                .filter(rc -> rc.getEffectiveTo() == null || !rc.getEffectiveTo().isBefore(bizDate))
                .max(Comparator.comparing(RuleConfig::getVersion))
                .orElse(null);
        if (hit == null) {
            throw new BizException(500, "规则配置缺失: key=" + ruleKey + ", scope=" + scope + ", date=" + bizDate);
        }
        return hit;
    }

    /** 取标量比率/金额/月数。 */
    public BigDecimal getValue(String ruleKey, String scopeKey, LocalDate bizDate) {
        BigDecimal v = getEffective(ruleKey, scopeKey, bizDate).getRuleValue();
        if (v == null) {
            throw new BizException(500, "规则配置无标量值: key=" + ruleKey + ", scope=" + scopeKey);
        }
        return v;
    }

    public BigDecimal getValue(String ruleKey, LocalDate bizDate) {
        return getValue(ruleKey, "", bizDate);
    }

    /** 取结构化 JSON 值(如管理费阶梯)。 */
    public String getJson(String ruleKey, String scopeKey, LocalDate bizDate) {
        String j = getEffective(ruleKey, scopeKey, bizDate).getRuleJson();
        if (j == null) {
            throw new BizException(500, "规则配置无 JSON 值: key=" + ruleKey + ", scope=" + scopeKey);
        }
        return j;
    }
}
