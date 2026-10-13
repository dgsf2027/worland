package top.aole.rent.modules.payproof.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.payproof.dto.PayProofDtos;
import top.aole.rent.modules.payproof.service.PayProofService;

import java.util.List;

@Api(tags = "资金回单")
@RestController
@RequestMapping("/rent/pay-proofs")
@RequiredArgsConstructor
public class PayProofController {

    private final PayProofService payProofService;

    @ApiOperation("某张单据的回单(bizType=payable/rent_bill/inv_damage)")
    @GetMapping
    public R<List<PayProofDtos.ProofView>> list(@RequestParam String bizType, @RequestParam Long bizId) {
        return R.ok(payProofService.list(bizType, bizId));
    }

    @ApiOperation("历史单据补传回单(已付/已核销/已收取)")
    @PostMapping
    public R<Void> supplement(@RequestBody PayProofDtos.SupplementRequest req) {
        payProofService.supplement(req);
        return R.ok();
    }
}
