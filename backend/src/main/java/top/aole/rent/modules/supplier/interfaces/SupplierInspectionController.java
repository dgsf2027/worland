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
import top.aole.rent.modules.supplier.dto.InspectionDtos;
import top.aole.rent.modules.supplier.service.SupplierInspectionExcelService;
import top.aole.rent.modules.supplier.service.SupplierInspectionService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 供应商考察(对齐「厂家考察汇总表」)。列表按序号排序;新增/编辑 → 上传考察记录压缩包(走 /rent/files,
 * bizType=supplier_inspection) → 判定/改判 合格·不合格;Excel 导出/导入(按公司名称自动更新)。
 * 合格列入供应商·上游并关联;不合格不列入、解除关联。
 */
@Api(tags = "供应商·考察")
@RestController
@RequestMapping("/rent/supplier-inspections")
@RequiredArgsConstructor
public class SupplierInspectionController {

    private final SupplierInspectionService inspectionService;
    private final SupplierInspectionExcelService excelService;

    @ApiOperation("考察列表:按公司/联系人/法人关键词、考察结果筛选 + 分页")
    @GetMapping
    public R<PageResult<InspectionDtos.Item>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String result,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(inspectionService.list(keyword, result, page, size));
    }

    @ApiOperation("导出「厂家考察汇总表」Excel(template=true 只导出表头空模板);导出文件改完可直接导回")
    @GetMapping("/export")
    public ResponseEntity<ByteArrayResource> export(@RequestParam(defaultValue = "false") boolean template) {
        byte[] data = excelService.export(template);
        String fileName = template ? "厂家考察汇总表-模板.xlsx"
                : "厂家考察汇总表-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
        ContentDisposition cd = ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }

    @ApiOperation("导入「厂家考察汇总表」(.xls/.xlsx):按公司名称新增或更新;「是否合格」是/否 自动判定或改判并同步供应商上游(敏感·供应链+老板·留痕)")
    @RequireRole(value = {"供应链", "老板"}, action = "供应商考察导入", targetType = "supplier_inspection")
    @PostMapping("/import")
    public R<InspectionDtos.ImportResult> importSheet(@RequestParam("file") MultipartFile file) {
        return R.ok(excelService.importFile(file));
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

    @ApiOperation("编辑考察公司信息(任意结果均可编辑)")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Validated @RequestBody InspectionDtos.SaveRequest req) {
        inspectionService.update(id, req);
        return R.ok();
    }

    @ApiOperation("删除考察(合格的需先改判不合格·逻辑删除)")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        inspectionService.delete(id);
        return R.ok();
    }

    @ApiOperation("判定或改判考察结果(敏感·供应链+老板·留痕):合格→列入供应商上游并关联;不合格→不列入、解除关联并淘汰原关联供应商")
    @RequireRole(value = {"供应链", "老板"}, action = "供应商考察判定", targetType = "supplier_inspection")
    @PostMapping("/{id}/decide")
    public R<InspectionDtos.DecideResult> decide(@PathVariable Long id,
                                                 @Validated @RequestBody InspectionDtos.DecideRequest req) {
        return R.ok(inspectionService.decide(id, req));
    }
}
