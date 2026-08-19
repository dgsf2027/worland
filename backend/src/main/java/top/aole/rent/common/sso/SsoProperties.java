package top.aole.rent.common.sso;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 澳乐门户 SSO 配置（ole-portal-sso 规范 · 硬约束 1/2：issuer 多值、凭据全走 .env，禁硬编码）。
 *
 * <p>启用：{@code SSO_ENABLED=true} 且 portal-base-url / app-id / client-secret 三项齐全。
 * 关闭（默认）：{@link SsoController} 不挂载，老的账号密码登录完全不受影响。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "sso")
public class SsoProperties {

    /** 总开关，默认 false */
    private boolean enabled = false;

    /** 门户 API 根（含 context-path /api），例：http://host.docker.internal:18151/api */
    private String portalBaseUrl = "";

    /** 门户管理后台分配的子系统 app_id（.env 注入） */
    private String appId = "";

    /** 门户管理后台分配的 client_secret（.env 注入，永不入库/日志） */
    private String clientSecret = "";

    /** JWT issuer 允许列表，逗号分隔（2026-04-25 门户从 yunshan-portal 改名 aole-portal，双值兼容） */
    private String jwtIssuer = "aole-portal,yunshan-portal";

    /**
     * 允许登录的门户租户 tenant_id 列表（逗号分隔）。留空=不限（门户按子系统所属租户发码，天然隔离）。
     * 本系统首登自动建号，配置后多一道兜底。
     */
    private String allowedTenantIds = "";

    /**
     * 前端根地址（不含末尾 /）。留空=同源相对跳转 {@code /sso/callback#token=...}（生产前后端同域反代，默认即可）。
     */
    private String frontendBaseUrl = "";

    /** 门户公钥内存缓存 TTL（秒），默认 600（规范：10 分钟） */
    private long publicKeyTtlSeconds = 600;

    /** JWT exp 校验允许的时钟偏差（秒），门户 exp=iat+60s，容器间几秒偏差需容错 */
    private long clockSkewSeconds = 30;

    public List<String> acceptedIssuers() {
        return splitCsv(jwtIssuer);
    }

    public List<String> allowedTenants() {
        return splitCsv(allowedTenantIds);
    }

    /** 三项凭据齐全才算真正配置好 */
    public boolean isConfigured() {
        return notBlank(portalBaseUrl) && notBlank(appId) && notBlank(clientSecret);
    }

    /** 去掉末尾斜杠的门户根 */
    public String portalRoot() {
        return portalBaseUrl == null ? "" : portalBaseUrl.replaceAll("/+$", "");
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null) return java.util.Collections.emptyList();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
