package top.aole.rent.common.sso;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.aole.rent.common.auth.AuthTokenService;
import top.aole.rent.common.auth.AuthUser;
import top.aole.rent.common.auth.AuthUserMapper;
import top.aole.rent.common.auth.PasswordHasher;
import top.aole.rent.common.exception.BizException;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * 门户 SSO 主流程：auth_code → 门户 exchange → RS256 验签 → 租户双源校验 → 按 portal_uid 找/建本地账号 → 签发本系统会话 token。
 *
 * <p><b>首登口径（负责人拍板·全自动）</b>：按 portal_uid 找不到本地账号时自动建号——
 * 用户名=手机号（可用且未被占用）否则 {@code portal_<uid>}；姓名=JWT name；绑定 portal_uid；
 * 角色=系统默认最低角色（rent.auth.register-default-role，默认「业务」）；密码为随机不可用值（只能走 SSO，
 * 老板可在花名册改角色）。若手机号恰好等于某个尚未绑定门户的本地账号名 → 首次绑定（硬约束 8：手机号仅用于首次绑定，长期键=portal_uid）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SsoService {

    private final SsoProperties props;
    private final PortalSsoClient client;
    private final PortalJwtVerifier verifier;
    private final AuthUserMapper userMapper;
    private final AuthTokenService tokenService;

    @Value("${rent.auth.register-default-role:业务}")
    private String defaultRole;

    /** SSO 登录结果：交给 controller 302 到前端 */
    public static final class Result {
        public final String token; public final String displayName; public final String role; public final boolean created;
        Result(String token, String displayName, String role, boolean created) {
            this.token = token; this.displayName = displayName; this.role = role; this.created = created;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Result login(String authCode, String appId, String urlTenantId) {
        if (!props.isEnabled()) throw new BizException(400, "SSO 未启用");
        if (!props.isConfigured()) throw new BizException(500, "SSO 配置不完整（portal-base-url/app-id/client-secret）");
        if (authCode == null || authCode.trim().isEmpty()) throw new BizException(400, "缺少 auth_code");
        // 防钓鱼：URL 上的 app_id 必须是本系统
        if (appId != null && !appId.trim().isEmpty() && !appId.trim().equals(props.getAppId())) {
            throw new BizException(400, "app_id 不匹配");
        }

        // 1. 门户兑换（auth_code 一次性、60s 有效）
        JSONObject data = client.exchange(authCode.trim());
        String jwt = data.getStr("jwt");

        // 2. RS256 完整验签（aud/iss/exp）
        PortalIdentity id = verifier.verify(jwt);

        // 3. 租户双源校验（硬约束 5）：URL tenantId 与 JWT tenant_id 必须一致
        checkTenant(urlTenantId, id.getTenantId());

        // 4. 找 / 建本地账号（长期键 portal_uid）
        boolean created = false;
        AuthUser user = userMapper.selectOne(new LambdaQueryWrapper<AuthUser>()
                .eq(AuthUser::getPortalUid, id.getPortalUid()).last("limit 1"));
        if (user == null) {
            user = findUnboundByPhone(id);
            if (user != null) {
                user.setPortalUid(id.getPortalUid());
                log.info("[sso] 首次绑定 username={} ← portalUid={}", user.getUsername(), id.getPortalUid());
            } else {
                user = createUser(id);
                created = true;
            }
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(403, "账号已停用，请联系管理员");
        }
        // 姓名跟门户同步（显示名=占位头 X-User-Name，是业务隔离键之一，只在门户有值时更新）
        if (id.getName() != null && !id.getName().trim().isEmpty() && !id.getName().trim().equals(user.getDisplayName())) {
            user.setDisplayName(id.getName().trim());
        }
        user.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(user);

        // 5. 签发本系统原有会话 token（与 /auth/login 完全一致的载荷）
        String token = tokenService.issue(user.getId(), user.getDisplayName(), user.getRole());
        log.info("[sso] login ok portalUid={} tenant={} userId={} created={}", id.getPortalUid(), id.getTenantId(), user.getId(), created);
        return new Result(token, user.getDisplayName(), user.getRole(), created);
    }

    /** 租户双源校验 + 可选租户白名单。包私有便于单测。 */
    void checkTenant(String urlTenantId, String jwtTenantId) {
        if (jwtTenantId == null || jwtTenantId.trim().isEmpty()) throw new BizException(401, "门户 JWT 缺 tenant_id");
        if (urlTenantId != null && !urlTenantId.trim().isEmpty() && !urlTenantId.trim().equals(jwtTenantId.trim())) {
            log.warn("[sso] 租户双源不一致 url={} jwt={}", urlTenantId, jwtTenantId);
            throw new BizException(401, "租户校验失败");
        }
        List<String> allowed = props.allowedTenants();
        if (!allowed.isEmpty() && !allowed.contains(jwtTenantId.trim())) {
            throw new BizException(403, "该门户租户未开通本系统");
        }
    }

    private static final String USERNAME_PATTERN = "^[A-Za-z0-9_\\-]{3,32}$";

    /** 手机号 == 某个尚未绑定门户的本地账号名 → 返回它（首次绑定）；否则 null */
    private AuthUser findUnboundByPhone(PortalIdentity id) {
        String phone = id.getPhone() == null ? "" : id.getPhone().trim();
        if (!phone.matches(USERNAME_PATTERN)) return null;
        AuthUser byPhone = userMapper.selectOne(new LambdaQueryWrapper<AuthUser>()
                .eq(AuthUser::getUsername, phone).last("limit 1"));
        if (byPhone != null && (byPhone.getPortalUid() == null || byPhone.getPortalUid().isEmpty())) return byPhone;
        return null;
    }

    /** 自动建号：用户名=手机号（可用且未占用）否则 portal_<uid>；角色=默认最低角色 */
    private AuthUser createUser(PortalIdentity id) {
        String phone = id.getPhone() == null ? "" : id.getPhone().trim();
        String username = phone.matches(USERNAME_PATTERN) && !usernameTaken(phone) ? phone : "portal_" + id.getPortalUid();
        if (usernameTaken(username)) {
            // 极端兜底：portal_<uid> 也被占了（人工手建撞名）→ 加短随机后缀
            username = username + "_" + randomSuffix();
        }
        String name = id.getName() == null || id.getName().trim().isEmpty() ? username : id.getName().trim();
        if (name.length() > 30) name = name.substring(0, 30);
        AuthUser u = new AuthUser();
        u.setUsername(username);
        u.setPasswordHash(PasswordHasher.hash(randomPassword()));
        u.setDisplayName(name);
        u.setRole(defaultRole);
        u.setStatus(1);
        u.setPortalUid(id.getPortalUid());
        u.setCreatedAt(LocalDateTime.now());
        u.setLastLoginAt(null);
        userMapper.insert(u);
        log.info("[sso] 首登自动建号 username={} role={} portalUid={}", username, defaultRole, id.getPortalUid());
        return u;
    }

    private boolean usernameTaken(String username) {
        return userMapper.selectCount(new LambdaQueryWrapper<AuthUser>().eq(AuthUser::getUsername, username)) > 0;
    }

    private static final SecureRandom RNG = new SecureRandom();

    private static String randomPassword() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String randomSuffix() {
        byte[] b = new byte[3];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b).replaceAll("[^A-Za-z0-9]", "x");
    }
}
