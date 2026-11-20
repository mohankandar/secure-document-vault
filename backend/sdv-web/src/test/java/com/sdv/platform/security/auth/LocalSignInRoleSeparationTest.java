package com.sdv.platform.security.auth;

import com.sdv.platform.security.SdvRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0025, rule 1, on the local-mode sign-in path (X-SDV-Roles). */
class LocalSignInRoleSeparationTest {
    private final List<RoleConflictReporter.RoleConflict> reported = new ArrayList<>();

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private record Outcome(SdvPrincipal principal, MockHttpServletResponse response) { }

    private static Outcome signIn(LocalHeaderAuthenticationFilter filter, String roles) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session");
        request.addHeader(LocalHeaderAuthenticationFilter.TENANT_HEADER, "11111111-1111-1111-1111-111111111111");
        request.addHeader(LocalHeaderAuthenticationFilter.SUBJECT_HEADER, "local-administrator");
        request.addHeader(LocalHeaderAuthenticationFilter.ROLES_HEADER, roles);
        MockHttpServletResponse response = new MockHttpServletResponse();
        final SdvPrincipal[] seen = new SdvPrincipal[1];
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen[0] = (SdvPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            }
        });
        return new Outcome(seen[0], response);
    }

    @Test
    void conflictingLocalRolesAreWithheldAndReported() throws Exception {
        var filter = new LocalHeaderAuthenticationFilter(reported::add);
        Outcome outcome = signIn(filter, "RESTRICTED_STEWARD,TENANT_IT_ADMIN,BUSINESS_USER");
        assertEquals(Set.of(SdvRole.BUSINESS_USER), outcome.principal().roles());
        assertEquals(1, reported.size());
        assertEquals(RoleConflictReporter.Source.LOCAL_SIGN_IN, reported.get(0).source());
        assertEquals("Ari Lindqvist", reported.get(0).displayName());
    }

    @Test
    void aLocalSignInLeftWithNoRoleIsRefused() throws Exception {
        var filter = new LocalHeaderAuthenticationFilter(reported::add);
        Outcome outcome = signIn(filter, "RESTRICTED_STEWARD,BUSINESS_ADMIN");
        assertNull(outcome.principal());
        assertEquals(401, outcome.response().getStatus());
        assertTrue(outcome.response().getErrorMessage().startsWith("SDV-ROL-004"), outcome.response().getErrorMessage());
        assertEquals(1, reported.size());
    }

    @Test
    void withoutAReporterAConflictIsRefused() throws Exception {
        Outcome outcome = signIn(new LocalHeaderAuthenticationFilter(), "RESTRICTED_STEWARD,PLATFORM_OPERATIONS_ADMIN,BUSINESS_USER");
        assertNull(outcome.principal());
        assertEquals(401, outcome.response().getStatus());
        assertTrue(outcome.response().getErrorMessage().startsWith("SDV-ROL-005"));
    }

    @Test
    void aRestrictedStewardAloneSignsIn() throws Exception {
        Outcome outcome = signIn(new LocalHeaderAuthenticationFilter(reported::add), "RESTRICTED_STEWARD");
        assertEquals(Set.of(SdvRole.RESTRICTED_STEWARD), outcome.principal().roles());
        assertTrue(reported.isEmpty());
    }
}
