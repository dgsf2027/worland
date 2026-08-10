package top.aole.rent.modules.pdca.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.aole.rent.modules.ai.domain.AiScenes;
import top.aole.rent.modules.ai.service.LlmGateway;
import top.aole.rent.modules.pdca.dto.PdcaDtos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PDCA 六指标看板服务(M5-05)。每指标本期红绿灯(系统自动算·复用 {@link PdcaMetricService})
 * + 一次 AI 综述(脱敏·只说趋势与动作·不出数字·LlmGateway mock 断路)。
 *
 * <p>铁律(§4.7 #7):红绿灯由规则/BI 出,LLM 只把"哪些红、该改什么"写顺;
 * 喂入 prompt 仅含指标名与红/绿状态(§十一 脱敏:不含任何数值/成本/身份)。
 */
@Service
@RequiredArgsConstructor
public class PdcaBoardService {

    private final PdcaMetricService metricService;
    private final LlmGateway llmGateway;

    public PdcaDtos.BoardResp board(boolean force) {
        PdcaDtos.BoardResp resp = new PdcaDtos.BoardResp();
        List<String> reds = new ArrayList<>();
        List<String> greens = new ArrayList<>();

        for (PdcaMetricService.MetricDef def : metricService.defs()) {
            BigDecimal value = metricService.value(def.key);
            BigDecimal target = metricService.threshold(def.key);
            String light = metricService.light(def.key, value, target);

            PdcaDtos.Indicator ind = new PdcaDtos.Indicator();
            ind.setMetricKey(def.key);
            ind.setLabel(def.label);
            ind.setValue(value);
            ind.setTarget(target);
            ind.setCompareOp(def.compareOp);
            ind.setUnit(def.unit);
            ind.setLight(light);
            ind.setSource("BI 只读聚合(不重算)· 阈值 rule_config[pdca_threshold]");
            ind.setNote(light.equals("gray") ? "取不到值 · 需人工判定"
                    : light.equals("green") ? "达标" : "未达标 · 建议登记改进项");
            resp.getIndicators().add(ind);

            if ("red".equals(light)) {
                reds.add(def.label);
                resp.setRedCount(resp.getRedCount() + 1);
            } else if ("green".equals(light)) {
                greens.add(def.label);
                resp.setGreenCount(resp.getGreenCount() + 1);
            }
        }

        // ---- 一次 AI 综述(脱敏:仅喂指标名 + 红/绿,不含任何数值)----
        String redsStr = reds.isEmpty() ? "无" : String.join("、", reds);
        String greensStr = greens.isEmpty() ? "无" : String.join("、", greens);
        String fallback = reds.isEmpty()
                ? "本期经营指标全部达标(" + greensStr + "),维持现有节奏,关注可持续性即可。"
                : "本期需重点改进:" + redsStr + ";建议就每个红灯指标登记改进项、指定负责人与到期回查日,"
                + "达标项(" + greensStr + ")保持。";

        String bizKey = "board:" + LocalDate.now();
        LlmGateway.GatewayResult result = llmGateway.invoke(LlmGateway.LlmTask.builder()
                .scene(AiScenes.PDCA)
                .sceneLabel("PDCA综述:" + LocalDate.now())
                .bizKey(bizKey)
                .prompt("你是设备租赁公司的经营改进助理。以下是本期经营红绿灯(已脱敏·无任何数值):"
                        + "红灯(未达标):" + redsStr + ";绿灯(达标):" + greensStr + "。"
                        + "请写一段面向老板的改进综述:先点出最该抓的红灯,给出方向性动作(如去化闲置/催收/调价),"
                        + "再肯定达标项。务实、不出具体数字、不套话,3-4 句。")
                .reasoning("红绿灯由 BI 只读聚合出数、rule_config 出阈值判定;LLM 仅据红/绿状态起草改进综述,"
                        + "未参与任何计算(§4.7 #7)。喂入仅含指标名与红/绿(§十一 脱敏:无数值/成本/身份)。")
                .inputDigest("{\"red\":" + jsonArr(reds) + ",\"green\":" + jsonArr(greens) + "}")
                .confidence(BigDecimal.valueOf(0.9))
                .confidenceSource("fixed")
                .promptFingerprint("rent-pdca-board-v1")
                .fallbackText(fallback)
                .build(), force);

        resp.setAiText(result.getCall().getOutputText());
        resp.setLlmCallId(result.getCall().getId());
        resp.setModel(result.getCall().getModel());
        resp.setMock(result.getCall().getModel() != null && result.getCall().getModel().startsWith("mock"));
        resp.setCacheHit(result.isCacheHit());
        return resp;
    }

    private String jsonArr(List<String> list) {
        return "[" + list.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(",")) + "]";
    }
}
