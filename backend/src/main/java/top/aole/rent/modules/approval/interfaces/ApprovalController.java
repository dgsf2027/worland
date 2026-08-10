package top.aole.rent.modules.approval.interfaces;

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
import top.aole.rent.common.auth.RequireRole;
import top.aole.rent.common.result.PageResult;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.approval.dto.ApprovalDtos;
import top.aole.rent.modules.approval.service.ApprovalService;

/**
 * 审批(M5-02)。投放审批:本金回报≥目标方可发起;300万内自主/超额协商。
 * 裁决(通过/驳回)为敏感操作,{@link RequireRole} 统一切面卡「老板」(P0-D)。
 */
@Api(tags = "审批·投放审批")
@RestController
@RequestMapping("/rent/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    @ApiOperation("审批列表:按状态/裁决方式筛选 + 分页")
    @GetMapping
    public R<PageResult<ApprovalDtos.ApprovalItem>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String decisionMode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return R.ok(approvalService.list(status, decisionMode, page, size));
    }

    @ApiOperation("发起投放审批(本金回报<目标→拒绝;≤300万自主/超额协商)")
    @PostMapping
    public R<Long> initiate(@Validated @RequestBody ApprovalDtos.InitiateRequest req) {
        return R.ok(approvalService.initiate(req));
    }

    @ApiOperation("审批详情")
    @GetMapping("/{id}")
    public R<ApprovalDtos.ApprovalItem> detail(@PathVariable Long id) {
        return R.ok(approvalService.detail(id));
    }

    @ApiOperation("通过(敏感·老板):自主/协商裁决 + audit")
    @RequireRole(value = {"老板"}, action = "投放审批", targetType = "approval")
    @PostMapping("/{id}/approve")
    public R<ApprovalDtos.ApprovalItem> approve(@PathVariable Long id, @RequestBody(required = false) ApprovalDtos.DecisionRequest req) {
        return R.ok(approvalService.approve(id, req));
    }

    @ApiOperation("驳回(敏感·老板):+ audit")
    @RequireRole(value = {"老板"}, action = "投放审批", targetType = "approval")
    @PostMapping("/{id}/reject")
    public R<ApprovalDtos.ApprovalItem> reject(@PathVariable Long id, @RequestBody(required = false) ApprovalDtos.DecisionRequest req) {
        return R.ok(approvalService.reject(id, req));
    }
}
