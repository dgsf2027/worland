package top.aole.rent.modules.project.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.auth.RequireRole;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.project.dto.ProjectDtos;
import top.aole.rent.modules.project.service.ProjectService;

import java.util.List;

/**
 * 租赁项目中心（一单到底 · V131）。
 */
@Api(tags = "项目中心·一单到底")
@RestController
@RequestMapping("/rent/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @ApiOperation("项目列表：按阶段/关键字筛选，返回7步推进进度与真实利润KPI")
    @GetMapping
    public R<List<ProjectDtos.ListItem>> list(
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String keyword) {
        return R.ok(projectService.list(stage, keyword));
    }

    @ApiOperation("项目详情：7步全流程进度 + 交付过磅验收 + 嵌入真实利润全景")
    @GetMapping("/{id}")
    public R<ProjectDtos.Detail> detail(@PathVariable Long id) {
        return R.ok(projectService.detail(id));
    }

    @ApiOperation("新建租赁项目（支持先采后签，先录客户/供应商/报价/货位吨数）")
    @RequireRole(value = {"老板", "财务", "供应链", "业务"}, action = "新建租赁项目", targetType = "project")
    @PostMapping
    public R<Long> create(@Validated @RequestBody ProjectDtos.SaveRequest req) {
        return R.ok(projectService.create(req));
    }

    @ApiOperation("更新项目要素与双合同绑定（自动对齐采购合同↔租赁合同关联）")
    @RequireRole(value = {"老板", "财务", "供应链", "业务"}, action = "更新租赁项目", targetType = "project")
    @PutMapping("/{id}")
    public R<ProjectDtos.Detail> update(@PathVariable Long id, @Validated @RequestBody ProjectDtos.SaveRequest req) {
        return R.ok(projectService.update(id, req));
    }

    @ApiOperation("登记交付与三方验收（自动联动采购合同月结到期日、5年质保与资产台账）")
    @RequireRole(value = {"老板", "财务", "供应链"}, action = "项目交付验收", targetType = "project")
    @PostMapping("/{id}/delivery")
    public R<ProjectDtos.Detail> updateDelivery(@PathVariable Long id, @RequestBody ProjectDtos.DeliveryRequest req) {
        return R.ok(projectService.updateDelivery(id, req));
    }

    @ApiOperation("按「工程套装」一键生成一套货架/阁楼资产台账（含货位数/吨数/库区）")
    @RequireRole(value = {"老板", "财务", "供应链", "业务"}, action = "项目建工程套装", targetType = "project")
    @PostMapping("/{id}/kit-asset")
    public R<Long> createKitAsset(@PathVariable Long id, @RequestBody(required = false) ProjectDtos.CreateKitAssetRequest req) {
        return R.ok(projectService.createKitAsset(id, req));
    }

    @ApiOperation("从现有生效租赁合同一键同步生成项目卡片")
    @RequireRole(value = {"老板", "财务", "供应链", "业务"}, action = "同步现有合同到项目中心", targetType = "project")
    @PostMapping("/sync-from-contracts")
    public R<ProjectDtos.SyncResult> syncFromContracts() {
        return R.ok(projectService.syncFromExistingContracts());
    }
}
