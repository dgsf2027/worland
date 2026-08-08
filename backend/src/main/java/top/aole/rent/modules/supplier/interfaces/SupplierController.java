package top.aole.rent.modules.supplier.interfaces;

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
import top.aole.rent.common.result.PageResult;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.supplier.dto.DependencyAlert;
import top.aole.rent.modules.supplier.dto.RetireRequest;
import top.aole.rent.modules.supplier.dto.SupplierDetailResponse;
import top.aole.rent.modules.supplier.dto.SupplierPoolItem;
import top.aole.rent.modules.supplier.dto.SupplierSaveRequest;
import top.aole.rent.modules.supplier.service.SupplierService;

/**
 * 供应商 · 上游管理(M1-01/02)。供应商池/详情/CRUD/淘汰/单一依赖预警。
 */
@Api(tags = "供应商·上游")
@RestController
@RequestMapping("/rent/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    @ApiOperation("供应商池:搜索(名/配件)/按品类/状态/评分下限筛选 + 分页")
    @GetMapping
    public R<PageResult<SupplierPoolItem>> pool(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer minScore,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(supplierService.pool(keyword, category, status, minScore, page, size));
    }

    @ApiOperation("单一依赖预警:某品类可用供应商<下限亮灯")
    @GetMapping("/dependency-alert")
    public R<DependencyAlert> dependencyAlert() {
        return R.ok(supplierService.dependencyAlert());
    }

    @ApiOperation("供应商详情:履约雷达/供货矩阵/价格构成")
    @GetMapping("/{id}")
    public R<SupplierDetailResponse> detail(@PathVariable Long id) {
        return R.ok(supplierService.detail(id));
    }

    @ApiOperation("新增供应商(含供货矩阵)")
    @PostMapping
    public R<Long> create(@Validated @RequestBody SupplierSaveRequest req) {
        return R.ok(supplierService.create(req));
    }

    @ApiOperation("编辑供应商(供货矩阵全量覆盖)")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Validated @RequestBody SupplierSaveRequest req) {
        supplierService.update(id, req);
        return R.ok();
    }

    @ApiOperation("淘汰/停用供应商(敏感·供应链+老板·留痕)")
    @RequireRole(value = {"供应链", "老板"}, action = "供应商淘汰", targetType = "supplier")
    @PostMapping("/{id}/retire")
    public R<Void> retire(@PathVariable Long id, @Validated @RequestBody RetireRequest req) {
        supplierService.retire(id, req);
        return R.ok();
    }
}
