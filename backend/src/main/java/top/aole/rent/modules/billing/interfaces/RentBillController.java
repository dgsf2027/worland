package top.aole.rent.modules.billing.interfaces;

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
import top.aole.rent.modules.billing.dto.BillDtos;
import top.aole.rent.modules.billing.service.RentBillGenScheduler;
import top.aole.rent.modules.billing.service.RentBillService;

import java.util.List;

/**
 * 收租 · 收租单/核销/红冲/退款/稽核(M2-01/02/03/04)。
 */
@Api(tags = "收租·收租单与核销")
@RestController
@RequestMapping("/rent")
@RequiredArgsConstructor
public class RentBillController {

    private final RentBillService rentBillService;
    private final RentBillGenScheduler rentBillGenScheduler;

    @ApiOperation("收租单列表:按状态/合同/性质筛选 + 分页")
    @GetMapping("/bills")
    public R<PageResult<BillDtos.BillItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long contractId,
            @RequestParam(required = false) String billKind,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(rentBillService.list(status, contractId, billKind, page, size));
    }

    @ApiOperation("收租单详情:主单 + 红冲/退款关联单 + 逾期案")
    @GetMapping("/bills/{id}")
    public R<BillDtos.BillDetail> detail(@PathVariable Long id) {
        return R.ok(rentBillService.detail(id));
    }

    @ApiOperation("到账核销:金额一致自动核销(不一致人工处理)")
    @PostMapping("/bills/{id}/match")
    public R<BillDtos.BillItem> match(@PathVariable Long id,
                                      @RequestBody(required = false) BillDtos.MatchRequest req) {
        return R.ok(rentBillService.match(id, req));
    }

    @ApiOperation("批量核销:按各单应收全额核销")
    @PostMapping("/bills/batch-match")
    public R<List<BillDtos.BillItem>> batchMatch(@RequestBody BillDtos.BatchMatchRequest req) {
        return R.ok(rentBillService.batchMatch(req.getIds()));
    }

    @ApiOperation("红冲(敏感·财务+老板·P0-F 原子+幂等+锁账守卫·返回影响清单)")
    @RequireRole(value = {"财务", "老板"}, action = "收租红冲", targetType = "rent_bill")
    @PostMapping("/bills/{id}/reverse")
    public R<BillDtos.ReverseImpact> reverse(@PathVariable Long id,
                                             @RequestBody(required = false) BillDtos.ReverseRequest req) {
        return R.ok(rentBillService.reverse(id, req));
    }

    @ApiOperation("退款(敏感·财务+老板·对已核销单退回款项·负额退款单)")
    @RequireRole(value = {"财务", "老板"}, action = "收租退款", targetType = "rent_bill")
    @PostMapping("/bills/{id}/refund")
    public R<BillDtos.BillItem> refund(@PathVariable Long id,
                                       @RequestBody(required = false) BillDtos.RefundRequest req) {
        return R.ok(rentBillService.refund(id, req));
    }

    @ApiOperation("钱该动没动稽核:到期未生成单N / 已到账未核销N / 已核销缺凭证N")
    @GetMapping("/audit/cash-check")
    public R<BillDtos.CashCheck> cashCheck() {
        return R.ok(rentBillService.cashCheck());
    }

    @ApiOperation("【运维/测试】手动触发收租单生成 cron")
    @PostMapping("/bills/gen/run")
    public R<BillDtos.GenResult> runGen() {
        return R.ok(rentBillGenScheduler.runRentBillGenCron());
    }
}
