package top.aole.rent.common.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.auth.CurrentUser;
import top.aole.rent.common.auth.UserContext;

import java.time.LocalDateTime;

/**
 * 敏感操作审计留痕服务(P0-D · M1-15)。统一切面(越权拦截 DENIED)与业务服务(放行执行 EXECUTED)共用。
 *
 * <p>身份取 {@link UserContext} 当前用户(占位期网关注入 X-User-*·真 SSO 后门户下发,口径不变)。
 * 写库用 {@code REQUIRES_NEW} 独立事务:DENIED 留痕不受被拦截请求回滚影响,业务失败也不冲掉越权痕迹。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    public static final String DENIED = "DENIED";
    public static final String EXECUTED = "EXECUTED";

    private final AuditLogMapper auditLogMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String targetType, Long targetId, String result, String detail) {
        CurrentUser u = UserContext.get();
        AuditLog log = new AuditLog();
        log.setAction(action);
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setResult(result);
        if (u != null) {
            log.setOperatorId(u.getUserId());
            log.setOperatorName(u.getUserName());
            log.setOperatorRole(u.getRole());
            // 请求指纹(网关注入·抗抵赖 M5-06 P1-16)
            log.setClientIp(u.getClientIp());
            log.setRequestUri(u.getRequestUri());
            log.setRequestId(u.getRequestId());
        }
        log.setDetail(detail);
        log.setCreateTime(LocalDateTime.now());
        auditLogMapper.insert(log);
    }
}
