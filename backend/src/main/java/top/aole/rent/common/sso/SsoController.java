package top.aole.rent.common.sso;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.exception.BizException;

import javax.servlet.http.HttpServletResponse;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * 门户 SSO 回调端点（仅 sso.enabled=true 时挂载；UserContextFilter 已把 /v1/sso/ 放行）。
 *
 * <p>门户工作台点卡片 → 门户 302 到 {@code {callback_url}?auth_code=..&app_id=..&tenantId=..}
 * （门户 yc_portal_system.callback_url 须登记为 {@code https://<本系统域名>/api/v1/sso/callback}）
 * → 本端点换 token → 302 到前端 {@code /sso/callback#token=..&name=..&role=..}
 * （token 放 URL fragment：不进 nginx/网关 access log；前端页读 hash → 写 localStorage → 整页强刷 /）。
 * 失败 → 302 到 {@code /sso/callback#error=..}，前端展示并给回登录页链接。
 */
@Slf4j
@Api(tags = "SSO 单点登录")
@RestController
@RequestMapping("/v1/sso")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "sso", name = "enabled", havingValue = "true")
public class SsoController {

    private final SsoService ssoService;
    private final SsoProperties props;

    @ApiOperation("门户回调：auth_code 换本系统会话并 302 到前端 /sso/callback")
    @GetMapping("/callback")
    public void callback(@RequestParam(value = "auth_code", required = false) String authCode,
                         @RequestParam(value = "app_id", required = false) String appId,
                         @RequestParam(value = "tenantId", required = false) String tenantId,
                         @RequestParam(value = "tenant_id", required = false) String tenantIdSnake,
                         HttpServletResponse response) {
        String urlTenant = tenantId != null && !tenantId.isEmpty() ? tenantId : tenantIdSnake;
        log.info("[sso] callback appId={} tenantId={} authCodePrefix={}", appId, urlTenant,
                authCode == null ? "null" : authCode.substring(0, Math.min(8, authCode.length())));
        String fragment;
        try {
            SsoService.Result r = ssoService.login(authCode, appId, urlTenant);
            fragment = "token=" + enc(r.token) + "&name=" + enc(r.displayName) + "&role=" + enc(r.role)
                    + (r.created ? "&created=1" : "");
        } catch (BizException e) {
            // 细节只留日志；对外文案按错误码给通用提示，不泄 issuer 列表 / 配置项名 / 门户内部报错
            log.warn("[sso] callback rejected code={} msg={}", e.getCode(), e.getMessage());
            fragment = "error=" + enc(publicMessage(e.getCode()));
        } catch (Exception e) {
            log.error("[sso] callback error", e);
            fragment = "error=" + enc("SSO 登录异常，请稍后重试");
        }
        redirect(response, props.getFrontendBaseUrl(), fragment);
    }

    /**
     * 手工写 Location（相对路径），不用 sendRedirect：Undertow 会用请求 scheme 拼绝对 URL，
     * 经 nginx/Cloudflare 反代时会拼成 http:// 造成二次跳转丢 fragment。
     */
    private static void redirect(HttpServletResponse response, String frontendBase, String fragment) {
        String base = frontendBase == null ? "" : frontendBase.replaceAll("/+$", "");
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", base + "/sso/callback#" + fragment);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
    }

    /** 对外通用文案（按 code 分桶），排查看后端日志 */
    static String publicMessage(int code) {
        switch (code) {
            case 400: return "登录参数不正确，请回平台重新点击进入";
            case 401: return "平台授权校验未通过或已过期，请回平台重新点击进入";
            case 403: return "该账号暂无本系统访问权限，请联系管理员";
            case 502: return "平台门户暂不可达，请稍后再试";
            default:  return "SSO 登录未成功，请稍后重试或使用账号密码登录";
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return "";
        }
    }
}
