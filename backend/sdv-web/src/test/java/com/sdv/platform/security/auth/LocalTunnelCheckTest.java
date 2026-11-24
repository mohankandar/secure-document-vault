package com.sdv.platform.security.auth;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0089: local mode refuses to start behind a tunnel, and refuses a forwarded request. */
class LocalTunnelCheckTest {
    private static final List<String> NO_TUNNEL = List.of("", "C:\\Windows\\explorer.exe", "/usr/bin/java", "C:\\x\\my-cloudflared-notes.exe");

    private static void refused(String proxies, String address, List<String> commands) {
        StartupFailure f = assertThrows(StartupFailure.class, () -> new LocalTunnelCheck(proxies, address, commands));
        assertEquals(SdvErrorCode.SYS_005, f.errorCode());
    }

    @Test
    void aPlainLocalStartIsAllowed() {
        for (String address : List.of("127.0.0.1", "localhost", "::1")) {
            assertDoesNotThrow(() -> new LocalTunnelCheck("", address, NO_TUNNEL), address);
        }
    }

    @Test
    void aRunningTunnelRefusesTheStart() {
        for (String command : List.of("cloudflared", "/usr/local/bin/cloudflared", "C:\\Program Files (x86)\\cloudflared\\cloudflared.exe",
                "C:\\tools\\CLOUDFLARED.EXE", "C:\\tools\\ngrok.exe")) {
            refused("", "127.0.0.1", List.of("/usr/bin/java", command));
        }
    }

    @Test
    void aTrustedProxyRefusesTheStart() {
        refused("127.0.0.1", "127.0.0.1", NO_TUNNEL);
        refused("10.0.0.0/8", "127.0.0.1", NO_TUNNEL);
    }

    @Test
    void anotherAddressThanThisComputerRefusesTheStart() {
        for (String address : List.of("", " ", "0.0.0.0", "::", "192.168.1.20", "127.0.0.1.example.test")) refused("", address, NO_TUNNEL);
    }

    /** The context itself refuses: the check is a component of every start with the local profile, and of no other. */
    @Test
    void aStartWithTheLocalProfileIsStopped() {
        assertTrue(starts("other"), "without the local profile the check isn't there");
        assertFalse(starts("local"), "a trusted proxy stops a local start");
    }

    private static boolean starts(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        environment.setProperty("server.address", "127.0.0.1");
        environment.setProperty("SDV_TRUSTED_PROXIES", "127.0.0.1");
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment);
            context.register(LocalTunnelCheck.class);
            context.refresh();
            return true;
        } catch (org.springframework.beans.factory.BeanCreationException refused) {
            assertInstanceOf(StartupFailure.class, refused.getMostSpecificCause());
            return false;
        }
    }

    @Test
    void aForwardedRequestIsRefusedEvenFromThisComputerUnderItsOwnName() throws Exception {
        assertEquals(200, status(null));
        for (String header : List.of("Forwarded", "X-Forwarded-For", "X-Forwarded-Host", "X-Forwarded-Proto", "X-Real-IP",
                "CF-Connecting-IP", "CF-Ray", "Cdn-Loop")) {
            assertEquals(401, status(header), header);
        }
    }

    private static int status(String header) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session");
            request.setRemoteAddr("127.0.0.1");
            request.setServerName("localhost");
            request.addHeader(LocalHeaderAuthenticationFilter.TENANT_HEADER, "11111111-1111-1111-1111-111111111111");
            request.addHeader(LocalHeaderAuthenticationFilter.SUBJECT_HEADER, "local-administrator");
            request.addHeader(LocalHeaderAuthenticationFilter.ROLES_HEADER, "BUSINESS_USER");
            if (header != null) request.addHeader(header, "x");
            MockHttpServletResponse response = new MockHttpServletResponse();
            new LocalHeaderAuthenticationFilter().doFilter(request, response, new MockFilterChain());
            assertEquals(response.getStatus() == 200, SecurityContextHolder.getContext().getAuthentication() != null);
            return response.getStatus();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
