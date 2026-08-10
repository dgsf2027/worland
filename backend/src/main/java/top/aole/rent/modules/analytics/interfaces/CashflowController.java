package top.aole.rent.modules.analytics.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.analytics.dto.CashflowDtos;
import top.aole.rent.modules.analytics.service.CashflowService;
import top.aole.rent.modules.analytics.service.CoverageGapService;
import top.aole.rent.modules.analytics.service.ReturnAttributionService;

import java.math.BigDecimal;

/**
 * 现金流驾驶舱 · 账期兑付缺口预警 · 回报四源(M3-05/06/09)。老板/财务驾驶舱数据源。
 */
@Api(tags = "现金流驾驶舱·兑付缺口·回报四源")
@RestController
@RequestMapping("/rent")
@RequiredArgsConstructor
public class CashflowController {

    private final CashflowService cashflowService;
    private final CoverageGapService coverageGapService;
    private final ReturnAttributionService returnAttributionService;

    @ApiOperation("现金流驾驶舱:应收/应付按到期分层 + 未来 N 月净现金流预测曲线 + 三层杠杆资金占用")
    @GetMapping("/cashflow")
    public R<CashflowDtos.Cashflow> cashflow(@RequestParam(required = false) Integer months) {
        return R.ok(cashflowService.cashflow(months));
    }

    @ApiOperation("账期兑付缺口预警(P0-H):每笔应付到期 − 可用回款 − 可动留存 <0 → 红灯 + 裁决人 + 补款来源")
    @GetMapping("/cashflow/coverage-gap")
    public R<CashflowDtos.CoverageGapReport> coverageGap(@RequestParam(required = false) Integer tMinusDays) {
        return R.ok(coverageGapService.coverageGap(tMinusDays));
    }

    @ApiOperation("回报四源:总税后 IRR 拆 集采差价+资金时间价值+价值定价+残值回收(四者之和=总IRR·可勾稽)")
    @GetMapping("/analytics/return-attribution")
    public R<CashflowDtos.ReturnAttribution> returnAttribution(
            @RequestParam(required = false) BigDecimal totalIrr,
            @RequestParam(required = false) String customerType) {
        return R.ok(returnAttributionService.attribution(totalIrr, customerType));
    }
}
