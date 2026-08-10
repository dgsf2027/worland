package top.aole.rent.modules.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI 接入点注册表(DESIGN_DOC §十一 · 按价值排序)。
 * 每个接入点:路由键(rule_config key = ai_model_<key>,设置中心可独立换模型)+ 默认模型预填。
 * 总原则:规则引擎出数字,LLM 只做起草/解释;未配 key 全走 mock,零外呼。
 *
 * <p>本波(M3 Wave C)仅挂接 MONTHLY(月报起草);其余为占位,后续 M5 事人 BI 再挂。
 */
@Getter
@AllArgsConstructor
public enum AiScenes {

    MONTHLY("monthly", "月报起草", "月度经营分析报告综述(长上下文喂脱敏整月数据)", "kimi-k2");

    /** 路由键(rule_config key 后缀 ai_model_<key> / idempotent key 前缀) */
    private final String key;
    /** 中文名(= llm_call_log.scene 主场景) */
    private final String label;
    private final String description;
    /** §12.2 默认模型(rule_config 缺失时兜底) */
    private final String defaultModel;
}
