package top.aole.rent.common.auth;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 数据可见域判定(P0-E 隔离的服务端口径 · 单一入口)。
 *
 * <p>DESIGN §4.5/§十一 B:行级(owner_user/project_id)+字段级(成本价/授信/账期/分配按角色投影)。
 * <b>投资人只读角色(GP/LP)不可见成本/授信明细</b>;经营角色(老板/财务/供应链/业务)可见。
 * 收敛为静态口径,业务代码只调本类,避免各处硬编码角色字符串(§4.24)。
 */
public final class DataScope {

    private DataScope() {
    }

    /** 经营角色:可见成本/账期/授信等敏感财务字段。 */
    private static final Set<String> COST_VISIBLE_ROLES =
            new HashSet<>(Arrays.asList("老板", "财务", "供应链", "业务"));

    /** 仅看自己名下+公海的行级隔离角色(业务 BD)。 */
    private static final Set<String> OWNER_SCOPED_ROLES =
            new HashSet<>(Arrays.asList("业务"));

    /** 当前角色是否可见成本/授信等敏感字段(GP/LP 及未知角色 → 不可见)。 */
    public static boolean canSeeCost(String role) {
        return role != null && COST_VISIBLE_ROLES.contains(role);
    }

    /** 当前角色是否受行级隔离(只见自己名下客户+公海)。老板/财务/供应链见全量。 */
    public static boolean isOwnerScoped(String role) {
        return role != null && OWNER_SCOPED_ROLES.contains(role);
    }
}
