package top.aole.rent.modules.finance.interfaces;

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
import top.aole.rent.common.auth.RequireRole;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.billing.service.RentBillService;
import top.aole.rent.modules.finance.dto.VoucherDtos;
import top.aole.rent.modules.finance.service.DepreciationScheduler;
import top.aole.rent.modules.finance.service.DepreciationService;
import top.aole.rent.modules.finance.service.TaxThresholdService;
import top.aole.rent.modules.finance.service.VoucherService;

/**
 * 凭证中心 · 双账凭证查询/详情/红冲 + 折旧计提 + 500万营收红线(M3-01/02/08/11)。
 */
@Api(tags = "凭证中心·双账/折旧/500万红线")
@RestController
@RequestMapping("/rent")
@RequiredArgsConstructor
public class VoucherController {

    private final VoucherService voucherService;
    private final DepreciationService depreciationService;
    private final DepreciationScheduler depreciationScheduler;
    private final TaxThresholdService taxThresholdService;
    private final RentBillService rentBillService;

    // ============ M3-11 凭证查询/详情/红冲 ============

    @ApiOperation("凭证列表:按账套(tax/ops)/来源单据/记账期/是否红冲 筛选 + 分页")
    @GetMapping("/vouchers")
    public R<PageResult<VoucherDtos.VoucherItem>> list(
            @RequestParam(required = false) String book,
            @RequestParam(required = false) String sourceDocType,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) Boolean isReversal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(voucherService.list(book, sourceDocType, period, isReversal, page, size));
    }

    @ApiOperation("凭证详情:分录借贷 + 借贷平衡校验 + 双账对家凭证对照 + 红冲指向")
    @GetMapping("/vouchers/{id}")
    public R<VoucherDtos.VoucherDetail> detail(@PathVariable Long id) {
        return R.ok(voucherService.detail(id));
    }

    @ApiOperation("通用凭证红冲(敏感·财务+老板·P0-F 原子+幂等+锁账守卫·返回影响清单)")
    @RequireRole(value = {"财务", "老板"}, action = "凭证红冲", targetType = "voucher")
    @PostMapping("/vouchers/{id}/reverse")
    public R<VoucherDtos.VoucherReverseImpact> reverse(@PathVariable Long id,
                                                       @RequestBody(required = false) VoucherDtos.ReverseRequest req) {
        return R.ok(voucherService.reverse(id, req));
    }

    @ApiOperation("【M3-01】回填历史已核销单收入凭证(消稽核 matchedNoVoucher)")
    @RequireRole(value = {"财务", "老板"}, action = "凭证回填", targetType = "rent_bill")
    @PostMapping("/vouchers/backfill-rent-income")
    public R<VoucherDtos.BackfillResult> backfill() {
        return R.ok(rentBillService.backfillRentIncomeVouchers());
    }

    // ============ M3-08 500万营收红线(取税务账) ============

    @ApiOperation("500万营收红线:本年税务账已确认收入/剩余额度/预警档(正常/预警/超限)+ 经营账对照")
    @GetMapping("/tax/threshold")
    public R<VoucherDtos.TaxThreshold> taxThreshold(@RequestParam(required = false) Integer year) {
        return R.ok(taxThresholdService.threshold(year));
    }

    // ============ M3-02 折旧计提/查询 ============

    @ApiOperation("某设备折旧行(经营口径·逐月 book_value 递减)")
    @GetMapping("/assets/{assetId}/depreciation")
    public R<java.util.List<VoucherDtos.DepreciationLineItem>> depreciationLines(@PathVariable Long assetId) {
        return R.ok(depreciationService.linesOf(assetId));
    }

    @ApiOperation("【运维/测试】手动触发月度折旧计提(bizDate 缺省=今天;可指定记账月补提)")
    @PostMapping("/depreciation/run")
    public R<VoucherDtos.DepreciationRunResult> runDepreciation(
            @RequestParam(required = false) String bizDate) {
        if (bizDate != null && !bizDate.isEmpty()) {
            return R.ok(depreciationService.runMonthlyDepreciation(java.time.LocalDate.parse(bizDate)));
        }
        return R.ok(depreciationScheduler.runDepreciationCron());
    }
}
