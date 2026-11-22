package com.knowledgehub.auth;

import com.knowledgehub.ApiTestSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountAccessTest extends ApiTestSupport {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminBootstrap bootstrap;

    @Test
    void registrationCreatesEnabledUserAndDefaultLibrary() throws Exception {
        String username = uniqueUsername();
        var user = registerAndLogin(username);
        var reply = call(user, "GET", "/api/auth/me", null);
        assertEquals(200, reply.status());
        assertEquals("USER", reply.json().path("role").asText());
        var hash = jdbc.queryForObject("select password_hash from accounts where id = ?", String.class, user.accountId());
        assertNotEquals(TEST_PASSWORD, hash);
        assertTrue(org.springframework.security.crypto.factory.PasswordEncoderFactories
                .createDelegatingPasswordEncoder().matches(TEST_PASSWORD, hash));
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select enabled from accounts where id = ?", Boolean.class, user.accountId()));
        assertEquals("我的知识库", jdbc.queryForObject(
                "select name from libraries where owner_id = ?", String.class, user.accountId()));
    }

    @Test
    void adminRoutesAndCsrfAreEnforced() throws Exception {
        var user = registerAndLogin(uniqueUsername());
        var admin = loginConfiguredAdmin();
        assertEquals(401, call(null, "GET", "/api/admin/users", null).status());
        assertEquals(403, call(user, "GET", "/api/admin/users", null).status());
        var list = call(admin, "GET", "/api/admin/users", null);
        assertEquals(200, list.status());
        for (var item : list.json().path("items")) {
            assertNotEquals("kh_test_admin", item.path("username").asText());
        }
        mvc.perform(MockMvcRequestBuilders.put("/api/admin/users/" + user.accountId() + "/status")
                        .session(admin.session()).contentType("application/json").content("{\"enabled\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void bootstrapPreservesCredentials() throws Exception {
        var admin = loginConfiguredAdmin();
        var before = jdbc.queryForObject("select password_hash from accounts where id = ?", String.class, admin.accountId());
        bootstrap.initialize("kh_test_admin", "AnotherPassword1234");
        assertEquals(before, jdbc.queryForObject(
                "select password_hash from accounts where id = ?", String.class, admin.accountId()));
        assertEquals(admin.accountId(), loginConfiguredAdmin().accountId());
    }

    @Test
    void authThrottleReturns429() throws Exception {
        String username = uniqueUsername();
        registerAndLogin(username);
        for (int i = 0; i < 5; i++) {
            assertEquals(401, call(null, "POST", "/api/auth/login", credentials(username, "invalid")).status());
        }
        assertEquals(429, call(null, "POST", "/api/auth/login", credentials(username, TEST_PASSWORD)).status());
    }

    @Test
    void restoredAccountDoesNotReviveUntouchedSession() throws Exception {
        String username = uniqueUsername();
        var old = registerAndLogin(username);
        var admin = loginConfiguredAdmin();
        var userStatus = "/api/admin/users/" + old.accountId() + "/status";
        assertEquals(204, call(admin, "PUT", userStatus, Map.of("enabled", false)).status());
        assertEquals(204, call(admin, "PUT", userStatus, Map.of("enabled", true)).status());
        assertEquals(401, call(old, "GET", "/api/auth/me", null).status());
        assertEquals(200, call(actor(username, TEST_PASSWORD), "GET", "/api/auth/me", null).status());
    }
}
