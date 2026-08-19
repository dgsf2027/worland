package top.aole.rent.common.sso;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import top.aole.rent.common.auth.AuthUser;
import top.aole.rent.common.auth.AuthUserMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** P1-1 手机号首绑收口：只认 ^1\d{10}$、只绑未绑定且 role==默认最低角色 的本地账号。 */
class SsoServicePhoneBindTest {

    AuthUserMapper mapper;
    SsoService svc;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(AuthUserMapper.class);
        SsoProperties p = new SsoProperties();
        p.setEnabled(true);
        svc = new SsoService(p, null, null, mapper, null);
        ReflectionTestUtils.setField(svc, "defaultRole", "业务");
    }

    static AuthUser local(String username, String role, String portalUid) {
        AuthUser u = new AuthUser();
        u.setId(1L); u.setUsername(username); u.setRole(role); u.setPortalUid(portalUid); u.setStatus(1);
        return u;
    }

    static PortalIdentity portal(String phone) {
        return new PortalIdentity("10086", "T-001", "张三", phone, "aole-portal");
    }

    @Test
    void bindsUnboundDefaultRoleAccount() {
        when(mapper.selectOne(any())).thenReturn(local("13800000000", "业务", null));
        AuthUser u = svc.findUnboundByPhone(portal("13800000000"));
        assertNotNull(u);
        assertEquals("13800000000", u.getUsername());
    }

    @Test
    void refusesHighPrivilegeAccount() {
        when(mapper.selectOne(any())).thenReturn(local("13800000000", "老板", null));
        assertNull(svc.findUnboundByPhone(portal("13800000000")));
        when(mapper.selectOne(any())).thenReturn(local("13800000000", "财务", null));
        assertNull(svc.findUnboundByPhone(portal("13800000000")));
    }

    @Test
    void refusesAlreadyBoundAccount() {
        when(mapper.selectOne(any())).thenReturn(local("13800000000", "业务", "99999"));
        assertNull(svc.findUnboundByPhone(portal("13800000000")));
    }

    @Test
    void refusesNonMainlandPhoneShapes() {
        when(mapper.selectOne(any())).thenReturn(local("admin", "业务", null));
        assertNull(svc.findUnboundByPhone(portal("admin")));          // 字母串不再能撞账号名
        assertNull(svc.findUnboundByPhone(portal("2380000000")));     // 非 1 开头
        assertNull(svc.findUnboundByPhone(portal("138000000001")));   // 12 位
        assertNull(svc.findUnboundByPhone(portal(null)));
        Mockito.verifyNoInteractions(mapper);
    }
}
