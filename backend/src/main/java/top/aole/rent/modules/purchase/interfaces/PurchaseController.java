package top.aole.rent.modules.purchase.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.auth.RequireRole;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.purchase.dto.PurchaseDetailResponse;
import top.aole.rent.modules.purchase.dto.PurchaseEditDtos;
import top.aole.rent.modules.purchase.dto.PurchaseListItem;
import top.aole.rent.modules.purchase.dto.PurchaseOrderRequest;
import top.aole.rent.modules.purchase.dto.ReturnRequest;
import top.aole.rent.modules.purchase.service.PurchaseService;

/**
 * 采购 · 入库/应付/退货(M1-12/13)。下单(先签约后采购)→入库(逐件生成设备)→应付计划;退货红冲。
 * 退货红冲为敏感操作,{@link RequireRole} 统一切面卡「供应链+老板」(P0-D)。
 */
@Api(tags = "采购·入库应付")
@RestController
@RequestMapping("/rent/purchase")
@RequiredArgsConstructor
public class PurchaseController {

    private final PurchaseService purchaseService;

    @ApiOperation("采购单列表:按状态/合同/单号筛选 + 分页")
    @GetMapping
    public R<PageResult<PurchaseListItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long contractId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(purchaseService.list(status, contractId, keyword, page, size));
    }

    @ApiOperation("采购下单(先签约后采购:必绑存续合同,无合同拒建单)+ 生成首付应付")
    @PostMapping
    public R<Long> order(@Validated @RequestBody PurchaseOrderRequest req) {
        return R.ok(purchaseService.order(req));
    }

    @ApiOperation("采购单详情:明细(含生成设备)+ 应付计划(首付/验收/尾款)+ 合同/客户")
    @GetMapping("/{id}")
    public R<PurchaseDetailResponse> detail(@PathVariable Long id) {
        return R.ok(purchaseService.detail(id));
    }

    @ApiOperation("入库:逐件生成设备(状态=采购)+ 回填 + 生成验收/尾款应付")
    @PostMapping("/{id}/receive")
    public R<Void> receive(@PathVariable Long id) {
        purchaseService.receive(id);
        return R.ok();
    }

    // ============ 整单编辑(V120:试跑阶段要能把已录的单改对) ============

    @ApiOperation("编辑采购单头(单号/供应商/下单日/预计入库日/入库日/备注);已红冲只读")
    @RequireRole(value = {"老板", "财务", "供应链"}, action = "采购单编辑", targetType = "purchase_in")
    @PutMapping("/{id}")
    public R<PurchaseDetailResponse> editHeader(
            @PathVariable Long id, @Validated @RequestBody PurchaseEditDtos.HeaderRequest req) {
        purchaseService.editHeader(id, req);
        return R.ok(purchaseService.detail(id));
    }

    @ApiOperation("整体替换本单采购的设备(增/减);已付款的设备不允许移除")
    @RequireRole(value = {"老板", "财务", "供应链"}, action = "采购明细编辑", targetType = "purchase_in")
    @PutMapping("/{id}/items")
    public R<PurchaseDetailResponse> replaceItems(
            @PathVariable Long id, @Validated @RequestBody PurchaseEditDtos.ItemsRequest req) {
        purchaseService.replaceItems(id, req);
        return R.ok(purchaseService.detail(id));
    }

    @ApiOperation("编辑单条应付(阶段/金额/到期日/备注);改金额会标记为手工调整")
    @RequireRole(value = {"老板", "财务", "供应链"}, action = "应付编辑", targetType = "payable")
    @PutMapping("/payables/{payableId}")
    public R<Void> editPayable(
            @PathVariable Long payableId, @Validated @RequestBody PurchaseEditDtos.PayableRequest req) {
        purchaseService.editPayable(payableId, req);
        return R.ok();
    }

    @ApiOperation("登记付款(财务+老板):实付小于应付时差额自动拆一行继续待付")
    @RequireRole(value = {"财务", "老板"}, action = "应付登记付款", targetType = "payable")
    @PostMapping("/payables/{payableId}/pay")
    public R<Void> payPayable(
            @PathVariable Long payableId, @Validated @RequestBody PurchaseEditDtos.PayRequest req) {
        purchaseService.payPayable(payableId, req);
        return R.ok();
    }

    @ApiOperation("撤销付款(财务+老板·误登记用):退回待付并清实付日")
    @RequireRole(value = {"财务", "老板"}, action = "应付撤销付款", targetType = "payable")
    @PostMapping("/payables/{payableId}/unpay")
    public R<Void> unpayPayable(@PathVariable Long payableId) {
        purchaseService.unpayPayable(payableId);
        return R.ok();
    }

    @ApiOperation("退货红冲(敏感·供应链+老板):设备报废释放 + 应付红字 + 单据红冲")
    @RequireRole(value = {"供应链", "老板"}, action = "采购退货", targetType = "purchase_in")
    @PostMapping("/{id}/return")
    public R<Void> returnOrder(@PathVariable Long id, @Validated @RequestBody ReturnRequest req) {
        purchaseService.returnOrder(id, req);
        return R.ok();
    }
}
