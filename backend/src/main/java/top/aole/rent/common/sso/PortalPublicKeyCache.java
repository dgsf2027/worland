package top.aole.rent.common.sso;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.aole.rent.common.exception.BizException;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 门户 RSA 公钥缓存（硬约束 3：公钥不进仓库，启动后从门户拉取并缓存 10 分钟）。
 * 本工程无 Redis，用进程内缓存（单实例部署等价；多实例各自缓存亦正确，只是各拉一次）。
 * 门户轮换密钥时验签失败 → 调用方 {@link #refresh()} 强制刷新后重试一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PortalPublicKeyCache {

    private final SsoProperties props;
    private final PortalSsoClient client;

    private volatile PublicKey cached;
    private volatile long fetchedAtMillis;

    public PublicKey get() {
        PublicKey k = cached;
        long ttlMs = props.getPublicKeyTtlSeconds() * 1000L;
        if (k != null && System.currentTimeMillis() - fetchedAtMillis < ttlMs) {
            return k;
        }
        return refresh();
    }

    public synchronized PublicKey refresh() {
        String pem = client.fetchPublicKeyPem();
        PublicKey key = parsePem(pem);
        cached = key;
        fetchedAtMillis = System.currentTimeMillis();
        log.info("[sso] 门户公钥已刷新 alg={} format={}", key.getAlgorithm(), key.getFormat());
        return key;
    }

    /** PEM（-----BEGIN PUBLIC KEY----- X.509 SubjectPublicKeyInfo）→ RSA PublicKey */
    public static PublicKey parsePem(String pem) {
        try {
            String stripped = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(stripped);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new BizException(502, "门户公钥解析失败：" + e.getMessage());
        }
    }
}
