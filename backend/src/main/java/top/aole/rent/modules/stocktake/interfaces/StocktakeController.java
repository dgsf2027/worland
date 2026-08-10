package top.aole.rent.modules.stocktake.interfaces;

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
import top.aole.rent.modules.stocktake.dto.StocktakeDtos;
import top.aole.rent.modules.stocktake.service.StocktakeService;

/**
 * 盘点(M4-05)。扫码盘点账面带出只录差异 → 账实差异生成盘盈亏调整单闭合(走单据不直改台账·§4.24)。
 */
@Api(tags = "盘点·盘盈亏调整")
@RestController
@RequestMapping("/rent/stocktake")
@RequiredArgsConstructor
public class StocktakeController {

    private final StocktakeService stocktakeService;

    @ApiOperation("盘点单列表(按状态筛选)")
    @GetMapping
    public R<PageResult<StocktakeDtos.StocktakeItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(stocktakeService.list(status, page, size));
    }

    @ApiOperation("盘点单详情(差异行)")
    @GetMapping("/{id}")
    public R<StocktakeDtos.StocktakeDetail> detail(@PathVariable Long id) {
        return R.ok(stocktakeService.detail(id));
    }

    @ApiOperation("建盘点单(账面带出应盘台数)")
    @PostMapping
    public R<Long> create(@RequestBody StocktakeDtos.CreateRequest req) {
        return R.ok(stocktakeService.create(req));
    }

    @ApiOperation("扫码盘点提交(只录差异)")
    @PostMapping("/{id}/scan")
    public R<StocktakeDtos.StocktakeDetail> scan(@PathVariable Long id, @RequestBody StocktakeDtos.ScanRequest req) {
        return R.ok(stocktakeService.scan(id, req));
    }

    @ApiOperation("闭合(逐差异生成盘盈亏调整单·对齐台账留痕)")
    @PostMapping("/{id}/close")
    public R<StocktakeDtos.CloseResult> close(@PathVariable Long id) {
        return R.ok(stocktakeService.close(id));
    }
}
