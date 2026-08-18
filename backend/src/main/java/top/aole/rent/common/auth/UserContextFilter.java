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
@lombok.RequiredArgsConstructor
public class UserContextFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-User-Name";
    public static final String HEADER_ROLE = "X-User-Role";

    private final AuthTokenService tokenService;

    /**
     * 2026-08-19 邀请码注册上线后：身份唯一来源 = Bearer 令牌（/auth/register|login 签发），
     * 占位头 X-User-* 仅在 rent.auth.placeholder-headers-enabled=true（本地开发）时才被信任。
     * 无令牌访问业务接口 → 401（/auth/**、健康检查、swagger 放行）。
     */
    @org.springframework.beans.factory.annotation.Value("${rent.auth.placeholder-headers-enabled:false}")
    private boolean placeholderHeadersEnabled;

    private static final String[] PUBLIC_PREFIXES = {
            "/auth/", "/v1/health", "/doc.html", "/webjars/", "/swagger-resources", "/v2/api-docs", "/v3/api-docs", "/swagger-ui", "/favicon.ico", "/error", "/actuator"
    };

    private static boolean isPublic(String path) {
        for (String p : PUBLIC_PREFIXES) if (path.startsWith(p)) return true;
        return false;
    }

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
            String name = null;
            String role = null;
            Long tokenUserId = null;
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
                AuthTokenService.Claims c = tokenService.verify(auth.substring(7).trim());
                if (c != null) { name = c.name; role = c.role; tokenUserId = c.userId; }
            }
            if (name == null && placeholderHeadersEnabled) {
                // Undertow/Servlet 默认按 ISO-8859-1 解析请求头,中文名会 mojibake,需转回 UTF-8
                name = decodeHeader(request.getHeader(HEADER_NAME));
                role = decodeHeader(request.getHeader(HEADER_ROLE));
            }
            String path = request.getRequestURI();
            String ctx = request.getContextPath();
            if (ctx != null && !ctx.isEmpty() && path.startsWith(ctx)) path = path.substring(ctx.length());
            if ((name == null || name.trim().isEmpty()) && !isPublic(path) && !"OPTIONS".equalsIgnoreCase(request.getMethod())) {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已过期\",\"data\":null}");
                return;
            }
            if (name != null && !name.trim().isEmpty()) {
                // 用户主键仍按显示名确定性映射(与占位期数据键一致,老数据不串;tokenUserId 仅留作审计)
                Long userId = resolveUserId(name.trim());
                if (tokenUserId != null) log.debug("token userId={}", tokenUserId);
                CurrentUser u = new CurrentUser(userId, name.trim(), role == null ? "" : role.trim(), null);
                // 请求指纹(审计抗抵赖 M5-06 P1-16):IP/URI/请求号。生产由可信网关注入 X-Forwarded-For/X-Request-Id
                u.setClientIp(clientIp(request));
                u.setRequestUri(request.getRequestURI());
                String reqId = request.getHeader("X-Request-Id");
                u.setRequestId(reqId != null && !reqId.isEmpty() ? reqId : java.util.UUID.randomUUID().toString());
                UserContext.set(u);
                log.debug("占位头解析: name={}, role={}, userId={}, ip={}", name, role, userId, u.getClientIp());
            }
            chain.doFilter(request, response);
        } finally {
            // 线程复用,必须清理,否则身份串号
            UserContext.clear();
        }
    }

    /**
     * 占位头解码。兼容两种客户端:
     *  - curl 直传原始 UTF-8 字节:Undertow 按 ISO-8859-1 读入 → 转回 UTF-8;
     *  - 浏览器 XHR 不允许非 Latin1 头值,前端 encodeURIComponent → 此处 URL 解码。
     */
    private String decodeHeader(String raw) {
        if (raw == null) {
            return null;
        }
        String utf8 = new String(raw.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
        if (utf8.indexOf('%') >= 0) {
            try {
                return java.net.URLDecoder.decode(utf8, "UTF-8");
            } catch (Exception ignore) {
                return utf8;
            }
        }
        return utf8;
    }

    /** 请求来源 IP:优先可信网关注入的 X-Forwarded-For 首段,退化到 remoteAddr。 */
    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
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
