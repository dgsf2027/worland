package top.aole.rent.common.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 占位头身份解析过滤器(S0-03 · ADR-001)。
 *
 * <p>从请求头 {@code X-User-Name} / {@code X-User-Role} 解析出当前用户,放入 {@link UserContext}。
 * <b>安全前提(S0-04 上线红线)</b>:生产部署时,可信网关必须<strong>先剥离</strong>客户端自带的
 * X-User-* 头,再由网关按会话<strong>重注入</strong>;后端只信任网关注入的身份。占位期本机直连用于开发。
 *
 * <p>占位期用户主键映射:X-User-Name → 真实 user 主键(隔离键)。真 user 表就绪前,用稳定确定性映射
 * (已知姓名种子表 + 名字 hash 兜底),保证同名同键、非 0。切真 SSO 后由门户下发主键替换本映射。
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@Component
public class UserContextFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-User-Name";
    public static final String HEADER_ROLE = "X-User-Role";

    /** 占位期已知用户种子映射(姓名 → 主键)。切真 SSO 后废弃。 */
    private static final Map<String, Long> SEED_USERS = new HashMap<>();
    static {
        SEED_USERS.put("老板", 1001L);
        SEED_USERS.put("刘总", 1002L);
        SEED_USERS.put("小洪", 1003L);
        SEED_USERS.put("李工", 1004L);
        SEED_USERS.put("财务", 1005L);
        SEED_USERS.put("供应链", 1006L);
        SEED_USERS.put("业务", 1007L);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            // Undertow/Servlet 默认按 ISO-8859-1 解析请求头,中文名会 mojibake,需转回 UTF-8
            String name = decodeHeader(request.getHeader(HEADER_NAME));
            String role = decodeHeader(request.getHeader(HEADER_ROLE));
            if (name != null && !name.trim().isEmpty()) {
                Long userId = resolveUserId(name.trim());
                UserContext.set(new CurrentUser(userId, name.trim(), role == null ? "" : role.trim(), null));
                log.debug("占位头解析: name={}, role={}, userId={}", name, role, userId);
            }
            chain.doFilter(request, response);
        } finally {
            // 线程复用,必须清理,否则身份串号
            UserContext.clear();
        }
    }

    /** 请求头从 ISO-8859-1 还原为 UTF-8(中文名占位头);已是 ASCII 则无损。 */
    private String decodeHeader(String raw) {
        if (raw == null) {
            return null;
        }
        return new String(raw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    /** 姓名 → 稳定非 0 主键:种子表优先,否则名字 hash 落到 [100000, 999999] 区间(确定性、可复现)。 */
    private Long resolveUserId(String name) {
        Long seed = SEED_USERS.get(name);
        if (seed != null) {
            return seed;
        }
        int h = 0;
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            h = h * 31 + (b & 0xff);
        }
        return 100000L + Math.floorMod(h, 900000L);
    }
}
