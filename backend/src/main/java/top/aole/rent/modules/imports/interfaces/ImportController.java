package top.aole.rent.modules.imports.interfaces;

import cn.hutool.json.JSONUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.imports.dto.ImportDtos;
import top.aole.rent.modules.imports.service.ImportService;

import java.util.List;
import java.util.Map;

/**
 * 导入中心接口(M5-07 · DESIGN §二)。映射/预览/去重 → 走同一校验+事件流入库。
 * 至少支持 供应商/客户/设备(target=supplier/customer/asset)。
 */
@Api(tags = "导入中心 · 映射/预览/去重(供应商/客户/设备)")
@RestController
@RequestMapping("/rent/imports")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    @ApiOperation("目标字段模板(前端列映射下拉;标必填/去重键)")
    @GetMapping("/template")
    public R<ImportDtos.TemplateResp> template(@RequestParam String target) {
        return R.ok(importService.template(target));
    }

    @ApiOperation("上传 Excel + 列映射 → 逐行预览(ok/dup/err + 公式转义计数),落作业(待确认)")
    @PostMapping("/preview")
    public R<ImportDtos.PreviewResp> preview(@RequestParam String target,
                                             @RequestParam("file") MultipartFile file,
                                             @RequestParam(required = false) String mapping,
                                             @RequestParam(required = false) Long projectId) {
        Map<String, String> mappingMap = (mapping == null || mapping.isEmpty())
                ? null : JSONUtil.toBean(mapping, Map.class);
        return R.ok(importService.preview(target, file, mappingMap, projectId));
    }

    @ApiOperation("确认入库:ok 行逐条走 service.create(同一校验+事件流);dup 跳过;幂等(已导入不可重复)")
    @PostMapping("/{jobId}/commit")
    public R<ImportDtos.CommitResp> commit(@PathVariable Long jobId) {
        return R.ok(importService.commit(jobId));
    }

    @ApiOperation("导入作业列表(target/status 筛选)")
    @GetMapping
    public R<List<ImportDtos.JobRow>> list(@RequestParam(required = false) String target,
                                           @RequestParam(required = false) String status) {
        return R.ok(importService.list(target, status));
    }
}
