package top.aole.rent.modules.supplier.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.multipart.MultipartFile;
import top.aole.rent.common.auth.RequireRole;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.supplier.dto.DependencyAlert;
import top.aole.rent.modules.supplier.dto.RetireRequest;
import top.aole.rent.modules.supplier.dto.SupplierDetailResponse;
import top.aole.rent.modules.supplier.dto.SupplierEditDtos;
import top.aole.rent.modules.supplier.dto.SupplierPoolItem;
import top.aole.rent.modules.supplier.dto.SupplierSaveRequest;
import top.aole.rent.modules.supplier.service.SupplierExcelService;
import top.aole.rent.modules.supplier.service.SupplierService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 供应商 · 上游管理(M1-01/02)。供应商池/详情/CRUD/淘汰/删除/单一依赖预警;
 * 详情分块编辑(履约评分/价格构成/供货矩阵);Excel 导入导出。
 */
@Api(tags = "供应商·上游")
@RestController
@RequestMapping("/rent/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;
    private final SupplierExcelService excelService;

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

    @ApiOperation("导出供应商 Excel(供应商 + 供货矩阵两张表;template=true 只导出表头);改完可直接导回")
    @GetMapping("/export")
    public ResponseEntity<ByteArrayResource> export(@RequestParam(defaultValue = "false") boolean template) {
        byte[] data = excelService.export(template);
        String fileName = template ? "供应商上游-模板.xlsx"
                : "供应商上游-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
        ContentDisposition cd = ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }

    @ApiOperation("导入供应商 Excel(.xls/.xlsx):供应商按名称、供货项按 供应商+供何物 新增或更新(敏感·供应链+老板·留痕)")
    @RequireRole(value = {"供应链", "老板"}, action = "供应商导入", targetType = "supplier")
    @PostMapping("/import")
    public R<SupplierEditDtos.ImportResult> importSheet(@RequestParam("file") MultipartFile file) {
        return R.ok(excelService.importFile(file));
    }

    @ApiOperation("编辑履约评分(五维 0-100;加权总分按权重即时算)")
    @PutMapping("/{id}/scores")
    public R<Void> updateScores(@PathVariable Long id, @Validated @RequestBody SupplierEditDtos.ScoreRequest req) {
        supplierService.updateScores(id, req);
        return R.ok();
    }

    @ApiOperation("编辑价格构成(材料/加工/利润/报价/我方 BOM 估算)")
    @PutMapping("/{id}/price-composition")
    public R<Void> updatePrice(@PathVariable Long id, @Validated @RequestBody SupplierEditDtos.PriceRequest req) {
        supplierService.updatePriceComposition(id, req);
        return R.ok();
    }

    @ApiOperation("供货矩阵:新增一行")
    @PostMapping("/{id}/supplies")
    public R<Long> addSupply(@PathVariable Long id, @Validated @RequestBody SupplierEditDtos.SupplyRequest req) {
        return R.ok(supplierService.addSupply(id, req));
    }

    @ApiOperation("供货矩阵:编辑一行")
    @PutMapping("/supplies/{supplyId}")
    public R<Void> updateSupply(@PathVariable Long supplyId, @Validated @RequestBody SupplierEditDtos.SupplyRequest req) {
        supplierService.updateSupply(supplyId, req);
        return R.ok();
    }

    @ApiOperation("供货矩阵:删除一行")
    @DeleteMapping("/supplies/{supplyId}")
    public R<Void> deleteSupply(@PathVariable Long supplyId) {
        supplierService.deleteSupply(supplyId);
        return R.ok();
    }

    @ApiOperation("删除供应商(敏感·供应链+老板·留痕;被设备/采购/维保/考察引用时拒绝,请改用淘汰)")
    @RequireRole(value = {"供应链", "老板"}, action = "供应商删除", targetType = "supplier")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        supplierService.delete(id);
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
