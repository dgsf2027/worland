package top.aole.rent.modules.pcontract.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.auth.RequireRole;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.pcontract.dto.PcDtos;
import top.aole.rent.modules.pcontract.service.PurchaseContractService;

import java.util.List;

@Api(tags = "采购合同")
@RestController
@RequestMapping("/rent/purchase-contracts")
@RequiredArgsConstructor
public class PurchaseContractController {

    private final PurchaseContractService service;

    @ApiOperation("采购合同列表(含合同额/已付/已收票汇总)")
    @RequireRole(value = {"老板", "财务", "供应链"}, action = "采购合同查看", targetType = "purchase_contract")
    @GetMapping
    public R<List<PcDtos.ContractView>> list() {
        return R.ok(service.list());
    }

    @ApiOperation("采购合同详情(节点/付款/发票)")
    @RequireRole(value = {"老板", "财务", "供应链"}, action = "采购合同查看", targetType = "purchase_contract")
    @GetMapping("/{id}")
    public R<PcDtos.Detail> detail(@PathVariable Long id) {
        return R.ok(service.detail(id));
    }

    @ApiOperation("新建采购合同")
    @RequireRole(value = {"老板", "财务", "供应链"}, action = "采购合同创建", targetType = "purchase_contract")
    @PostMapping
    public R<Long> create(@RequestBody PcDtos.CreateRequest req) {
        return R.ok(service.create(req));
    }

    @ApiOperation("登记付款(须带回单,可一笔核销多个节点)")
    @RequireRole(value = {"财务", "老板"}, action = "采购合同付款", targetType = "purchase_contract")
    @PostMapping("/{id}/payments")
    public R<Long> pay(@PathVariable Long id, @RequestBody PcDtos.PaymentRequest req) {
        return R.ok(service.pay(id, req));
    }

    @ApiOperation("撤销付款(红冲付款凭证)")
    @RequireRole(value = {"财务", "老板"}, action = "采购合同撤销付款", targetType = "purchase_contract")
    @PostMapping("/payments/{paymentId}/void")
    public R<Void> voidPayment(@PathVariable Long paymentId, @RequestBody(required = false) PcDtos.VoidRequest req) {
        service.voidPayment(paymentId, req);
        return R.ok();
    }

    @ApiOperation("关联采购入库单(同供应商;关联后其应付随合同付款自动冲销)")
    @RequireRole(value = {"老板", "财务", "供应链"}, action = "采购合同关联采购单", targetType = "purchase_contract")
    @PostMapping("/{id}/purchases")
    public R<Void> linkPurchases(@PathVariable Long id, @RequestBody PcDtos.LinkRequest req) {
        service.linkPurchases(id, req);
        return R.ok();
    }

    @ApiOperation("解除采购入库单关联")
    @RequireRole(value = {"老板", "财务", "供应链"}, action = "采购合同解除关联", targetType = "purchase_contract")
    @PostMapping("/{id}/purchases/{purchaseInId}/unlink")
    public R<Void> unlinkPurchase(@PathVariable Long id, @PathVariable Long purchaseInId) {
        service.unlinkPurchase(id, purchaseInId);
        return R.ok();
    }

    @ApiOperation("登记进项发票")
    @RequireRole(value = {"财务", "老板"}, action = "采购合同收票", targetType = "purchase_contract")
    @PostMapping("/{id}/invoices")
    public R<Long> addInvoice(@PathVariable Long id, @RequestBody PcDtos.InvoiceRequest req) {
        return R.ok(service.addInvoice(id, req));
    }

    @ApiOperation("作废进项发票")
    @RequireRole(value = {"财务", "老板"}, action = "采购合同发票作废", targetType = "purchase_contract")
    @PostMapping("/invoices/{invoiceId}/void")
    public R<Void> voidInvoice(@PathVariable Long invoiceId, @RequestBody(required = false) PcDtos.VoidRequest req) {
        service.voidInvoice(invoiceId, req);
        return R.ok();
    }
}
