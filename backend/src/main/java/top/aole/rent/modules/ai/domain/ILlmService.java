package top.aole.rent.modules.ai.domain;

/**
 * LLM 服务接口(AI 网关)。
 * 占位期唯一实现 = {@code MockLlmService}(无 key 断路,零外呼);
 * 接真网关时新增 OpenAiCompatLlmService 并标 @Primary,业务方只依赖本接口不改。
 */
public interface ILlmService {

    /**
     * 单轮对话(指定模型,多模型路由用)。
     *
     * @param model  模型名(mock 实现忽略,仅记录)
     * @param prompt 用户输入(数字已由规则引擎算好·已脱敏)
     * @return 回复 + token 用量
     */
    LlmReply chat(String model, String prompt);
}
