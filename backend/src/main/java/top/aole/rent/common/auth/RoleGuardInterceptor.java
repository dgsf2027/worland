package top.aole.rent.common.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.exception.BizException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Map;

/**
 * 统一鉴权切面(P0-D · M1-15)。用 {@link HandlerInterceptor} 集中拦截挂了 {@link RequireRole} 的
 * 敏感写接口,替代散在各 service 的 RoleGuard 硬编码 —— 先卡权限再执行,越权 403 且不进业务。
 *
 * <p>选型:本工程未引入 spring-boot-starter-aop(无 aspectjweaver),用 spring-web 原生
 * HandlerInterceptor + 方法注解即可实现声明式统一切面,零新增依赖;由 {@link top.aole.rent.common.config.CorsConfig}
 * 注册。留痕:越权拦截写 audit(DENIED);放行执行由业务 service 写 audit(EXECUTED)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RoleGuardInterceptor implements HandlerInterceptor {

    private final AuditLogService auditLogService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        RequireRole ann = ((HandlerMethod) handler).getMethodAnnotation(RequireRole.class);
        if (ann == null) {
            return true;
        }
        // 需登录(占位期 X-User-Name 未解析 → 401)
        CurrentUser user = UserContext.require();
        String role = user.getRole();
        boolean allowed = ann.value().length == 0
                || Arrays.stream(ann.value()).anyMatch(r -> r.equals(role));

        String action = ann.action().isEmpty() ? ((HandlerMethod) handler).getMethod().getName() : ann.action();
        String targetType = ann.targetType().isEmpty() ? null : ann.targetType();
        Long targetId = pathId(request);

        if (!allowed) {
            String detail = "越权拦截:需要角色 " + Arrays.toString(ann.value()) + ",当前=" + role;
            auditLogService.record(action, targetType, targetId, AuditLogService.DENIED, detail);
            log.warn("敏感操作越权拦截: action={}, target={}#{}, user={}({})",
                    action, targetType, targetId, user.getUserName(), role);
            throw new BizException(403, "无权限:" + action + " 需要角色 "
                    + Arrays.toString(ann.value()) + ",当前=" + role);
        }
        return true;
    }

    /** 从 REST 路径变量 {id} 取审计对象主键(取不到返回 null,不影响放行)。 */
    private Long pathId(HttpServletRequest request) {
        Object attr = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (!(attr instanceof Map)) {
            return null;
        }
        Object id = ((Map<?, ?>) attr).get("id");
        if (id == null) {
            return null;
        }
        try {
            return Long.valueOf(id.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
