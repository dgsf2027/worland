package top.aole.rent.modules.contract.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.contract.dto.ContractChangeRequest;
import top.aole.rent.modules.contract.dto.ContractDetailResponse;
import top.aole.rent.modules.contract.dto.ContractListItem;
import top.aole.rent.modules.contract.dto.ContractSignRequest;
import top.aole.rent.modules.contract.service.ContractService;

/**
 * 合同 · 签约与租金计划(M1-10/11/17/18)。签约自动生成 N 期 + 详情(勾稽/回款/每期构成/单笔P&L) + 作废/变更/续租。
 */
@Api(tags = "合同·签约与租金计划")
@RestController
@RequestMapping("/rent/contracts")
@RequiredArgsConstructor
public class ContractController {

    private final ContractService contractService;

    @ApiOperation("合同列表:按状态/客户/编号筛选 + 分页")
    @GetMapping
    public R<PageResult<ContractListItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(contractService.list(status, customerId, keyword, page, size));
    }

    @ApiOperation("签约:录要素→挂N台设备→自动生成租金计划N期→设备转在租→押金台账")
    @PostMapping
    public R<Long> sign(@Validated @RequestBody ContractSignRequest req) {
        return R.ok(contractService.sign(req));
    }

    @ApiOperation("合同详情:要点+勾稽校验行+回款进度+租金计划逐期+每期租金构成+单笔P&L")
    @GetMapping("/{id}")
    public R<ContractDetailResponse> detail(@PathVariable Long id) {
        return R.ok(contractService.detail(id));
    }

    @ApiOperation("作废(限未采购·整份红冲:计划删/押金退/设备释放)")
    @PostMapping("/{id}/void")
    public R<Void> voidContract(@PathVariable Long id, @RequestBody(required = false) ContractChangeRequest req) {
        contractService.voidContract(id, req);
        return R.ok();
    }

    @ApiOperation("续租:追加期数,重算租金计划")
    @PostMapping("/{id}/renew")
    public R<Void> renew(@PathVariable Long id, @RequestBody ContractChangeRequest req) {
        contractService.renew(id, req);
        return R.ok();
    }

    @ApiOperation("变更/提前结清:截断剩余未到期计划,合同关闭,押金期末抵")
    @PostMapping("/{id}/change")
    public R<Void> change(@PathVariable Long id, @RequestBody(required = false) ContractChangeRequest req) {
        contractService.change(id, req);
        return R.ok();
    }
}
