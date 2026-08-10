package top.aole.rent.modules.maintenance.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.maintenance.dto.MaintenanceDtos;
import top.aole.rent.modules.maintenance.service.MaintenanceService;

/**
 * 维保工单(M4-04)。报修→派工→处理→回写闭环;故障回写 asset_bom.fault_count;质保内转供应商;高故障备件提示。
 */
@Api(tags = "维保工单")
@RestController
@RequestMapping("/rent/maintenance")
@RequiredArgsConstructor
public class MaintenanceController {

    private final MaintenanceService maintenanceService;

    @ApiOperation("工单列表(按状态/类型/设备筛选)")
    @GetMapping
    public R<PageResult<MaintenanceDtos.MaintenanceItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Long assetId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(maintenanceService.list(status, type, assetId, page, size));
    }

    @ApiOperation("工单详情")
    @GetMapping("/{id}")
    public R<MaintenanceDtos.MaintenanceItem> detail(@PathVariable Long id) {
        return R.ok(maintenanceService.detail(id));
    }

    @ApiOperation("报修/建单(报修/预防/巡检)")
    @PostMapping
    public R<Long> create(@RequestBody MaintenanceDtos.CreateRequest req) {
        return R.ok(maintenanceService.create(req));
    }

    @ApiOperation("派工(指定处理人/责任方·质保内可转供应商)")
    @PostMapping("/{id}/assign")
    public R<MaintenanceDtos.MaintenanceItem> assign(@PathVariable Long id, @RequestBody MaintenanceDtos.AssignRequest req) {
        return R.ok(maintenanceService.assign(id, req));
    }

    @ApiOperation("处理/完工(回写 fault_count·质保内转供应商·费用不计我方)")
    @PostMapping("/{id}/handle")
    public R<MaintenanceDtos.MaintenanceItem> handle(@PathVariable Long id, @RequestBody MaintenanceDtos.HandleRequest req) {
        return R.ok(maintenanceService.handle(id, req));
    }

    @ApiOperation("高故障配件备件提示(fault_count 超阈值)")
    @GetMapping("/spare-alert")
    public R<MaintenanceDtos.SparePartAlertResponse> sparePartAlert() {
        return R.ok(maintenanceService.sparePartAlert());
    }
}
