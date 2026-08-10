package top.aole.rent.modules.workbench.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.workbench.dto.WorkbenchDtos;
import top.aole.rent.modules.workbench.service.WorkbenchService;

/**
 * 工作台聚合(WT-01)。登录落地页:按当前登录角色返回 KPI + 红点 + 待办(角色投影可见)。
 */
@Api(tags = "工作台聚合")
@RestController
@RequestMapping("/rent/workbench")
@RequiredArgsConstructor
public class WorkbenchController {

    private final WorkbenchService workbenchService;

    @ApiOperation("工作台:各角色待办 + 亮灯红点 + 驾驶舱 KPI(按角色可见)")
    @GetMapping
    public R<WorkbenchDtos.Workbench> workbench() {
        return R.ok(workbenchService.workbench());
    }
}
