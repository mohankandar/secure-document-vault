package com.sdv.platform.security.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

/** Owner, 2026-11-18: the fixed local test sign-ins carry display names (local profile only); nothing else does. */
class LocalHeaderTestNamesTest {
    private final LocalHeaderAuthenticationFilter filter = new LocalHeaderAuthenticationFilter();

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private SdvPrincipal signIn(String subject, String roles, String extraHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session");
        request.addHeader(LocalHeaderAuthenticationFilter.TENANT_HEADER, "11111111-1111-1111-1111-111111111111");
        request.addHeader(LocalHeaderAuthenticationFilter.SUBJECT_HEADER, subject);
        request.addHeader(LocalHeaderAuthenticationFilter.ROLES_HEADER, roles);
        if (extraHeader != null) request.addHeader(extraHeader, "Mallory Spoofed");
        final SdvPrincipal[] seen = new SdvPrincipal[1];
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen[0] = (SdvPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            }
        });
        return seen[0];
    }

    @Test
    void eachFixedLocalTestSignInHasADisplayName() throws Exception {
        assertEquals("Daniel Reyes", signIn("local-document-steward", "DOCUMENT_STEWARD", null).displayName());
        assertEquals("Maya Okafor", signIn("local-business-user", "BUSINESS_USER", null).displayName());
        assertEquals("Dana Ruiz", signIn("local-restricted-steward", "RESTRICTED_STEWARD", null).displayName(), "task 0029");
        assertEquals("Ari Lindqvist", signIn("local-administrator", "IT_ADMIN,BUSINESS_ADMIN", null).displayName());
        assertEquals("Aiko Tanaka", signIn("local-platform-operations-admin", "PLATFORM_OPERATIONS_ADMIN", null).displayName());
        for (String subject : LocalHeaderAuthenticationFilter.LOCAL_TEST_NAMES.keySet()) {
            assertFalse(LocalHeaderAuthenticationFilter.LOCAL_TEST_NAMES.get(subject).isBlank(), subject);
        }
    }

    @Test
    void eachFixedLocalTestSignInHasAWorkEmail() throws Exception {
        assertEquals("maya.okafor@acmelending.com", signIn("local-business-user", "BUSINESS_USER", null).email());
        assertEquals(LocalHeaderAuthenticationFilter.LOCAL_TEST_NAMES.keySet(), LocalHeaderAuthenticationFilter.LOCAL_TEST_EMAILS.keySet());
    }

    @Test
    void anyOtherSubjectStaysUnnamedAndANameHeaderIsIgnored() throws Exception {
        SdvPrincipal other = signIn("someone-else", "BUSINESS_USER", "X-SDV-Display-Name");
        assertEquals("someone-else", other.subject());
        assertNull(other.displayName());
        assertNull(other.email());
    }

    @Test
    void theNamesExistOnlyInTheLocalProfileFilter() {
        assertNotNull(LocalHeaderAuthenticationFilter.class.getAnnotation(org.springframework.context.annotation.Profile.class));
        assertArrayEquals(new String[]{"local"},
                LocalHeaderAuthenticationFilter.class.getAnnotation(org.springframework.context.annotation.Profile.class).value());
    }
}
