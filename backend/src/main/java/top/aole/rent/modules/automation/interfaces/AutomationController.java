package top.aole.rent.modules.automation.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.aole.rent.common.auth.RequireRole;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.automation.domain.AutomationLog;
import top.aole.rent.modules.automation.domain.AutomationRule;
import top.aole.rent.modules.automation.domain.BankStatement;
import top.aole.rent.modules.automation.domain.NotifyChannel;
import top.aole.rent.modules.automation.dto.AutomationDtos;
import top.aole.rent.modules.automation.service.AutomationEngineService;
import top.aole.rent.modules.automation.service.BankReconcileService;

import javax.validation.Valid;
import java.util.List;

/**
 * 自动化中心与银行流水智能核销控制器（一期 + 二期 · V132）。
 */
@Api(tags = "自动化中心 · 总控台/推送/催收/银行流水核销")
@RestController
@RequestMapping("/rent/automation")
@RequiredArgsConstructor
public class AutomationController {

    private final AutomationEngineService engineService;
    private final BankReconcileService bankReconcileService;

    // ---- 一期：规则总控台 & 外推通道 & 日志 ----

    @ApiOperation("自动化规则列表")
    @RequireRole({"老板", "财务", "供应链", "销售", "业务"})
    @GetMapping("/rules")
    public R<List<AutomationRule>> listRules() {
        return R.ok(engineService.listRules());
    }

    @ApiOperation("修改自动化规则（开关/提前天数/阈值）")
    @RequireRole({"老板", "财务"})
    @PutMapping("/rules/{ruleKey}")
    public R<AutomationRule> updateRule(@PathVariable String ruleKey,
                                        @RequestBody AutomationDtos.RuleUpdateRequest req) {
        return R.ok(engineService.updateRule(ruleKey, req));
    }

    @ApiOperation("立即手动试跑指定自动化规则")
    @RequireRole({"老板", "财务"})
    @PostMapping("/rules/{ruleKey}/run")
    public R<AutomationDtos.RunResult> runRule(@PathVariable String ruleKey) {
        return R.ok(engineService.runRule(ruleKey, "MANUAL", true));
    }

    @ApiOperation("外推消息通道列表")
    @RequireRole({"老板", "财务"})
    @GetMapping("/channels")
    public R<List<NotifyChannel>> listChannels() {
        return R.ok(engineService.listChannels());
    }

    @ApiOperation("新增或更新外推通道（企微/钉钉/飞书/通用Webhook）")
    @RequireRole({"老板", "财务"})
    @PostMapping("/channels")
    public R<NotifyChannel> saveChannel(@Valid @RequestBody AutomationDtos.ChannelSaveRequest req) {
        return R.ok(engineService.saveChannel(req));
    }

    @ApiOperation("删除外推通道")
    @RequireRole({"老板", "财务"})
    @DeleteMapping("/channels/{id}")
    public R<Void> deleteChannel(@PathVariable Long id) {
        engineService.deleteChannel(id);
        return R.ok(null);
    }

    @ApiOperation("发送通道连通性测试消息")
    @RequireRole({"老板", "财务"})
    @PostMapping("/channels/{id}/test")
    public R<String> testChannel(@PathVariable Long id) {
        return R.ok(engineService.testChannel(id));
    }

    @ApiOperation("自动化执行日志")
    @RequireRole({"老板", "财务", "供应链", "销售", "业务"})
    @GetMapping("/logs")
    public R<List<AutomationLog>> listLogs(@RequestParam(required = false) String ruleKey,
                                           @RequestParam(defaultValue = "50") int limit) {
        return R.ok(engineService.listLogs(ruleKey, limit));
    }

    // ---- 二期：逾期阶梯催款通知函 ----

    @ApiOperation("获取预提醒与逾期催款通知函清单（可直接复制发客户）")
    @RequireRole({"老板", "财务", "销售", "业务"})
    @GetMapping("/collection-notices")
    public R<List<AutomationDtos.CollectionNotice>> collectionNotices(@RequestParam(required = false) Integer leadDays) {
        return R.ok(engineService.listCollectionNotices(leadDays));
    }

    // ---- 二期：银行流水智能匹配与一键批量核销 ----

    @ApiOperation("银行流水台账列表")
    @RequireRole({"老板", "财务"})
    @GetMapping("/bank-statements")
    public R<List<BankStatement>> listStatements(@RequestParam(required = false) String matchStatus) {
        return R.ok(bankReconcileService.list(matchStatus));
    }

    @ApiOperation("可匹配的待收租金单候选列表")
    @RequireRole({"老板", "财务"})
    @GetMapping("/bank-statements/unpaid-bills")
    public R<List<AutomationDtos.UnpaidBillOption>> unpaidBills() {
        return R.ok(bankReconcileService.listUnpaidBills());
    }

    @ApiOperation("上传银行流水 CSV/Excel 并自动智能匹配")
    @RequireRole({"老板", "财务"})
    @PostMapping("/bank-statements/import")
    public R<AutomationDtos.ImportResult> importStatements(@RequestParam("file") MultipartFile file) {
        return R.ok(bankReconcileService.importFile(file));
    }

    @ApiOperation("手工批量提交银行流水并自动匹配")
    @RequireRole({"老板", "财务"})
    @PostMapping("/bank-statements/manual")
    public R<AutomationDtos.ImportResult> manualStatements(@RequestBody List<AutomationDtos.ManualStatementInput> rows) {
        return R.ok(bankReconcileService.ingestAndMatch(rows));
    }

    @ApiOperation("手工改绑某条银行流水的目标收租单")
    @RequireRole({"老板", "财务"})
    @PutMapping("/bank-statements/{id}/reassign")
    public R<BankStatement> reassign(@PathVariable Long id, @RequestBody AutomationDtos.ReassignRequest req) {
        return R.ok(bankReconcileService.reassign(id, req == null ? null : req.getBillId()));
    }

    @ApiOperation("忽略某条非租金银行流水")
    @RequireRole({"老板", "财务"})
    @PostMapping("/bank-statements/{id}/ignore")
    public R<Void> ignore(@PathVariable Long id) {
        bankReconcileService.ignore(id);
        return R.ok(null);
    }

    @ApiOperation("一键批量核销已匹配的银行流水（自动记账凭证 + 启动 3% 开票倒计时）")
    @RequireRole({"老板", "财务"})
    @PostMapping("/bank-statements/reconcile")
    public R<AutomationDtos.ReconcileResult> reconcile(@RequestBody AutomationDtos.ReconcileRequest req) {
        return R.ok(bankReconcileService.confirmBatch(req == null ? null : req.getStatementIds()));
    }
}
