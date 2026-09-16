package top.aole.rent.modules.roster.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.*;
import top.aole.rent.common.exception.BizException;
import top.aole.rent.modules.roster.dto.RosterDtos;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** 授权与鉴权共同使用账号表；历史花名册仅用于原有提成归属，不按姓名猜测绑定。 */
@Service
@RequiredArgsConstructor
public class RosterAccountService {
    private static final List<String> ROLES = Arrays.asList("老板", "财务", "供应链", "业务", "GP", "LP");
    private final AuthUserMapper userMapper;
    private final AuditLogService audit;

    public List<RosterDtos.AccountItem> accounts() {
        UserContext.require();
        return userMapper.selectList(new LambdaQueryWrapper<AuthUser>()
                        .select(AuthUser::getId, AuthUser::getUsername, AuthUser::getDisplayName,
                                AuthUser::getRole, AuthUser::getStatus, AuthUser::getPortalUid, AuthUser::getLastLoginAt)
                        .orderByAsc(AuthUser::getId)).stream()
                .map(this::toItem).collect(Collectors.toList());
    }

    @Transactional
    public RosterDtos.AccountItem update(Long accountId, RosterDtos.AccountUpdateRequest request) {
        CurrentUser current = UserContext.require();
        if (current.getAccountId() == null || !"老板".equals(current.getRole())) {
            deny(accountId, "仅已登录的老板账号可管理权限");
        }
        if (request.getRole() != null && !ROLES.contains(request.getRole())) {
            throw new BizException(400, "请选择有效角色：老板、财务、供应链、业务、GP 或 LP");
        }
        // 所有授权写入按同一顺序锁定账号。等待锁后重新验证，避免已被降级的请求继续授权。
        List<AuthUser> locked = userMapper.lockAccountsForPermissionChange();
        AuthUser actor = find(locked, current.getAccountId());
        if (actor == null || !enabledBoss(actor)) {
            deny(accountId, "你的管理权限已变更，请刷新页面");
        }
        AuthUser target = find(locked, accountId);
        if (target == null) throw new BizException(404, "登录账号不存在，请刷新列表");
        String nextRole = request.getRole() == null ? target.getRole() : request.getRole();
        Integer nextStatus = request.getActive() == null ? target.getStatus() : (request.getActive() ? 1 : 0);
        if (enabledBoss(target) && (!"老板".equals(nextRole) || !Integer.valueOf(1).equals(nextStatus))
                && locked.stream().filter(this::enabledBoss).count() <= 1) {
            throw new BizException(409, "必须保留至少一个启用的老板账号，请先授权另一位管理员");
        }
        String detail = "操作者账号 #" + current.getAccountId() + "；目标账号 " + target.getUsername()
                + " (#" + accountId + ")；角色 " + target.getRole() + "→" + nextRole
                + "；账号状态 " + target.getStatus() + "→" + nextStatus;
        userMapper.update(null, new LambdaUpdateWrapper<AuthUser>().eq(AuthUser::getId, accountId)
                .set(AuthUser::getRole, nextRole).set(AuthUser::getStatus, nextStatus));
        audit.record("角色权限变更", "auth_user", accountId, AuditLogService.EXECUTED, detail);
        target.setRole(nextRole);
        target.setStatus(nextStatus);
        return toItem(target);
    }

    private void deny(Long accountId, String message) {
        audit.record("角色权限变更", "auth_user", accountId, AuditLogService.DENIED, message);
        throw new BizException(403, message);
    }

    private AuthUser find(List<AuthUser> users, Long id) {
        return users.stream().filter(u -> u.getId().equals(id)).findFirst().orElse(null);
    }

    private boolean enabledBoss(AuthUser user) {
        return "老板".equals(user.getRole()) && Integer.valueOf(1).equals(user.getStatus());
    }

    private RosterDtos.AccountItem toItem(AuthUser user) {
        RosterDtos.AccountItem item = new RosterDtos.AccountItem();
        item.setAccountId(user.getId());
        item.setUsername(user.getUsername());
        item.setDisplayName(user.getDisplayName());
        item.setRole(user.getRole());
        item.setActive(Integer.valueOf(1).equals(user.getStatus()));
        item.setCostVisible(DataScope.canSeeCost(user.getRole()));
        item.setOwnerScoped(DataScope.isOwnerScoped(user.getRole()));
        item.setLoginSource(user.getPortalUid() == null ? "本地账号" : "门户 SSO");
        item.setLastLoginAt(user.getLastLoginAt());
        return item;
    }
}
