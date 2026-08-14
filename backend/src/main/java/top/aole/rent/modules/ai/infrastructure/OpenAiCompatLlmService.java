package top.aole.rent.modules.ai.infrastructure;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.core.net.SSLUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import top.aole.rent.modules.ai.domain.ILlmService;
import top.aole.rent.modules.ai.domain.LlmReply;

/**
 * OpenAI 兼容 LLM 实现(真调用 · @Primary 覆盖 mock)。
 *
 * <p>断路策略(§4.13 绝不 return null / 不让页面空):
 * <ol>
 *   <li>base-url / api-key / model 任一空 → 直接走 {@link MockLlmService}(零外呼,executor=mock)。</li>
 *   <li>三项齐全 → POST {base-url}/v1/chat/completions(messages=[system 角色设定, user=脱敏 prompt],
 *       temperature 0.5,超时 30s)。成功解析 choices[0].message.content,executor=real,带 token 用量。</li>
 *   <li>真调用抛异常/超时/非 200 → 优雅回退 mock 草稿(executor=fallback),业务不断、页面不空。</li>
 * </ol>
 *
 * 脱敏(DESIGN_DOC §十一):prompt 由调用方喂入前已剔成本/分配/身份;本类不再触碰原始数据。
 * 铁律(§4.7 #7):数字全部来自规则引擎,LLM 只把话说顺,本类不参与任何计算。
 */
@Slf4j
@Primary
@Service("openAiCompatLlmService")
public class OpenAiCompatLlmService implements ILlmService {

    private static final String SYSTEM_ROLE =
            "你是设备租赁公司的资深财务/经营分析助理。你只负责把已算好的数字用自然语言说顺、"
                    + "给出务实的经营结论与下一步动作,绝不新增、修改或推算任何数字。语气专业、不套话。";

    private final MockLlmService mockLlmService;

    @Value("${llm.base-url:}")
    private String baseUrl;

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.model:}")
    private String configModel;

    @Value("${llm.timeout-ms:30000}")
    private int timeoutMs;

    @Value("${llm.trust-all-ssl:true}")
    private boolean trustAllSsl;

    public OpenAiCompatLlmService(MockLlmService mockLlmService) {
        this.mockLlmService = mockLlmService;
    }

    @Override
    public LlmReply chat(String routedModel, String prompt) {
        // 断路①:未配全 key → 零外呼走 mock
        if (StrUtil.hasBlank(baseUrl, apiKey, configModel)) {
            log.debug("[OpenAiLlm] LLM 未配全(base-url/api-key/model 任一空)→ mock 断路");
            return mockLlmService.chat(routedModel, prompt);
        }

        // 真调用:模型优先用接入点路由结果(可换模型不写死),路由为空才兜底 llm.model
        String model = StrUtil.blankToDefault(routedModel, configModel);
        try {
            LlmReply reply = callReal(model, prompt);
            log.info("[OpenAiLlm] 真调用成功 model={} tokensIn={} tokensOut={}",
                    model, reply.getTokensIn(), reply.getTokensOut());
            return reply;
        } catch (Exception ex) {
            // 断路②:真调用失败 → 优雅回退 mock 草稿(不 500、不 null、不空页)
            log.warn("[OpenAiLlm] 真调用失败,回退 mock 草稿:{}", ex.getMessage());
            LlmReply fb = mockLlmService.chat(routedModel, prompt);
            fb.setExecutor("fallback");
            return fb;
        }
    }

    /** 真 HTTP POST {base-url}/v1/chat/completions(OpenAI 兼容)。 */
    private LlmReply callReal(String model, String prompt) {
        String url = StrUtil.removeSuffix(baseUrl.trim(), "/") + "/v1/chat/completions";

        JSONArray messages = new JSONArray();
        messages.add(new JSONObject().set("role", "system").set("content", SYSTEM_ROLE));
        messages.add(new JSONObject().set("role", "user").set("content", prompt));
        String body = new JSONObject()
                .set("model", model)
                .set("messages", messages)
                .set("temperature", 0.5)
                .set("stream", false)
                .toString();

        HttpRequest req = HttpRequest.post(url)
                .header("Authorization", "Bearer " + apiKey.trim())
                .header("Content-Type", "application/json")
                .timeout(timeoutMs)
                .body(body);
        if (trustAllSsl) {
            // 自签证书/内网代理宽松容错(§4.13 rejectUnauthorized:false 类比):信任所有证书 + 不校验主机名
            req.setSSLSocketFactory(SSLUtil.createSSLContext(null).getSocketFactory());
            req.setHostnameVerifier((hostname, session) -> true);
        }

        try (HttpResponse resp = req.execute()) {
            int status = resp.getStatus();
            String respBody = resp.body();
            if (status != 200) {
                throw new IllegalStateException("LLM 非 200:" + status + " " + StrUtil.brief(respBody, 200));
            }
            JSONObject json = JSONUtil.parseObj(respBody);
            String content = json.getByPath("choices.0.message.content", String.class);
            if (StrUtil.isBlank(content)) {
                throw new IllegalStateException("LLM 返回无 content:" + StrUtil.brief(respBody, 200));
            }
            Integer tokensIn = null;
            Integer tokensOut = null;
            JSONObject usage = json.getJSONObject("usage");
            if (usage != null) {
                tokensIn = usage.getInt("prompt_tokens");
                tokensOut = usage.getInt("completion_tokens");
            }
            return new LlmReply(content.trim(), tokensIn, tokensOut, "real");
        }
    }
}
