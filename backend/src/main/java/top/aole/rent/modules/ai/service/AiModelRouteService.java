package top.aole.rent.modules.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.aole.rent.modules.ai.domain.AiScenes;
import top.aole.rent.modules.rule.service.RuleConfigService;

import java.time.LocalDate;

/**
 * 模型路由(可换模型·不写死):按接入点从 rule_config(key = ai_model_<sceneKey>)解析模型名,
 * 缺失则兜底枚举默认模型(§12.2)。设置中心改一行 rule_config 即换模型,业务代码不动。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelRouteService {

    private final RuleConfigService ruleConfigService;

    /** 解析接入点当前生效模型名。 */
    public String resolve(AiScenes scene) {
        String key = "ai_model_" + scene.getKey();
        try {
            String model = ruleConfigService.getJson(key, "", LocalDate.now());
            if (model != null && !model.isEmpty()) {
                return model.trim();
            }
        } catch (Exception e) {
            log.debug("[AiRoute] rule_config {} 缺失,兜底默认模型 {}", key, scene.getDefaultModel());
        }
        return scene.getDefaultModel();
    }
}
