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
import top.aole.rent.modules.billing.service.OverdueScanScheduler;
import top.aole.rent.modules.billing.service.OverdueService;

/**
 * 逾期 · 三步走处置 + 还款恢复 + 收回(M2-05/06)。
 * 处置端点采用英文动词(与仓库既有 /void /renew /deploy 一致):extend=延期 penalty=罚息 lock=锁机 repossess=收回。
 * 内外一视同仁,物权在我方;每案必裁决人 owner + 期限 deadline。
 */
@Api(tags = "逾期·三步走处置")
@RestController
@RequestMapping("/rent/overdue")
@RequiredArgsConstructor
public class OverdueController {

    private final OverdueService overdueService;
    private final OverdueScanScheduler overdueScanScheduler;

    @ApiOperation("逾期案列表:按案态/合同筛选 + 分页")
    @GetMapping
    public R<PageResult<BillDtos.OverdueItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long contractId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(overdueService.list(status, contractId, page, size));
    }

    @ApiOperation("逾期案详情")
    @GetMapping("/{id}")
    public R<BillDtos.OverdueItem> detail(@PathVariable Long id) {
        return R.ok(overdueService.detail(id));
    }

    @ApiOperation("延期(展期·裁决人+期限)")
    @RequireRole(value = {"财务", "老板"}, action = "逾期延期", targetType = "overdue_case")
    @PostMapping("/{id}/extend")
    public R<BillDtos.OverdueItem> extend(@PathVariable Long id,
                                          @RequestBody(required = false) BillDtos.OverdueActionRequest req) {
        return R.ok(overdueService.extend(id, req));
    }

    @ApiOperation("罚息(按 rule 罚息率计罚息单·累计)")
    @RequireRole(value = {"财务", "老板"}, action = "逾期罚息", targetType = "overdue_case")
    @PostMapping("/{id}/penalty")
    public R<BillDtos.OverdueItem> penalty(@PathVariable Long id,
                                           @RequestBody(required = false) BillDtos.OverdueActionRequest req) {
        return R.ok(overdueService.penalty(id, req));
    }

    @ApiOperation("锁机(物权主张·内外一视同仁)")
    @RequireRole(value = {"财务", "老板"}, action = "逾期锁机", targetType = "overdue_case")
    @PostMapping("/{id}/lock")
    public R<BillDtos.OverdueItem> lock(@PathVariable Long id,
                                        @RequestBody(required = false) BillDtos.OverdueActionRequest req) {
        return R.ok(overdueService.lock(id, req));
    }

    @ApiOperation("收回(生成收回单+设备转收回待处置·结案)")
    @RequireRole(value = {"财务", "老板"}, action = "逾期收回", targetType = "overdue_case")
    @PostMapping("/{id}/repossess")
    public R<BillDtos.OverdueItem> repossess(@PathVariable Long id,
                                             @RequestBody(required = false) BillDtos.OverdueActionRequest req) {
        return R.ok(overdueService.repossess(id, req));
    }

    @ApiOperation("还款恢复(核销逾期单·案关闭)")
    @PostMapping("/{id}/repay")
    public R<BillDtos.RepaymentResult> repay(@PathVariable Long id,
                                             @RequestBody(required = false) BillDtos.MatchRequest req) {
        return R.ok(overdueService.repay(id, req));
    }

    @ApiOperation("【运维/测试】手动触发逾期检测 cron")
    @PostMapping("/scan/run")
    public R<BillDtos.OverdueScanResult> runScan() {
        return R.ok(overdueScanScheduler.runOverdueScanCron());
    }
}
