package top.aole.rent.modules.supplier.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.auth.RequireRole;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.supplier.dto.InspectionDtos;
import top.aole.rent.modules.supplier.service.SupplierInspectionService;

/**
 * 供应商考察。新增考察 → 上传考察记录压缩包(走 /rent/files, bizType=supplier_inspection) → 判定合格/不合格。
 * 合格自动列入供应商·上游并关联;不合格不列入。
 */
@Api(tags = "供应商·考察")
@RestController
@RequestMapping("/rent/supplier-inspections")
@RequiredArgsConstructor
public class SupplierInspectionController {

    private final SupplierInspectionService inspectionService;

    @ApiOperation("考察列表:按公司/联系人/法人关键词、考察结果筛选 + 分页")
    @GetMapping
    public R<PageResult<InspectionDtos.Item>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String result,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(inspectionService.list(keyword, result, page, size));
    }

    @ApiOperation("考察详情")
    @GetMapping("/{id}")
    public R<InspectionDtos.Item> detail(@PathVariable Long id) {
        return R.ok(inspectionService.detail(id));
    }

    @ApiOperation("新增考察供应商(结果=待考察)")
    @PostMapping
    public R<Long> create(@Validated @RequestBody InspectionDtos.SaveRequest req) {
        return R.ok(inspectionService.create(req));
    }

    @ApiOperation("编辑考察(仅待考察可编辑)")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Validated @RequestBody InspectionDtos.SaveRequest req) {
        inspectionService.update(id, req);
        return R.ok();
    }

    @ApiOperation("删除考察(仅待考察可删·逻辑删除)")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        inspectionService.delete(id);
        return R.ok();
    }

    @ApiOperation("判定考察结果(敏感·供应链+老板·留痕):合格→列入供应商池并关联;不合格→不列入")
    @RequireRole(value = {"供应链", "老板"}, action = "供应商考察判定", targetType = "supplier_inspection")
    @PostMapping("/{id}/decide")
    public R<InspectionDtos.DecideResult> decide(@PathVariable Long id,
                                                 @Validated @RequestBody InspectionDtos.DecideRequest req) {
        return R.ok(inspectionService.decide(id, req));
    }
}
