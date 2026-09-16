package top.aole.rent.modules.roster;

import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import top.aole.rent.common.audit.AuditLogService;
import top.aole.rent.common.auth.*;
import top.aole.rent.common.exception.GlobalExceptionHandler;
import top.aole.rent.common.result.R;
import top.aole.rent.common.sso.*;
import top.aole.rent.modules.contract.mapper.ContractMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseInMapper;
import top.aole.rent.modules.purchase.mapper.PurchaseItemMapper;
import top.aole.rent.modules.roster.interfaces.RosterController;
import top.aole.rent.modules.rule.service.RuleConfigService;

import javax.sql.DataSource;
import java.util.concurrent.*;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real database + token filter + role interceptor: permissions must affect the next request. */
@SpringJUnitConfig(RosterAccountIntegrationTest.Config.class)
@Sql("/roster-accounts.sql")
class RosterAccountIntegrationTest {
    @Configuration
    @EnableTransactionManagement
    @ComponentScan(basePackages = "top.aole.rent.modules.roster.service")
    @Import({RosterController.class, AuthController.class, AuthTokenService.class,
            UserContextFilter.class, RoleGuardInterceptor.class, AuditLogService.class})
    @MapperScan({"top.aole.rent.common.auth", "top.aole.rent.common.audit",
            "top.aole.rent.modules.roster.mapper"})
    static class Config {
        @Bean DataSource dataSource() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:roster;MODE=MySQL;DB_CLOSE_DELAY=-1");
            return ds;
        }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(ds);
            return factory.getObject();
        }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) {
            return new DataSourceTransactionManager(ds);
        }
        @Bean PurchaseInMapper purchaseInMapper() { return mock(PurchaseInMapper.class); }
        @Bean PurchaseItemMapper purchaseItemMapper() { return mock(PurchaseItemMapper.class); }
        @Bean ContractMapper contractMapper() { return mock(ContractMapper.class); }
        @Bean RuleConfigService rules() { return mock(RuleConfigService.class); }
    }

    @RestController
    static class ProtectedOperation {
        @GetMapping("/test/supplier")
        @RequireRole({"供应链", "老板"})
        public R<String> supplier() { return R.ok(UserContext.getRole()); }
    }

    @Autowired RosterController roster;
    @Autowired AuthController auth;
    @Autowired UserContextFilter filter;
    @Autowired RoleGuardInterceptor guard;
    @Autowired AuthTokenService tokens;
    @Autowired DataSource ds;
    @Autowired AuthUserMapper userMapper;
    MockMvc mvc;
    JdbcTemplate jdbc;
    String bossToken;
    String colleagueToken;

    @BeforeEach void setup() {
        ReflectionTestUtils.setField(tokens, "secret", "roster-integration-test-secret");
        ReflectionTestUtils.setField(tokens, "ttlSeconds", 3600L);
        mvc = MockMvcBuilders.standaloneSetup(roster, auth, new ProtectedOperation())
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(guard)
                .addFilters(filter).build();
        jdbc = new JdbcTemplate(ds);
        bossToken = tokens.issue(101L, "小洪", "老板");
        colleagueToken = tokens.issue(202L, "小洪", "业务");
    }

    @Test void listsRealAccountsIncludingSsoAndDisabledAccountsWithoutRosterRows() throws Exception {
        mvc.perform(get("/rent/roster/accounts").header("Authorization", "Bearer " + bossToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[1].accountId").value(202))
                .andExpect(jsonPath("$.data[1].username").value("colleague"))
                .andExpect(jsonPath("$.data[1].loginSource").value("门户 SSO"))
                .andExpect(jsonPath("$.data[1].role").value("业务"))
                .andExpect(jsonPath("$.data[1].ownerScoped").value(true))
                .andExpect(jsonPath("$.data[2].active").value(false))
                .andExpect(jsonPath("$.data[2].costVisible").value(false))
                .andExpect(jsonPath("$.data[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data[1].portalUid").doesNotExist());
    }

    @Test void grantingSameNameColleagueChangesRealRoleAndExistingTokenImmediately() throws Exception {
        change(202, bossToken, "{\"role\":\"供应链\",\"active\":true}", 200);
        assertEquals("供应链", role(202));
        assertEquals("老板", role(101));
        mvc.perform(get("/test/supplier").header("Authorization", "Bearer " + colleagueToken))
                .andExpect(jsonPath("$.data").value("供应链"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM yc_rent_audit_log WHERE target_type='auth_user' AND target_id=202 AND result='EXECUTED'", Integer.class));
        assertEquals("老板", jdbc.queryForObject("SELECT role FROM yc_rent_user_role_ext WHERE id=1", String.class));
    }

    @Test void revokingRoleAndDisablingAccountTakeEffectForExistingTokens() throws Exception {
        String oldSupplyToken = tokens.issue(202L, "小洪", "供应链");
        jdbc.update("UPDATE yc_rent_auth_user SET role='供应链' WHERE id=202");
        change(202, bossToken, "{\"role\":\"业务\"}", 200);
        mvc.perform(get("/test/supplier").header("Authorization", "Bearer " + oldSupplyToken))
                .andExpect(jsonPath("$.code").value(403));
        change(202, bossToken, "{\"active\":false}", 200);
        mvc.perform(get("/rent/roster/accounts").header("Authorization", "Bearer " + colleagueToken))
                .andExpect(status().isUnauthorized());
        change(202, bossToken, "{\"active\":true}", 200);
        assertEquals(1, jdbc.queryForObject("SELECT status FROM yc_rent_auth_user WHERE id=202", Integer.class));
    }

    @Test void nonBossCannotGrantOwnPermissionsAndDenialIsAudited() throws Exception {
        change(202, colleagueToken, "{\"role\":\"老板\"}", 403);
        assertEquals("业务", role(202));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM yc_rent_audit_log WHERE result='DENIED'", Integer.class));
    }

    @Test void invalidRolesAndMissingAccountsAreRejected() throws Exception {
        change(202, bossToken, "{\"role\":\"admin\"}", 400);
        change(202, bossToken, "{\"role\":\"\"}", 400);
        change(999, bossToken, "{\"role\":\"业务\"}", 404);
        assertEquals("业务", role(202));
    }

    @Test void cannotRemoveLastEnabledBossButCanDelegateInApplication() throws Exception {
        change(101, bossToken, "{\"role\":\"业务\"}", 409);
        change(101, bossToken, "{\"active\":false}", 409);
        change(202, bossToken, "{\"role\":\"老板\"}", 200);
        change(101, colleagueToken, "{\"role\":\"业务\"}", 200);
        change(303, bossToken, "{\"role\":\"老板\"}", 403);
        assertEquals("老板", role(202));
    }

    @Test void oldRosterIdCannotSilentlyChangeAnUnrelatedAccount() throws Exception {
        mvc.perform(put("/rent/roster/1").header("Authorization", "Bearer " + bossToken)
                        .contentType("application/json").content("{\"role\":\"财务\"}"))
                .andExpect(jsonPath("$.code").value(409));
        assertEquals("老板", role(101));
    }

    @Test void unauthenticatedChangesAreRejected() throws Exception {
        mvc.perform(put("/rent/roster/accounts/202").contentType("application/json")
                .content("{\"role\":\"老板\"}")).andExpect(status().isUnauthorized());
    }

    @Test void concurrentBossesCannotRemoveEachOtherAndLeaveNoAdministrator() throws Exception {
        jdbc.update("UPDATE yc_rent_auth_user SET role='老板' WHERE id=202");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> concurrentDemotion(start, 202, bossToken));
            Future<String> second = executor.submit(() -> concurrentDemotion(start, 101, colleagueToken));
            start.countDown();
            String a = first.get(10, TimeUnit.SECONDS);
            String b = second.get(10, TimeUnit.SECONDS);
            assertTrue(a.contains("\"code\":200") || b.contains("\"code\":200"), a + b);
            assertFalse(a.contains("\"code\":200") && b.contains("\"code\":200"), a + b);
            String denied = a.contains("\"code\":200") ? b : a;
            assertTrue(denied.contains("\"code\":403") || denied.contains("\"code\":409"), denied);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM yc_rent_auth_user WHERE role='老板' AND status=1", Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test void passwordLoginCannotRestorePermissionsChangedAfterItsAccountRead() {
        jdbc.update("UPDATE yc_rent_auth_user SET password_hash=? WHERE id=202", PasswordHasher.hash("test-password"));
        AuthController controller = new AuthController(revokeAfterAccountRead(), tokens);
        Map<String, String> body = new HashMap<>();
        body.put("username", "colleague");
        body.put("password", "test-password");
        controller.login(body);
        assertRevocationSurvivedLogin();
    }

    @Test void ssoLoginCannotRestorePermissionsChangedAfterItsAccountRead() {
        SsoProperties properties = new SsoProperties();
        properties.setEnabled(true);
        properties.setPortalBaseUrl("https://portal.example.test");
        properties.setAppId("rent-test");
        properties.setClientSecret("test-client-secret");
        PortalSsoClient client = mock(PortalSsoClient.class);
        PortalJwtVerifier verifier = mock(PortalJwtVerifier.class);
        when(client.exchange("test-code")).thenReturn(new cn.hutool.json.JSONObject().set("jwt", "test-jwt"));
        when(verifier.verify("test-jwt")).thenReturn(new PortalIdentity("portal-202", "test-tenant", "小洪", null, "issuer"));
        SsoService service = new SsoService(properties, client, verifier, revokeAfterAccountRead(), tokens);
        ReflectionTestUtils.setField(service, "defaultRole", "业务");
        service.login("test-code", "rent-test", "test-tenant");
        assertRevocationSurvivedLogin();
        assertEquals("portal-202", jdbc.queryForObject("SELECT portal_uid FROM yc_rent_auth_user WHERE id=202", String.class));
    }

    /** Run a real permission change precisely between the login SELECT and UPDATE. */
    private AuthUserMapper revokeAfterAccountRead() {
        return (AuthUserMapper) Proxy.newProxyInstance(AuthUserMapper.class.getClassLoader(),
                new Class<?>[]{AuthUserMapper.class}, (proxy, method, args) -> {
                    Object result = method.invoke(userMapper, args);
                    if ("selectOne".equals(method.getName()) && result instanceof AuthUser) {
                        jdbc.update("UPDATE yc_rent_auth_user SET role='LP', status=0 WHERE id=202");
                    }
                    return result;
                });
    }

    private void assertRevocationSurvivedLogin() {
        assertEquals("LP", role(202));
        assertEquals(0, jdbc.queryForObject("SELECT status FROM yc_rent_auth_user WHERE id=202", Integer.class));
        assertNotNull(jdbc.queryForObject("SELECT last_login_at FROM yc_rent_auth_user WHERE id=202", java.sql.Timestamp.class));
    }

    @Test void firstPhoneBindingCannotCaptureAnAccountPromotedAfterTheRead() {
        jdbc.update("UPDATE yc_rent_auth_user SET username='13800000000', portal_uid=NULL WHERE id=202");
        AuthUserMapper racingMapper = (AuthUserMapper) Proxy.newProxyInstance(AuthUserMapper.class.getClassLoader(),
                new Class<?>[]{AuthUserMapper.class}, (proxy, method, args) -> {
                    Object result = method.invoke(userMapper, args);
                    if ("selectOne".equals(method.getName()) && result instanceof AuthUser) {
                        jdbc.update("UPDATE yc_rent_auth_user SET role='老板' WHERE id=202");
                    }
                    return result;
                });
        SsoProperties properties = new SsoProperties();
        properties.setEnabled(true);
        properties.setPortalBaseUrl("https://portal.example.test");
        properties.setAppId("rent-test");
        properties.setClientSecret("test-client-secret");
        PortalSsoClient client = mock(PortalSsoClient.class);
        PortalJwtVerifier verifier = mock(PortalJwtVerifier.class);
        when(client.exchange("test-code")).thenReturn(new cn.hutool.json.JSONObject().set("jwt", "test-jwt"));
        when(verifier.verify("test-jwt")).thenReturn(new PortalIdentity("new-portal", "test-tenant", "同事", "13800000000", "issuer"));
        SsoService service = new SsoService(properties, client, verifier, racingMapper, tokens);
        ReflectionTestUtils.setField(service, "defaultRole", "业务");
        top.aole.rent.common.exception.BizException error = assertThrows(top.aole.rent.common.exception.BizException.class,
                () -> service.login("test-code", "rent-test", "test-tenant"));
        assertEquals(409, error.getCode());
        assertNull(jdbc.queryForObject("SELECT portal_uid FROM yc_rent_auth_user WHERE id=202", String.class));
        assertEquals("老板", role(202));
    }

    @Test void firstPhoneBindingStillWorksForAnEnabledDefaultRoleAccount() {
        jdbc.update("UPDATE yc_rent_auth_user SET username='13800000000', portal_uid=NULL WHERE id=202");
        SsoProperties properties = new SsoProperties();
        properties.setEnabled(true);
        properties.setPortalBaseUrl("https://portal.example.test");
        properties.setAppId("rent-test");
        properties.setClientSecret("test-client-secret");
        PortalSsoClient client = mock(PortalSsoClient.class);
        PortalJwtVerifier verifier = mock(PortalJwtVerifier.class);
        when(client.exchange("test-code")).thenReturn(new cn.hutool.json.JSONObject().set("jwt", "test-jwt"));
        when(verifier.verify("test-jwt")).thenReturn(new PortalIdentity("new-portal", "test-tenant", "同事", "13800000000", "issuer"));
        SsoService service = new SsoService(properties, client, verifier, userMapper, tokens);
        ReflectionTestUtils.setField(service, "defaultRole", "业务");
        SsoService.Result result = service.login("test-code", "rent-test", "test-tenant");
        assertFalse(result.created);
        assertEquals("业务", role(202));
        assertEquals("new-portal", jdbc.queryForObject("SELECT portal_uid FROM yc_rent_auth_user WHERE id=202", String.class));
        assertNotNull(jdbc.queryForObject("SELECT last_login_at FROM yc_rent_auth_user WHERE id=202", java.sql.Timestamp.class));
    }

    private String concurrentDemotion(CountDownLatch start, long id, String token) throws Exception {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return mvc.perform(put("/rent/roster/accounts/" + id).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"role\":\"业务\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private String role(long id) {
        return jdbc.queryForObject("SELECT role FROM yc_rent_auth_user WHERE id=?", String.class, id);
    }

    private void change(long id, String token, String body, int code) throws Exception {
        mvc.perform(put("/rent/roster/accounts/" + id).header("Authorization", "Bearer " + token)
                        .contentType("application/json").content(body))
                .andExpect(jsonPath("$.code").value(code));
    }
}
