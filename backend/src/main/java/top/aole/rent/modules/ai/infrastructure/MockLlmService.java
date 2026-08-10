package top.aole.rent.modules.ai.infrastructure;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.aole.rent.modules.ai.domain.ILlmService;
import top.aole.rent.modules.ai.domain.LlmReply;

/**
 * LLM mock 实现:无 key 断路档,返回带 [MOCK] 前缀的合理文案,绝不发网络请求(无 key 不外呼)。
 *
 * <p>约定:prompt 内含「草稿:xxx」段时,直接把该规则草稿当综述回显——
 * 这样 mock 阶段 UI 上已是真数字组成的人话,接真网关只是把话说得更顺。规则出数字,LLM 不算数。
 */
@Slf4j
@Service("mockLlmService")
public class MockLlmService implements ILlmService {

    private static final String DRAFT_MARK = "草稿:";

    @Override
    public LlmReply chat(String model, String prompt) {
        log.debug("[MockLlm] model={} prompt(前50字)={}", model,
                prompt == null ? "" : prompt.substring(0, Math.min(50, prompt.length())));
        String draft = extractDraft(prompt);
        String text = "[MOCK] " + (draft != null ? draft
                : "本月经营综述(占位):数字均由规则引擎算出,接入真模型后此处替换为自然语言综述。");
        return LlmReply.mock(text);
    }

    /** 抽 prompt 里的规则草稿段(到下一段标记「\n」前的整句),原样回显。 */
    private String extractDraft(String prompt) {
        if (prompt == null) {
            return null;
        }
        int i = prompt.indexOf(DRAFT_MARK);
        if (i < 0) {
            return null;
        }
        String tail = prompt.substring(i + DRAFT_MARK.length());
        int nl = tail.indexOf('\n');
        return (nl < 0 ? tail : tail.substring(0, nl)).trim();
    }
}
