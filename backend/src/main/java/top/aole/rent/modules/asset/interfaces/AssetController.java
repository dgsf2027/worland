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
import top.aole.rent.common.result.PageResult;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.asset.dto.AssetDetailResponse;
import top.aole.rent.modules.asset.dto.AssetListItem;
import top.aole.rent.modules.asset.dto.AssetSaveRequest;
import top.aole.rent.modules.asset.dto.BomNodeRequest;
import top.aole.rent.modules.asset.dto.StatusChangeRequest;
import top.aole.rent.modules.asset.service.AssetService;

/**
 * 设备 · 逐件台账(M1-06/07)。台账/详情(配件树BOM/成本拆解/残值/故障档案/单台收益/状态机)/CRUD/状态流转/BOM 维护。
 * 敏感财务字段(集采价/账面价/成本)在 {@link AssetService} 按角色投影(GP/LP 打码)。
 */
@Api(tags = "设备·逐件台账")
@RestController
@RequestMapping("/rent/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    @ApiOperation("设备台账:按状态/品类/关键词筛选 + 分页")
    @GetMapping
    public R<PageResult<AssetListItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(assetService.list(status, category, keyword, page, size));
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

    @ApiOperation("状态流转(走事件流 + 状态机校验)")
    @PostMapping("/{id}/status")
    public R<Void> changeStatus(@PathVariable Long id, @Validated @RequestBody StatusChangeRequest req) {
        assetService.changeStatus(id, req);
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

    @ApiOperation("删除配件树节点(递归删后代)")
    @DeleteMapping("/bom/{bomId}")
    public R<Void> deleteBom(@PathVariable Long bomId) {
        assetService.deleteBom(bomId);
        return R.ok();
    }
}
