package top.aole.rent.modules.workbench.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.contract.dto.ProjectPanoramaDtos;
import top.aole.rent.modules.contract.service.ProjectPanoramaService;
import top.aole.rent.modules.workbench.dto.WorkbenchDtos;
import top.aole.rent.modules.workbench.dto.WorkbenchChartsDtos;
import top.aole.rent.modules.workbench.service.WorkbenchChartsService;
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
    private final WorkbenchChartsService workbenchChartsService;
    private final ProjectPanoramaService projectPanoramaService;

    @ApiOperation("工作台:各角色待办 + 亮灯红点 + 驾驶舱 KPI(按角色可见)")
    @GetMapping
    public R<WorkbenchDtos.Workbench> workbench() {
        return R.ok(workbenchService.workbench());
    }

    @ApiOperation("工作台图表：按角色返回客户阶段、仓库出租、收租原单趋势及计划现金流聚合")
    @GetMapping("/charts")
    public R<WorkbenchChartsDtos.Charts> charts(@RequestParam(required = false) Integer months) {
        return R.ok(workbenchChartsService.charts(months));
    }

    @ApiOperation("工作台·项目真实利润总览：汇总各项目采购投入、已收租金、净现金流、预计毛利/税后净利与回本进度")
    @GetMapping("/project-panoramas")
    public R<ProjectPanoramaDtos.Overview> projectPanoramas() {
        return R.ok(projectPanoramaService.overview());
    }
}
