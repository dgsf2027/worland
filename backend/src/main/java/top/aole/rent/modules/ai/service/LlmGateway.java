package top.aole.rent.modules.ai.service;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.aole.rent.modules.ai.domain.AiScenes;
import top.aole.rent.modules.ai.domain.ILlmService;
import top.aole.rent.modules.ai.domain.LlmReply;
import top.aole.rent.modules.ai.domain.entity.LlmCallLog;
import top.aole.rent.modules.ai.mapper.LlmCallLogMapper;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 统一 LLM 网关(withLlmIdempotent 风格 · 全局 §4.19)。照园区小卖账房 LlmGateway。
 *
 * <ol>
 *   <li>24h idempotent:幂等键 = 接入点 + 业务key + 当日;当日已有成功/降级记录 → 直接复用不重调不烧钱;
 *       force=true 例外(用户点"重新生成")。</li>
 *   <li>多模型路由:模型名由 {@link AiModelRouteService} 按接入点从 rule_config 解析(可换模型不写死)。</li>
 *   <li>mock 断路:无 key → MockLlmService 零外呼,model 记 mock(路由目标)。</li>
 *   <li>透明四件套统一落 yc_rent_llm_call_log:reasoning/output/confidence/inputDigest(脱敏后)。</li>
 *   <li>失败降级:调用异常时若给了 fallbackText(规则草稿),输出草稿并记「降级」,业务不断。</li>
 * </ol>
 *
 * 铁律(§4.7 #7):LLM 永不算数——prompt 里数字全部来自规则引擎,本网关只"把话说顺"。
 * 脱敏(DESIGN_DOC §十一):调用方须在 inputDigest / prompt 中剔除成本/分配/身份后再传入。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmGateway {

    private final ILlmService llmService;
    private final AiModelRouteService routeService;
    private final LlmCallLogMapper llmCallLogMapper;

    /** 一次调用的输入(数字已由规则引擎算好·已脱敏) */
    @Data
    @Builder
    public static class LlmTask {
        private AiScenes scene;
        /** 细分场景名,落 log.scene(空则用接入点中文名) */
        private String sceneLabel;
        /** 业务幂等 key(不含日期,网关自动拼当日) */
        private String bizKey;
        private String prompt;
        /** 四件套·推理过程(规则引擎计算说明) */
        private String reasoning;
        /** 四件套·原始数据(脱敏 JSON) */
        private String inputDigest;
        /** 四件套·置信分(规则真算,如数据完备度) */
        private BigDecimal confidence;
        /** 置信分来源:computed=真算 / fixed=固定基准 */
        private String confidenceSource;
        /** Prompt 模板指纹(版本追踪) */
        private String promptFingerprint;
        /** 失败降级文案(规则草稿);null=失败直接抛 */
        private String fallbackText;
    }

    @Value
    public static class GatewayResult {
        LlmCallLog call;
        boolean cacheHit;
    }

    /**
     * 幂等调用主入口。
     *
     * @param force true=跳过当日缓存强制重调(重新生成按钮)
     */
    public GatewayResult invoke(LlmTask task, boolean force) {
        String idemKey = idempotentKey(task.getScene(), task.getBizKey());
        if (!force) {
            LlmCallLog cached = findCached(idemKey);
            if (cached != null) {
                log.debug("[LlmGateway] 幂等命中 {} → 复用 call#{}", idemKey, cached.getId());
                return new GatewayResult(cached, true);
            }
        }

        String routedModel = routeService.resolve(task.getScene());
        long start = System.currentTimeMillis();

        String outputText;
        String callStatus = "成功";
        Integer tokensIn = null;
        Integer tokensOut = null;
        try {
            LlmReply reply = llmService.chat(routedModel, task.getPrompt());
            outputText = reply.getText();
            tokensIn = reply.getTokensIn();
            tokensOut = reply.getTokensOut();
        } catch (Exception ex) {
            if (task.getFallbackText() == null) {
                throw ex;
            }
            log.warn("[LlmGateway] {} 调用失败,降级用规则草稿:{}", task.getScene().getKey(), ex.getMessage());
            outputText = task.getFallbackText();
            callStatus = "降级";
        }

        LlmCallLog call = new LlmCallLog();
        call.setScene(StrUtil.blankToDefault(task.getSceneLabel(), task.getScene().getLabel()));
        // 占位期无 key → mock 断路,模型记 mock(路由目标)(接真后一眼对照)
        call.setModel("mock(" + routedModel + ")");
        call.setPromptFingerprint(task.getPromptFingerprint());
        call.setIdempotentKey(idemKey);
        call.setCacheHit(0);
        call.setInputDigest(task.getInputDigest());
        call.setReasoning(task.getReasoning());
        call.setOutputText(outputText);
        call.setConfidence(task.getConfidence());
        call.setConfidenceSource(StrUtil.blankToDefault(task.getConfidenceSource(), "fixed"));
        call.setTokensIn(tokensIn);
        call.setTokensOut(tokensOut);
        call.setDurationMs((int) (System.currentTimeMillis() - start));
        call.setCallStatus(callStatus);
        llmCallLogMapper.insert(call);
        return new GatewayResult(call, false);
    }

    /** 幂等键:接入点 + 业务key + 当日(24h 窗口) */
    public String idempotentKey(AiScenes scene, String bizKey) {
        return scene.getKey() + ":" + bizKey + ":" + LocalDate.now();
    }

    private LlmCallLog findCached(String idemKey) {
        return llmCallLogMapper.selectOne(new LambdaQueryWrapper<LlmCallLog>()
                .eq(LlmCallLog::getIdempotentKey, idemKey)
                .ne(LlmCallLog::getCallStatus, "失败")
                .orderByDesc(LlmCallLog::getId)
                .last("LIMIT 1"));
    }
}
