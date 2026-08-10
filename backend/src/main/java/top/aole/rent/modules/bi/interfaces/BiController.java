package top.aole.rent.modules.bi.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.bi.dto.BiDtos;
import top.aole.rent.modules.bi.service.BiService;

/**
 * BI 多维矩阵接口(M5-04 · 业务流§8.4)。只读聚合:在租率/加权回报/应收账龄/资产周转,
 * 可按 品类×客户×供应商 下钻 + 回款近 N 月趋势。数与源模块一致(验收逐维对齐 SQL)。
 */
@Api(tags = "BI · 多维矩阵(在租率/回报/账龄/周转/趋势)")
@RestController
@RequestMapping("/rent/bi")
@RequiredArgsConstructor
public class BiController {

    private final BiService biService;

    @ApiOperation("在租率矩阵:总览 + 按 品类/供应商 下钻(在租/(总−报废))")
    @GetMapping("/occupancy")
    public R<BiDtos.MatrixResp> occupancy(@RequestParam(required = false) String dim) {
        return R.ok(biService.occupancy(dim));
    }

    @ApiOperation("加权回报矩阵:Σ(目标IRR×月租)/Σ月租,按 客户/性质 下钻")
    @GetMapping("/weighted-return")
    public R<BiDtos.MatrixResp> weightedReturn(@RequestParam(required = false) String dim) {
        return R.ok(biService.weightedReturn(dim));
    }

    @ApiOperation("应收账龄:0-30/31-60/61-90/90+ 分桶 + 按客户下钻")
    @GetMapping("/receivable-aging")
    public R<BiDtos.AgingResp> receivableAging() {
        return R.ok(biService.receivableAging());
    }

    @ApiOperation("资产周转(投放率)矩阵:(在租+已转让)/(总−报废),按品类下钻")
    @GetMapping("/asset-turnover")
    public R<BiDtos.MatrixResp> assetTurnover(@RequestParam(required = false) String dim) {
        return R.ok(biService.assetTurnover(dim));
    }

    @ApiOperation("近 N 月趋势:metric=collection(回款额)/receivable(应收额),months 默认 6")
    @GetMapping("/trend")
    public R<BiDtos.TrendResp> trend(@RequestParam(required = false) String metric,
                                     @RequestParam(required = false) Integer months) {
        return R.ok(biService.trend(metric, months));
    }
}
