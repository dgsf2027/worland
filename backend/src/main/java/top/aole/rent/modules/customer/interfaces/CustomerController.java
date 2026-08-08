package top.aole.rent.modules.customer.interfaces;

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
import top.aole.rent.common.result.PageResult;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.customer.dto.AdmissionRequest;
import top.aole.rent.modules.customer.dto.CustomerDetailResponse;
import top.aole.rent.modules.customer.dto.CustomerPoolItem;
import top.aole.rent.modules.customer.dto.CustomerSaveRequest;
import top.aole.rent.modules.customer.dto.FollowupRequest;
import top.aole.rent.modules.customer.dto.PipelineResponse;
import top.aole.rent.modules.customer.service.CustomerService;

/**
 * 客户 · CRM(M1-03/04/05/09)。客户池/销售管道/详情/跟进/风控准入。
 * 行级+字段级隔离(P0-E)在 {@link CustomerService} 服务端强制。
 */
@Api(tags = "客户·CRM")
@RestController
@RequestMapping("/rent/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @ApiOperation("客户池:按阶段/评级/负责人/关键词筛选 + 分页(业务只见自己+公海)")
    @GetMapping
    public R<PageResult<CustomerPoolItem>> pool(
            @RequestParam(required = false) String phase,
            @RequestParam(required = false) String rating,
            @RequestParam(required = false) Long owner,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(customerService.pool(phase, rating, owner, keyword, page, size));
    }

    @ApiOperation("销售管道看板:按阶段分组 + 加权预测")
    @GetMapping("/pipeline")
    public R<PipelineResponse> pipeline() {
        return R.ok(customerService.pipeline());
    }

    @ApiOperation("客户详情:信用画像/LTV/风险敞口/跟进时间线/准入建议")
    @GetMapping("/{id}")
    public R<CustomerDetailResponse> detail(@PathVariable Long id) {
        return R.ok(customerService.detail(id));
    }

    @ApiOperation("新增客户")
    @PostMapping
    public R<Long> create(@Validated @RequestBody CustomerSaveRequest req) {
        return R.ok(customerService.create(req));
    }

    @ApiOperation("编辑客户")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Validated @RequestBody CustomerSaveRequest req) {
        customerService.update(id, req);
        return R.ok();
    }

    @ApiOperation("记一次跟进(同步下次跟进日)")
    @PostMapping("/{id}/followup")
    public R<Long> followup(@PathVariable Long id, @Validated @RequestBody FollowupRequest req) {
        return R.ok(customerService.addFollowup(id, req));
    }

    @ApiOperation("风控准入结论:按评级出授信/押金/目标IRR(可覆盖;拒绝留痕)")
    @PostMapping("/{id}/admission")
    public R<CustomerDetailResponse.Admission> admission(@PathVariable Long id, @RequestBody AdmissionRequest req) {
        return R.ok(customerService.admission(id, req));
    }
}
