package top.aole.rent.modules.transfer.interfaces;

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
import top.aole.rent.modules.transfer.dto.TransferDtos;
import top.aole.rent.modules.transfer.service.TransferService;

/**
 * 转让/处置(M4-01/02/03)。到期转让(逐台残值凭证+资产出账+合同关闭)/ 名义价守卫审批 / 复投飞轮(再投放/二手/报废)。
 *
 * <p>敏感操作角色守卫(P0-D):转让/二手/报废过账 → 财务+老板;再投放(投放决策) → 老板。
 */
@Api(tags = "转让·处置(到期转让/复投飞轮)")
@RestController
@RequestMapping("/rent/transfer")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    @ApiOperation("转让/处置单列表(按类型/状态/合同筛选)")
    @GetMapping
    public R<PageResult<TransferDtos.TransferItem>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long contractId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(transferService.list(type, status, contractId, page, size));
    }

    @ApiOperation("转让/处置单详情(逐台行:账面快照/损益/凭证)")
    @GetMapping("/{id}")
    public R<TransferDtos.TransferDetail> detail(@PathVariable Long id) {
        return R.ok(transferService.detail(id));
    }

    @ApiOperation("到期转让:挂合同N台→逐台残值凭证+资产出账→合同关闭(名义价守卫触发则待审批)")
    @RequireRole(value = {"财务", "老板"}, action = "到期转让", targetType = "transfer_order")
    @PostMapping("/expiry")
    public R<TransferDtos.TransferResult> expiry(@RequestBody TransferDtos.ExpiryTransferRequest req) {
        return R.ok(transferService.createExpiryTransfer(req));
    }

    @ApiOperation("名义价守卫审批过账(财务/老板)")
    @RequireRole(value = {"财务", "老板"}, action = "转让审批过账", targetType = "transfer_order")
    @PostMapping("/{id}/approve")
    public R<TransferDtos.TransferDetail> approve(@PathVariable Long id, @RequestBody(required = false) TransferDtos.ApproveRequest req) {
        return R.ok(transferService.approve(id, req));
    }

    @ApiOperation("复投飞轮·二手/报废处置(收回待处置→已转让/报废·财务+老板)")
    @RequireRole(value = {"财务", "老板"}, action = "二手报废处置", targetType = "transfer_order")
    @PostMapping("/dispose")
    public R<TransferDtos.TransferResult> dispose(@RequestBody TransferDtos.DisposeRequest req) {
        return R.ok(transferService.dispose(req));
    }

    @ApiOperation("复投飞轮·再投放(收回待处置→投放·回在租池·老板)")
    @RequireRole(value = {"老板"}, action = "再投放", targetType = "asset")
    @PostMapping("/redeploy")
    public R<TransferDtos.TransferResult> redeploy(@RequestBody TransferDtos.DisposeRequest req) {
        if (req != null) {
            req.setAction("再投放");
        }
        return R.ok(transferService.dispose(req));
    }
}
