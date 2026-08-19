package top.aole.rent.common.sso;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.signers.JWTSignerUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import top.aole.rent.common.exception.BizException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 门户 JWT 验签单测（硬约束 4：RS256 + aud + iss 多值 + exp）+ 租户双源校验（硬约束 5）。
 * 本地生成 RSA 密钥对模拟门户签发；公钥走 PEM 解析路径，与生产 /v1/sso/public-key 返回形态一致。
 */
class PortalJwtVerifierTest {

    static KeyPair portalKeys;
    static KeyPair otherKeys;
    static final String APP_ID = "rent_test_app";

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        portalKeys = g.generateKeyPair();
        otherKeys = g.generateKeyPair();
    }

    static SsoProperties props() {
        SsoProperties p = new SsoProperties();
        p.setEnabled(true);
        p.setAppId(APP_ID);
        p.setJwtIssuer("aole-portal,yunshan-portal");
        p.setClockSkewSeconds(5);
        return p;
    }

    static PublicKey pubViaPem(KeyPair kp) {
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        return PortalPublicKeyCache.parsePem(pem);
    }

    static String jwt(KeyPair kp, String iss, Object aud, long expOffsetSec, String tenant) {
        long now = System.currentTimeMillis() / 1000;
        JWT j = JWT.create()
                .setPayload("iss", iss)
                .setPayload("sub", "10086")
                .setPayload("portal_uid", "10086")
                .setPayload("tenant_id", tenant)
                .setPayload("name", "张三")
                .setPayload("phone", "13800000000")
                .setPayload("iat", now)
                .setPayload("exp", now + expOffsetSec);
        if (aud != null) j.setPayload("aud", aud);
        return j.setSigner(JWTSignerUtil.rs256(kp.getPrivate())).sign();
    }

    @Test
    void validTokenPasses() {
        PortalJwtVerifier v = new PortalJwtVerifier(props(), null);
        PortalIdentity id = v.verifyWith(jwt(portalKeys, "aole-portal", APP_ID, 60, "T-001"), pubViaPem(portalKeys));
        assertEquals("10086", id.getPortalUid());
        assertEquals("T-001", id.getTenantId());
        assertEquals("张三", id.getName());
        assertEquals("13800000000", id.getPhone());
    }

    @Test
    void legacyIssuerStillAccepted() {
        PortalJwtVerifier v = new PortalJwtVerifier(props(), null);
        assertDoesNotThrow(() -> v.verifyWith(jwt(portalKeys, "yunshan-portal", APP_ID, 60, "T-001"), pubViaPem(portalKeys)));
    }

    @Test
    void wrongIssuerRejected() {
        PortalJwtVerifier v = new PortalJwtVerifier(props(), null);
        BizException e = assertThrows(BizException.class,
                () -> v.verifyWith(jwt(portalKeys, "evil-portal", APP_ID, 60, "T-001"), pubViaPem(portalKeys)));
        assertEquals(401, e.getCode());
        assertTrue(e.getMessage().contains("issuer"));
    }

    @Test
    void wrongAudienceRejected() {
        PortalJwtVerifier v = new PortalJwtVerifier(props(), null);
        BizException e = assertThrows(BizException.class,
                () -> v.verifyWith(jwt(portalKeys, "aole-portal", "other_app", 60, "T-001"), pubViaPem(portalKeys)));
        assertEquals(401, e.getCode());
        assertTrue(e.getMessage().contains("audience"));
    }

    @Test
    void audienceArrayFormAccepted() {
        PortalJwtVerifier v = new PortalJwtVerifier(props(), null);
        assertDoesNotThrow(() -> v.verifyWith(
                jwt(portalKeys, "aole-portal", new String[]{"x", APP_ID}, 60, "T-001"), pubViaPem(portalKeys)));
    }

    @Test
    void expiredRejected() {
        PortalJwtVerifier v = new PortalJwtVerifier(props(), null);
        BizException e = assertThrows(BizException.class,
                () -> v.verifyWith(jwt(portalKeys, "aole-portal", APP_ID, -120, "T-001"), pubViaPem(portalKeys)));
        assertEquals(401, e.getCode());
        assertTrue(e.getMessage().contains("过期"));
    }

    @Test
    void wrongKeySignalsSignatureMismatch() {
        PortalJwtVerifier v = new PortalJwtVerifier(props(), null);
        assertThrows(PortalJwtVerifier.SignatureMismatch.class,
                () -> v.verifyWith(jwt(otherKeys, "aole-portal", APP_ID, 60, "T-001"), pubViaPem(portalKeys)));
    }

    @Test
    void hs256OrNoneAlgRejected() {
        PortalJwtVerifier v = new PortalJwtVerifier(props(), null);
        String hs = JWT.create().setPayload("iss", "aole-portal").setPayload("aud", APP_ID)
                .setPayload("portal_uid", "1").setPayload("tenant_id", "T").setPayload("exp", System.currentTimeMillis() / 1000 + 60)
                .setKey("secret".getBytes()).sign();
        BizException e = assertThrows(BizException.class, () -> v.verifyWith(hs, pubViaPem(portalKeys)));
        assertEquals(401, e.getCode());
        assertTrue(e.getMessage().contains("RS256"));
    }

    @Test
    void missingTenantClaimRejected() {
        PortalJwtVerifier v = new PortalJwtVerifier(props(), null);
        BizException e = assertThrows(BizException.class,
                () -> v.verifyWith(jwt(portalKeys, "aole-portal", APP_ID, 60, null), pubViaPem(portalKeys)));
        assertTrue(e.getMessage().contains("tenant_id"));
    }

    // ---- 租户双源校验（SsoService.checkTenant） ----

    @Test
    void tenantMismatchBetweenUrlAndJwtRejected() {
        SsoService s = new SsoService(props(), null, null, null, null);
        BizException e = assertThrows(BizException.class, () -> s.checkTenant("T-URL", "T-JWT"));
        assertEquals(401, e.getCode());
        assertDoesNotThrow(() -> s.checkTenant("T-001", "T-001"));
        assertDoesNotThrow(() -> s.checkTenant(null, "T-001"));   // 门户未带 tenantId 时以 JWT 为准
        assertDoesNotThrow(() -> s.checkTenant("", "T-001"));
    }

    @Test
    void tenantAllowlistEnforcedWhenConfigured() {
        SsoProperties p = props();
        p.setAllowedTenantIds("T-001, T-002");
        SsoService s = new SsoService(p, null, null, null, null);
        assertDoesNotThrow(() -> s.checkTenant("T-002", "T-002"));
        BizException e = assertThrows(BizException.class, () -> s.checkTenant("T-999", "T-999"));
        assertEquals(403, e.getCode());
    }
}
