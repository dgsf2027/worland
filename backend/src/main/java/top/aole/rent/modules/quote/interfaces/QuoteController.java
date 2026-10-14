package top.aole.rent.modules.quote.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.quote.dto.QuoteRequest;
import top.aole.rent.modules.quote.dto.QuoteResponse;
import top.aole.rent.modules.quote.service.RentQuoteService;

/**
 * 报价测算器(S0-06)。POST /api/rent/quote/calc。
 */
@Api(tags = "报价测算")
@RestController
@RequestMapping("/rent/quote")
@RequiredArgsConstructor
public class QuoteController {

    private final RentQuoteService quoteService;

    @ApiOperation("报价测算(曜石测算表简版口径):市场价+采购成本+品类+客户类型 → 月租(可反解)/租赁公司·客户·实际成本三视角")
    @PostMapping("/calc")
    public R<QuoteResponse> calc(@Validated @RequestBody QuoteRequest req) {
        return R.ok(quoteService.calc(req));
    }
}
