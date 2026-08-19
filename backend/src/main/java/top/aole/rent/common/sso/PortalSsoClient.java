package top.aole.rent.common.sso;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.aole.rent.common.exception.BizException;

/**
 * 调门户 portal-server 的 HTTP 客户端（契约见 ole-portal-sso SKILL「上游契约速查」）：
 * <ul>
 *   <li>POST {portal}/v1/sso/exchange  {authCode, appId[, clientSecret]} → R{code,success,msg,data{jwt,expiresIn,portalUid,name,phone}}</li>
 *   <li>GET  {portal}/v1/sso/public-key → R{data: PEM}</li>
 * </ul>
 * 门户返回体 code=200 且 success=true 才算成功（两种字段都兼容）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalSsoClient {

    private static final int TIMEOUT_MS = 8_000;

    private final SsoProperties props;

    /** auth_code 一次性兑换 RS256 JWT。失败抛 BizException(401/502)。 */
    public JSONObject exchange(String authCode) {
        String url = props.portalRoot() + "/v1/sso/exchange";
        JSONObject body = new JSONObject()
                .set("authCode", authCode)
                .set("appId", props.getAppId());
        // 门户当前 exchange 契约仅校验 appId + auth_code；client_secret 一并带上（门户忽略未知字段，
        // 若门户后续对 exchange 开启子系统鉴权可零改动过渡）。secret 只进请求体，不进日志。
        if (props.getClientSecret() != null && !props.getClientSecret().trim().isEmpty()) {
            body.set("clientSecret", props.getClientSecret());
        }
        String raw;
        int status;
        try (HttpResponse resp = HttpRequest.post(url)
                .header("Content-Type", "application/json; charset=utf-8")
                .body(body.toString())
                .timeout(TIMEOUT_MS)
                .execute()) {
            status = resp.getStatus();
            raw = resp.body();
        } catch (Exception e) {
            log.error("[sso] 门户 exchange 网络异常 url={} err={}", url, e.getMessage());
            throw new BizException(502, "门户暂不可达，请稍后再试");
        }
        if (status < 200 || status >= 300) {
            log.warn("[sso] 门户 exchange HTTP {} body={}", status, abbreviate(raw));
            throw new BizException(502, "门户 exchange HTTP " + status);
        }
        JSONObject parsed = safeParse(raw);
        if (!isOk(parsed)) {
            String msg = parsed.getStr("msg", parsed.getStr("message", "授权码无效或已过期"));
            log.warn("[sso] 门户 exchange 业务失败 code={} msg={}", parsed.getInt("code"), msg);
            throw new BizException(401, "门户授权失败：" + msg);
        }
        JSONObject data = parsed.getJSONObject("data");
        if (data == null || data.getStr("jwt") == null) {
            throw new BizException(502, "门户响应缺 jwt");
        }
        return data;
    }

    /** 拉门户 RSA 公钥（PEM）。 */
    public String fetchPublicKeyPem() {
        String url = props.portalRoot() + "/v1/sso/public-key";
        String raw;
        int status;
        try (HttpResponse resp = HttpRequest.get(url).timeout(TIMEOUT_MS).execute()) {
            status = resp.getStatus();
            raw = resp.body();
        } catch (Exception e) {
            log.error("[sso] 门户公钥拉取网络异常 url={} err={}", url, e.getMessage());
            throw new BizException(502, "门户暂不可达（公钥）");
        }
        if (status < 200 || status >= 300) {
            throw new BizException(502, "门户公钥拉取 HTTP " + status);
        }
        JSONObject parsed = safeParse(raw);
        if (!isOk(parsed)) {
            throw new BizException(502, "门户公钥拉取失败：" + parsed.getStr("msg", "未知"));
        }
        String pem = parsed.getStr("data");
        if (pem == null || pem.trim().isEmpty()) {
            throw new BizException(502, "门户公钥为空");
        }
        return pem;
    }

    private static boolean isOk(JSONObject r) {
        Boolean success = r.getBool("success");
        Integer code = r.getInt("code");
        if (success != null) return success && (code == null || code == 200 || code == 0);
        return code != null && (code == 200 || code == 0);
    }

    private static JSONObject safeParse(String raw) {
        try {
            JSONObject o = JSONUtil.parseObj(raw);
            return o == null ? new JSONObject() : o;
        } catch (Exception e) {
            throw new BizException(502, "门户响应不是合法 JSON");
        }
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
