package top.aole.rent.modules.asset.interfaces;

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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import top.aole.rent.common.auth.RequireRole;
import top.aole.rent.modules.asset.domain.Asset;
import top.aole.rent.modules.asset.dto.BomSheetDtos;
import top.aole.rent.modules.asset.service.AssetBomExcelService;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.asset.dto.AssetDetailResponse;
import top.aole.rent.modules.asset.dto.AssetLinkDtos;
import top.aole.rent.modules.asset.dto.PaymentTermDtos;
import top.aole.rent.modules.asset.dto.AssetListItem;
import top.aole.rent.modules.asset.dto.AssetSaveRequest;
import top.aole.rent.modules.asset.dto.BomFaultRequest;
import top.aole.rent.modules.asset.dto.BomNodeRequest;
import top.aole.rent.modules.asset.dto.BomPricingRequest;
import top.aole.rent.modules.asset.dto.IdleAlertResponse;
import top.aole.rent.modules.asset.dto.StatusChangeRequest;
import top.aole.rent.modules.asset.service.AssetService;

/**
 * 设备 · 租赁台账(M1-06/07)。台账/详情(工程量清单计价表/成本拆解/残值/故障档案/单台收益/状态机)/CRUD/状态流转/清单维护。
 * 敏感财务字段(集采价/账面价/成本)在 {@link AssetService} 按角色投影(GP/LP 打码)。
 */
@Api(tags = "设备·租赁台账")
@RestController
@RequestMapping("/rent/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;
    private final AssetBomExcelService bomExcelService;

    @ApiOperation("设备台账:按状态/品类/关键词筛选 + 分页")
    @GetMapping
    public R<PageResult<AssetListItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long contractId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(assetService.list(status, category, keyword, contractId, page, size));
    }

    @ApiOperation("设备详情:配件树BOM/成本拆解/残值构成/故障档案/单台收益/状态机事件流")
    @GetMapping("/{id}")
    public R<AssetDetailResponse> detail(@PathVariable Long id) {
        return R.ok(assetService.detail(id));
    }

    @ApiOperation("新增设备(逐件建档,状态=采购)")
    @PostMapping
    public R<Long> create(@Validated @RequestBody AssetSaveRequest req) {
        return R.ok(assetService.create(req));
    }

    @ApiOperation("编辑设备")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Validated @RequestBody AssetSaveRequest req) {
        assetService.update(id, req);
        return R.ok();
    }

    @ApiOperation("状态流转(走事件流 + 状态机校验;流转到「投放」需老板)")
    @PostMapping("/{id}/status")
    public R<Void> changeStatus(@PathVariable Long id, @Validated @RequestBody StatusChangeRequest req) {
        assetService.changeStatus(id, req);
        return R.ok();
    }

    @ApiOperation("空置亮灯:收回待处置 + 投放超N天未起租(老板驾驶舱红点)")
    @GetMapping("/idle-alert")
    public R<IdleAlertResponse> idleAlert() {
        return R.ok(assetService.idleAlert());
    }

    @ApiOperation("投放/交付确认(敏感·投放审批→老板):采购/收回待处置→投放")
    @RequireRole(value = {"老板"}, action = "投放审批", targetType = "asset")
    @PostMapping("/{id}/deploy")
    public R<Void> deploy(@PathVariable Long id, @RequestParam(required = false) String remark) {
        assetService.deliver(id, remark);
        return R.ok();
    }

    @ApiOperation("新增配件树节点(parentId 空=一级总成)")
    @PostMapping("/{id}/bom")
    public R<Long> addBom(@PathVariable Long id, @Validated @RequestBody BomNodeRequest req) {
        return R.ok(assetService.addBom(id, req));
    }

    @ApiOperation("编辑配件树节点")
    @PutMapping("/bom/{bomId}")
    public R<Void> updateBom(@PathVariable Long bomId, @Validated @RequestBody BomNodeRequest req) {
        assetService.updateBom(bomId, req);
        return R.ok();
    }

    @ApiOperation("单台收益手工覆盖(某项传 null = 恢复自动计算;只影响设备展示)")
    @PutMapping("/{id}/single-unit-return")
    public R<Void> updateSingleUnitReturn(@PathVariable Long id,
                                          @Validated @RequestBody AssetLinkDtos.SingleUnitReturnRequest req) {
        assetService.updateSingleUnitReturn(id, req);
        return R.ok();
    }

    @ApiOperation("设置合同付款条件(自定义多段合计100%;预计付款=集采价×比例;采购入库设备同步重算待付应付,已付阶段锁定)")
    @PutMapping("/{id}/payment-terms")
    public R<Void> updatePaymentTerms(@PathVariable Long id, @Validated @RequestBody PaymentTermDtos.SaveRequest req) {
        assetService.updatePaymentTerms(id, req);
        return R.ok();
    }

    @ApiOperation("设置意向承接客户(未签约设备;customerId=null 清除;签约后以合同客户为准)")
    @PutMapping("/{id}/intended-customer")
    public R<Void> updateIntendedCustomer(@PathVariable Long id, @RequestBody AssetLinkDtos.IntendedCustomerRequest req) {
        assetService.updateIntendedCustomer(id, req);
        return R.ok();
    }

    @ApiOperation("配件 BOM 改数量/单价(合价自动计算;BOM 不参与合同价)")
    @PutMapping("/bom/{bomId}/pricing")
    public R<Void> updateBomPricing(@PathVariable Long bomId, @Validated @RequestBody BomPricingRequest req) {
        assetService.updateBomPricing(bomId, req);
        return R.ok();
    }

    @ApiOperation("故障档案编辑(按配件:故障次数/可维修/质保到期/质保方)")
    @PutMapping("/bom/{bomId}/fault")
    public R<Void> updateBomFault(@PathVariable Long bomId, @Validated @RequestBody BomFaultRequest req) {
        assetService.updateBomFault(bomId, req);
        return R.ok();
    }

    @ApiOperation("删除配件树节点(递归删后代)")
    @DeleteMapping("/bom/{bomId}")
    public R<Void> deleteBom(@PathVariable Long bomId) {
        assetService.deleteBom(bomId);
        return R.ok();
    }

    @ApiOperation("导出配件 BOM 明细 Excel(与合同清单同列 + BOM 字段;template=true 只导表头模板)")
    @RequireRole(value = {"老板", "财务", "供应链", "业务"}, action = "配件BOM导出", targetType = "asset")
    @GetMapping("/{id}/bom/export")
    public ResponseEntity<ByteArrayResource> exportBom(@PathVariable Long id,
                                                       @RequestParam(defaultValue = "false") boolean template) {
        Asset asset = assetService.requireAsset(id);
        byte[] data = bomExcelService.export(asset, template);
        String name = "配件BOM明细-" + asset.getSerialNo()
                + (template ? "-模板" : "-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)) + ".xlsx";
        ContentDisposition cd = ContentDisposition.attachment().filename(name, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(new ByteArrayResource(data));
    }

    @ApiOperation("导入配件 BOM 明细 Excel(整表替换;挂了附件的配件会拦下)")
    @RequireRole(value = {"老板", "财务", "供应链", "业务"}, action = "配件BOM导入", targetType = "asset")
    @PostMapping("/{id}/bom/import")
    public R<BomSheetDtos.ImportResult> importBom(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return R.ok(bomExcelService.importFile(assetService.requireAsset(id), file));
    }
}
