package top.aole.rent.common.sso;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.jwt.JWT;
import cn.hutool.jwt.signers.JWTSignerUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.aole.rent.common.exception.BizException;

import java.security.PublicKey;
import java.util.List;

/**
 * 门户 JWT 完整校验（硬约束 4：RS256 + aud=appId + iss∈允许列表 + exp，缺一不可）。
 * 用 hutool-jwt（工程已有依赖），不引入 jjwt。iss 多值手工比对（硬约束 1）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalJwtVerifier {

    private final SsoProperties props;
    private final PortalPublicKeyCache keyCache;

    /** 用缓存公钥验签；签名不过则强制刷新公钥重试一次（门户轮换密钥场景）。 */
    public PortalIdentity verify(String jwt) {
        PublicKey key = keyCache.get();
        try {
            return verifyWith(jwt, key);
        } catch (SignatureMismatch first) {
            log.warn("[sso] JWT 签名不匹配，刷新门户公钥后重试一次");
            PublicKey fresh = keyCache.refresh();
            try {
                return verifyWith(jwt, fresh);
            } catch (SignatureMismatch again) {
                throw new BizException(401, "门户 JWT 验签失败");
            }
        }
    }

    /** 纯函数式校验入口（单测直接用）。 */
    public PortalIdentity verifyWith(String jwt, PublicKey publicKey) {
        if (jwt == null || jwt.trim().isEmpty()) throw new BizException(401, "门户 JWT 为空");
        JWT parsed;
        try {
            parsed = JWT.of(jwt.trim());
        } catch (Exception e) {
            throw new BizException(401, "门户 JWT 格式非法");
        }
        // 1. 算法钉死 RS256（防 alg=none / HS256 混淆攻击）
        String alg = parsed.getAlgorithm();
        if (!"RS256".equalsIgnoreCase(alg)) {
            throw new BizException(401, "门户 JWT 算法非 RS256：" + alg);
        }
        // 2. 签名
        boolean sigOk;
        try {
            sigOk = parsed.verify(JWTSignerUtil.rs256(publicKey));
        } catch (Exception e) {
            sigOk = false;
        }
        if (!sigOk) throw new SignatureMismatch();

        JSONObject payload = parsed.getPayloads();
        // 3. audience = 本系统 app_id（字符串或数组两种形态都认）
        if (!audienceMatches(payload.get("aud"), props.getAppId())) {
            throw new BizException(401, "门户 JWT audience 不匹配");
        }
        // 4. issuer ∈ 允许列表（多值）
        String iss = payload.getStr("iss");
        List<String> accepted = props.acceptedIssuers();
        if (iss == null || !accepted.contains(iss)) {
            throw new BizException(401, "门户 JWT issuer 不匹配：实际 " + iss + "，允许 " + accepted);
        }
        // 5. exp（必须有；含时钟偏差容错）+ nbf（若有）
        long now = System.currentTimeMillis() / 1000L;
        long skew = Math.max(0, props.getClockSkewSeconds());
        Long exp = payload.getLong("exp");
        if (exp == null) throw new BizException(401, "门户 JWT 缺 exp");
        if (exp + skew < now) throw new BizException(401, "门户 JWT 已过期，请回门户重新点击进入");
        Long nbf = payload.getLong("nbf");
        if (nbf != null && nbf - skew > now) throw new BizException(401, "门户 JWT 尚未生效");

        // 6. 抽身份
        String portalUid = payload.getStr("portal_uid");
        if (portalUid == null || portalUid.trim().isEmpty()) portalUid = payload.getStr("sub");
        if (portalUid == null || portalUid.trim().isEmpty()) throw new BizException(401, "门户 JWT 缺 portal_uid");
        String tenantId = payload.getStr("tenant_id");
        if (tenantId == null || tenantId.trim().isEmpty()) throw new BizException(401, "门户 JWT 缺 tenant_id");
        return new PortalIdentity(portalUid.trim(), tenantId.trim(), payload.getStr("name"), payload.getStr("phone"), iss);
    }

    static boolean audienceMatches(Object aud, String appId) {
        if (aud == null || appId == null || appId.isEmpty()) return false;
        if (aud instanceof CharSequence) return appId.equals(aud.toString());
        if (aud instanceof JSONArray) {
            for (Object o : (JSONArray) aud) if (o != null && appId.equals(o.toString())) return true;
            return false;
        }
        if (aud instanceof Iterable) {
            for (Object o : (Iterable<?>) aud) if (o != null && appId.equals(o.toString())) return true;
            return false;
        }
        return appId.equals(aud.toString());
    }

    /** 内部信号：签名不匹配（触发公钥刷新重试），对外统一转 401。 */
    static final class SignatureMismatch extends RuntimeException {
        private static final long serialVersionUID = 1L;
        SignatureMismatch() { super("signature mismatch"); }
    }
}
