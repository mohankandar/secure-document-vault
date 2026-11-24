package com.sdv.platform.security.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0064: local sign-in works from this computer only, and only under its own names. */
class LocalSignInAddressTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static int status(String remote, String host, boolean source) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session");
        request.setRemoteAddr(remote);
        request.setServerName(host);
        request.addHeader(LocalHeaderAuthenticationFilter.TENANT_HEADER, "11111111-1111-1111-1111-111111111111");
        request.addHeader(LocalHeaderAuthenticationFilter.SUBJECT_HEADER, "local-administrator");
        if (source) request.addHeader(LocalHeaderAuthenticationFilter.SOURCE_HEADER, "22222222-2222-2222-2222-222222222222");
        else request.addHeader(LocalHeaderAuthenticationFilter.ROLES_HEADER, "BUSINESS_USER");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new LocalHeaderAuthenticationFilter().doFilter(request, response, new MockFilterChain());
        assertEquals(response.getStatus() == 200, SecurityContextHolder.getContext().getAuthentication() != null);
        return response.getStatus();
    }

    @Test
    void thisComputerUnderItsOwnNames() throws Exception {
        for (String remote : new String[] {"127.0.0.1", "127.0.0.2", "::1", "0:0:0:0:0:0:0:1"}) {
            for (String host : new String[] {"localhost", "LOCALHOST", "127.0.0.1", "[::1]"}) {
                assertEquals(200, status(remote, host, false), remote + " " + host);
                assertEquals(200, status(remote, host, true), remote + " " + host);
            }
        }
    }

    @Test
    void anotherComputerIsRefused() throws Exception {
        for (String remote : new String[] {"192.168.1.20", "10.0.0.5", "fe80::1", "203.0.113.9", "localhost", "", null}) {
            assertEquals(401, status(remote, "localhost", false), String.valueOf(remote));
            assertEquals(401, status(remote, "localhost", true), String.valueOf(remote));
        }
    }

    @Test
    void anotherHostIsRefused() throws Exception {
        for (String host : new String[] {"example.test", "localhost.example.test", "127.0.0.1.example.test", "0.0.0.0", "192.168.1.20", ""}) {
            assertEquals(401, status("127.0.0.1", host, false), host);
            assertEquals(401, status("127.0.0.1", host, true), host);
        }
    }

    @Test
    void theLocalProfileListensOnThisComputerAndHasNoPassword() throws Exception {
        var local = new YamlPropertySourceLoader().load("local", new ClassPathResource("application-local.yml")).get(0);
        assertEquals("127.0.0.1", local.getProperty("server.address"));
        assertEquals("${SDV_LOCAL_DB_PASSWORD:}", local.getProperty("spring.datasource.password"));
    }
}
