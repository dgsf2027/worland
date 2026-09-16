package top.aole.rent.modules.roster.interfaces;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.auth.RequireRole;
import top.aole.rent.common.result.R;
import top.aole.rent.modules.roster.dto.RosterDtos;
import top.aole.rent.modules.roster.service.RosterService;
import top.aole.rent.modules.roster.service.RosterAccountService;
import top.aole.rent.common.exception.BizException;

import java.util.List;

/**
 * 花名册/权限/提成(M5-03)。花名册+角色权限矩阵(复用 DataScope);提成(降本/成交贡献·从管理费列支)。
 * 角色权限改动为敏感操作,{@link RequireRole} 统一切面卡「老板」+ 入 audit(M5-06)。
 */
@Api(tags = "花名册·权限·提成")
@RestController
@RequestMapping("/rent")
@RequiredArgsConstructor
public class RosterController {

    private final RosterService rosterService;
    private final RosterAccountService accountService;

    @ApiOperation("真实登录账号及生效权限（含门户首次登录账号）")
    @GetMapping("/roster/accounts")
    public R<List<RosterDtos.AccountItem>> accounts() {
        return R.ok(accountService.accounts());
    }

    @ApiOperation("按登录账号分配角色或启停账号；下一次请求生效")
    @RequireRole(value = {"老板"}, action = "角色权限变更", targetType = "auth_user")
    @PutMapping("/roster/accounts/{id}")
    public R<RosterDtos.AccountItem> updateAccount(@PathVariable Long id,
            @RequestBody RosterDtos.AccountUpdateRequest request) {
        return R.ok(accountService.update(id, request));
    }

    @ApiOperation("花名册:成员+角色+数据权限矩阵")
    @GetMapping("/roster")
    public R<List<RosterDtos.RosterItem>> roster() {
        return R.ok(rosterService.roster());
    }

    @ApiOperation("角色权限改动(敏感·老板):+ audit")
    @RequireRole(value = {"老板"}, action = "角色权限变更", targetType = "user_role_ext")
    @PutMapping("/roster/{id}")
    public R<RosterDtos.RosterItem> updateRole(@PathVariable Long id, @RequestBody RosterDtos.RoleUpdateRequest req) {
        // 旧 id 是花名册行主键，不能当作账号主键，避免旧客户端误改其他人的权限。
        throw new BizException(409, "权限管理已切换到登录账号，请刷新页面后在账号列表中授权");
    }

    @ApiOperation("提成计提(某期·集采降本/成交贡献·幂等)")
    @PostMapping("/commission/compute")
    public R<RosterDtos.ComputeResult> compute(@RequestParam String period) {
        return R.ok(rosterService.computeCommission(period));
    }

    @ApiOperation("提成拆解汇总(某期·可按 userId·每单降本/成交贡献可溯)")
    @GetMapping("/commission")
    public R<List<RosterDtos.CommissionSummary>> commission(
            @RequestParam String period,
            @RequestParam(required = false) Long userId) {
        return R.ok(rosterService.commissionSummary(period, userId));
    }
}
