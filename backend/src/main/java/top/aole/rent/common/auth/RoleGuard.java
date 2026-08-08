package top.aole.rent.common.auth;

import top.aole.rent.common.exception.BizException;

import java.util.Arrays;

/**
 * 角色守卫(assertRole 式)。照园区小卖账房 assertBossRole 思路,收敛为单一入口。
 *
 * <p>占位期用于敏感操作粗粒度卡权限;M1-15 会升级为「敏感操作×角色」RBAC 矩阵 + 统一鉴权切面
 * (先卡权限再执行,越权入 audit)。本类为其前置基座。
 */
public final class RoleGuard {

    private RoleGuard() {
    }

    /** 断言当前用户具备指定角色之一,否则 403。 */
    public static void assertRole(String... allowedRoles) {
        CurrentUser user = UserContext.require();
        String role = user.getRole();
        boolean ok = role != null && Arrays.stream(allowedRoles).anyMatch(r -> r.equals(role));
        if (!ok) {
            throw new BizException(403, "无权限:需要角色 " + Arrays.toString(allowedRoles) + ",当前=" + role);
        }
    }

    /** 断言为老板。 */
    public static void assertBoss() {
        assertRole("老板");
    }
}
