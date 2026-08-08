package top.aole.rent.common.auth;

import top.aole.rent.common.exception.BizException;

/**
 * 用户上下文适配层(ThreadLocal)。ADR-001 · S0-03。
 *
 * <p>占位期:{@link UserContextFilter} 从可信网关注入的 X-User-* 头解析出 {@link CurrentUser} 放入本地;
 * 就绪期:替换为真 SSO(澳乐门户 auth_code 换 token,走 ole-portal-sso)时,只改 Filter 的解析来源,
 * 业务代码调用 {@code UserContext.get()} / {@code getUserId()} 不变。
 *
 * <p>唯一 provider,禁止业务代码另行读头(收敛成单一入口)。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    /** 取当前用户;未登录返回 null(需鉴权的接口应先经守卫) */
    public static CurrentUser get() {
        return HOLDER.get();
    }

    /** 取当前用户,缺省即未登录 → 401 */
    public static CurrentUser require() {
        CurrentUser u = HOLDER.get();
        if (u == null || u.getUserId() == null || u.getUserId() == 0L) {
            throw new BizException(401, "未登录或身份缺失(占位头 X-User-Name 未解析到用户)");
        }
        return u;
    }

    public static Long getUserId() {
        return require().getUserId();
    }

    public static String getRole() {
        return require().getRole();
    }

    public static Long getProjectId() {
        CurrentUser u = HOLDER.get();
        return u == null ? null : u.getProjectId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
