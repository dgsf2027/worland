package top.aole.rent.modules.ai.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.ai.domain.entity.LlmCallLog;
import top.aole.rent.modules.ai.mapper.LlmCallLogMapper;

/**
 * AI 透明中心(🔬 过程入口):按 call id 取透明四件套(推理过程/完整输出/置信分/原始数据·脱敏后)。
 * 前端 AI 结论旁的 🔬 徽标点开即调本接口。
 */
@Api(tags = "AI · 透明四件套")
@RestController
@RequestMapping("/rent/ai")
@RequiredArgsConstructor
public class AiController {

    private final LlmCallLogMapper llmCallLogMapper;

    @ApiOperation("AI 调用透明四件套(推理过程/完整输出/置信分/原始数据·脱敏后)")
    @GetMapping("/call/{id}")
    public R<LlmCallLog> call(@PathVariable Long id) {
        return R.ok(llmCallLogMapper.selectById(id));
    }
}
