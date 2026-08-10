package top.aole.rent.modules.pdca.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.pdca.dto.PdcaDtos;
import top.aole.rent.modules.pdca.service.ActionItemService;
import top.aole.rent.modules.pdca.service.PdcaBoardService;
import top.aole.rent.modules.pdca.service.PdcaMetricService;
import top.aole.rent.modules.pdca.service.PdcaRecheckScheduler;

import javax.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PDCA 改进循环接口(M5-05 · §流程11):五指标红绿灯看板 + AI 综述 + 改进项 CRUD +
 * 到期回查三分支(通过关闭/未达升级/需人工判定)+ 指标注册表。
 */
@Api(tags = "PDCA · 改进循环(红绿灯看板/改进项/到期回查)")
@RestController
@RequestMapping("/rent/pdca")
@RequiredArgsConstructor
public class PdcaController {

    private final PdcaBoardService boardService;
    private final ActionItemService actionItemService;
    private final PdcaMetricService metricService;
    private final PdcaRecheckScheduler recheckScheduler;

    @ApiOperation("五指标红绿灯看板 + AI 综述(脱敏·不出数字·mock 断路)")
    @GetMapping("/board")
    public R<PdcaDtos.BoardResp> board(@RequestParam(required = false, defaultValue = "false") boolean force) {
        return R.ok(boardService.board(force));
    }

    @ApiOperation("改进项清单(状态/环节筛选;到期红标)")
    @GetMapping("/items")
    public R<List<PdcaDtos.ItemRow>> items(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) String scene) {
        return R.ok(actionItemService.list(status, scene));
    }

    @ApiOperation("登记改进项(绑指标键自动取基线值+默认方向;留空指标=需人工判定)")
    @PostMapping("/items")
    public R<Long> create(@Valid @RequestBody PdcaDtos.ItemSaveReq req) {
        return R.ok(actionItemService.create(req));
    }

    @ApiOperation("编辑改进项(仅进行中/未见效可改)")
    @PutMapping("/items/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody PdcaDtos.ItemSaveReq req) {
        actionItemService.update(id, req);
        return R.ok(null);
    }

    @ApiOperation("手动关闭改进项(留原因)")
    @PostMapping("/items/{id}/close")
    public R<Void> close(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
        actionItemService.close(id, body == null ? null : body.get("note"));
        return R.ok(null);
    }

    @ApiOperation("单条一键回查:指标当前值 vs 目标 → 通过关闭/未达升级/需人工判定")
    @PostMapping("/items/{id}/recheck")
    public R<PdcaDtos.RecheckResp> recheck(@PathVariable Long id) {
        return R.ok(actionItemService.recheck(id));
    }

    @ApiOperation("批量到期回查(=cron 执行体·可手动触发)")
    @PostMapping("/items/recheck-due")
    public R<PdcaDtos.RecheckBatchResp> recheckDue() {
        return R.ok(recheckScheduler.runRecheckDueCron());
    }

    @ApiOperation("指标注册表(新建下拉;每键取数函数复用 BI·带当前值预览)")
    @GetMapping("/metrics")
    public R<List<PdcaDtos.MetricDefRow>> metrics() {
        List<PdcaDtos.MetricDefRow> rows = new ArrayList<>();
        for (PdcaMetricService.MetricDef def : metricService.defs()) {
            PdcaDtos.MetricDefRow row = new PdcaDtos.MetricDefRow();
            row.setKey(def.key);
            row.setLabel(def.label);
            row.setUnit(def.unit);
            row.setCompareOp(def.compareOp);
            row.setDefaultTarget(metricService.threshold(def.key));
            row.setCurrentValue(metricService.value(def.key));
            row.setNote(row.getCurrentValue() == null ? "取不到当前值·登记为需人工判定" : "可作结构化回查");
            rows.add(row);
        }
        return R.ok(rows);
    }
}
