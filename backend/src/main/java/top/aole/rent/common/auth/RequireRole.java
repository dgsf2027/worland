package top.aole.rent.common.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 敏感操作角色守卫注解(P0-D · M1-15)。挂在 Controller 方法上,由 {@link RoleGuardInterceptor}
 * 统一切面在 preHandle 卡权限:当前角色不在 {@code value} 允许集合内 → 403 + audit(DENIED),不进业务。
 *
 * <p>「敏感操作 × 角色」矩阵(评审纪要 P0-D)收敛入口,替代散在各 service 的 RoleGuard 硬编码:
 * <ul>
 *   <li>合同作废/红冲 → 财务 + 老板</li>
 *   <li>供应商淘汰 → 供应链 + 老板</li>
 *   <li>采购退货(红冲) → 供应链 + 老板</li>
 *   <li>投放审批 → 老板</li>
 * </ul>
 *
 * @see RoleGuardInterceptor
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 允许执行本操作的角色集合(命中其一即放行);为空视为需登录即可。 */
    String[] value();

    /** 审计留痕的操作名(空则用方法签名兜底)。 */
    String action() default "";

    /** 审计对象类型(contract/supplier/purchase_in/asset)。 */
    String targetType() default "";
}
