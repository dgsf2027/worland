package top.aole.rent.modules.task.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.task.dto.TaskDtos;
import top.aole.rent.modules.task.service.TaskGenService;
import top.aole.rent.modules.task.service.TaskService;

/**
 * 任务中心(M5-01)。派单(绑角色绑人)/转派留痕/开始/完成(需校验)/我的任务队列。
 * 系统派单由 {@link TaskGenService} 扫描逾期/兑付缺口/到期跟进自动开(幂等)。
 */
@Api(tags = "任务中心")
@RestController
@RequestMapping("/rent/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TaskGenService taskGenService;

    @ApiOperation("任务列表:按承接角色/人/状态/来源/类型筛选 + 分页")
    @GetMapping
    public R<PageResult<TaskDtos.TaskItem>> list(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(taskService.list(role, userId, status, source, type, page, size));
    }

    @ApiOperation("手动派单:绑角色绑人(必填其一)")
    @PostMapping
    public R<Long> dispatch(@Validated @RequestBody TaskDtos.DispatchRequest req) {
        return R.ok(taskService.dispatch(req));
    }

    @ApiOperation("转派(留痕只追加:from/to/at/by/reason)")
    @PostMapping("/{id}/transfer")
    public R<TaskDtos.TaskItem> transfer(@PathVariable Long id, @Validated @RequestBody TaskDtos.TransferRequest req) {
        return R.ok(taskService.transfer(id, req));
    }

    @ApiOperation("开始任务(待开始→进行中)")
    @PostMapping("/{id}/start")
    public R<TaskDtos.TaskItem> start(@PathVariable Long id) {
        return R.ok(taskService.start(id));
    }

    @ApiOperation("完成任务(verify_required=1 缺 evidence 拒绝完成)")
    @PostMapping("/{id}/complete")
    public R<TaskDtos.TaskItem> complete(@PathVariable Long id, @RequestBody(required = false) TaskDtos.CompleteRequest req) {
        return R.ok(taskService.complete(id, req));
    }

    @ApiOperation("手动触发系统派单扫描(逾期/兑付缺口/到期跟进 → 幂等自动开任务)")
    @PostMapping("/system-scan")
    public R<TaskGenService.GenResult> systemScan() {
        return R.ok(taskGenService.runSystemDispatch());
    }
}
