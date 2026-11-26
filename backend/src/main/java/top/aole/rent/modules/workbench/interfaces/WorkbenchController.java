package top.aole.rent.modules.workbench.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.auth.RequireRole;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.contract.dto.ProjectPanoramaDtos;
import top.aole.rent.modules.contract.service.ProjectPanoramaService;
import top.aole.rent.modules.workbench.dto.WorkbenchChartsDtos;
import top.aole.rent.modules.workbench.dto.WorkbenchDtos;
import top.aole.rent.modules.workbench.dto.WorkbenchWaterlineDtos;
import top.aole.rent.modules.workbench.service.WorkbenchCashWaterlineService;
import top.aole.rent.modules.workbench.service.WorkbenchChartsService;
import top.aole.rent.modules.workbench.service.WorkbenchService;

import java.math.BigDecimal;

/**
 * 工作台聚合(WT-01)。登录落地页:按当前登录角色返回 KPI + 红点 + 待办 + 资金水位线 + 数据体检。
 */
@Api(tags = "工作台聚合")
@RestController
@RequestMapping("/rent/workbench")
@RequiredArgsConstructor
public class WorkbenchController {

    private final WorkbenchService workbenchService;
    private final WorkbenchChartsService workbenchChartsService;
    private final ProjectPanoramaService projectPanoramaService;
    private final WorkbenchCashWaterlineService waterlineService;

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

    @ApiOperation("工作台·资金水位线预测：叠加当前银行可用余额与未来6/12个月收付计划，预判资金见底月份与缺口")
    @GetMapping("/cash-waterline")
    public R<WorkbenchWaterlineDtos.CashWaterline> cashWaterline(
            @RequestParam(required = false) Integer months,
            @RequestParam(required = false) BigDecimal cashBalance) {
        return R.ok(waterlineService.cashWaterline(months, cashBalance));
    }

    @ApiOperation("工作台·保存当前银行可用余额(老板/财务)")
    @RequireRole(value = {"老板", "财务"}, action = "更新银行可用余额", targetType = "rule_config")
    @PutMapping("/cash-balance")
    public R<BigDecimal> saveCashBalance(@RequestBody WorkbenchWaterlineDtos.CashBalanceRequest req) {
        return R.ok(waterlineService.saveCashBalance(req == null ? null : req.getBalance(),
                req == null ? null : req.getRemark()));
    }

    @ApiOperation("工作台·数据体检：扫描残留孤儿系统任务、真实逾期、待开销项票与未绑主合同采购单")
    @RequireRole(value = {"老板", "财务"}, action = "经营数据体检", targetType = "workbench")
    @GetMapping("/data-health")
    public R<WorkbenchWaterlineDtos.DataHealth> dataHealth() {
        return R.ok(waterlineService.dataHealth());
    }

    @ApiOperation("工作台·一键清理孤儿系统任务(来源单据已关闭/结清/删除)")
    @RequireRole(value = {"老板", "财务"}, action = "清理孤儿系统任务", targetType = "task")
    @PostMapping("/data-health/clean-stale-tasks")
    public R<WorkbenchWaterlineDtos.CleanupResult> cleanStaleTasks() {
        return R.ok(waterlineService.cleanStaleTasks());
    }
}
