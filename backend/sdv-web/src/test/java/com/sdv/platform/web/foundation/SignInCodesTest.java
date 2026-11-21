package com.sdv.platform.web.foundation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.access.ActiveTenant;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantGuardrails;
import com.sdv.platform.domain.tenant.TenantStatus;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.CapabilityDenied;
import com.sdv.platform.security.auth.LocalHeaderAuthenticationFilter;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.security.config.SignInAnswers;
import com.sdv.platform.web.config.CorrelationIdFilter;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Task 0038: the sign-in and role codes that don't need a token: sign-in switched off (SYS-006), the local test
 * headers (AUT-008, ROL-004), not signed in at a controller (AUT-001), the role (ROL-006), and the organisation
 * (AUT-009: unknown and suspended give the identical body; the log keeps which).
 */
class SignInCodesTest {
    private static final UUID KNOWN = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID SUSPENDED = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID UNKNOWN = UUID.fromString("10000000-0000-0000-0000-000000000003");

    private final ObjectMapper json = new ObjectMapper();
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(ErrorReplies.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ErrorReplies.class)).detachAppender(logs);
        SecurityContextHolder.clearContext();
    }

    /** As the servlet container does after sendError: the same request goes to Vero's error page. */
    private JsonNode errorPage(MockHttpServletRequest request, MockHttpServletResponse filtered) throws Exception {
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, filtered.getStatus());
        MockHttpServletResponse page = new MockHttpServletResponse();
        new SdvErrorController().error(request, page);
        assertEquals(filtered.getStatus(), page.getStatus());
        return json.readTree(page.getContentAsString());
    }

    private List<ILoggingEvent> lines(Level level, SdvErrorCode code) {
        return logs.list.stream().filter(e -> e.getLevel() == level && e.getFormattedMessage().contains("code=" + code.code())).toList();
    }

    @Test
    void whileSignInIsOffEveryRefusalIs401Sys006SayingSo() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session");
        request.setAttribute(CorrelationIdFilter.REFERENCE_ATTRIBUTE, "ref-off");
        MockHttpServletResponse response = new MockHttpServletResponse();
        SignInAnswers.entryPoint("disabled").commence(request, response, new InsufficientAuthenticationException("anonymous"));
        assertEquals(401, response.getStatus());
        assertNull(response.getHeader("WWW-Authenticate"), "no bearer header: no sign-in is offered");
        JsonNode body = errorPage(request, response);
        assertEquals("SDV-SYS-006", body.get("code").asText());
        assertEquals("Sign-in is switched off for Vero, so nobody can sign in yet. Your IT team has been told.",
                body.get("message").asText());
        assertEquals("ref-off", body.get("reference").asText());
        assertEquals(1, lines(Level.WARN, SdvErrorCode.SYS_006).size(), "it needs a person: WARN");
    }

    @Test
    void localTestHeadersThatAreWrongAre401Aut008NamingTheHeaderNotTheValue() throws Exception {
        var filter = new LocalHeaderAuthenticationFilter(conflict -> { });
        String[][] cases = {
                {null, "local-business-user", "BUSINESS_USER", "X-SDV-Tenant-Id and X-SDV-Subject are required"},
                {"not-a-uuid-secret-1", "local-business-user", "BUSINESS_USER", "X-SDV-Tenant-Id is not a UUID"},
                {KNOWN.toString(), "local-business-user", "SUPER_USER_secret-2", "X-SDV-Roles names a role Vero doesn't have"},
        };
        for (String[] c : cases) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session");
            if (c[0] != null) request.addHeader(LocalHeaderAuthenticationFilter.TENANT_HEADER, c[0]);
            request.addHeader(LocalHeaderAuthenticationFilter.SUBJECT_HEADER, c[1]);
            request.addHeader(LocalHeaderAuthenticationFilter.ROLES_HEADER, c[2]);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertEquals(401, response.getStatus());
            JsonNode body = errorPage(request, response);
            assertEquals("SDV-AUT-008", body.get("code").asText());
            assertEquals("Local sign-in headers are not valid: " + c[3] + ".", body.get("message").asText());
            assertFalse(body.toString().contains("secret"), "the value sent is not repeated: " + body);
        }
        assertEquals(3, lines(Level.INFO, SdvErrorCode.AUT_008).size());
        for (ILoggingEvent line : logs.list) assertFalse(line.getFormattedMessage().contains("secret"));
    }

    @Test
    void aLocalSignInLeftWithNoRoleIs401Rol004WithItsSentence() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/session");
        request.addHeader(LocalHeaderAuthenticationFilter.TENANT_HEADER, KNOWN.toString());
        request.addHeader(LocalHeaderAuthenticationFilter.SUBJECT_HEADER, "local-administrator");
        request.addHeader(LocalHeaderAuthenticationFilter.ROLES_HEADER, "RESTRICTED_STEWARD,BUSINESS_ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new LocalHeaderAuthenticationFilter(conflict -> { }).doFilter(request, response, new MockFilterChain());
        JsonNode body = errorPage(request, response);
        assertEquals("SDV-ROL-004", body.get("code").asText());
        assertEquals("The roles from this sign-in can never be held together, so none of them were given. Ask your IT "
                + "team to correct your sign-in groups.", body.get("message").asText());
    }

    @Test
    void aRefusalByTheFiltersAccessRuleIs403Rol006() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        SignInAnswers.accessDeniedHandler().handle(request, response, new AccessDeniedException("Access is denied"));
        JsonNode body = errorPage(request, response);
        assertEquals(403, response.getStatus());
        assertEquals("SDV-ROL-006", body.get("code").asText());
        assertEquals("Your role doesn't include this action. Ask someone who has it.", body.get("message").asText());
    }

    @RestController
    static class Guarded {
        private final TenantRepository tenants;

        Guarded(TenantRepository tenants) { this.tenants = tenants; }

        @GetMapping("/hold") void hold() { throw new CapabilityDenied(Capability.MANAGE_LEGAL_HOLD); }
        @GetMapping("/plain-denied") void plainDenied() { throw new AccessDeniedException("internal capability name"); }
        @GetMapping("/session") void session() { new SecurityContextFacade().requirePrincipal(); }
        @GetMapping("/tenant/{id}") void tenant(@PathVariable UUID id) { ActiveTenant.require(tenants, id); }
    }

    private MockMvc mvc() {
        TenantRepository tenants = mock(TenantRepository.class);
        Instant now = Instant.parse("2026-11-21T10:00:00Z");
        when(tenants.findById(KNOWN)).thenReturn(Optional.of(new Tenant(KNOWN, "acme", "Acme", TenantStatus.ACTIVE, TenantGuardrails.defaults(), now, now)));
        when(tenants.findById(SUSPENDED)).thenReturn(Optional.of(new Tenant(SUSPENDED, "gone", "Gone", TenantStatus.SUSPENDED, TenantGuardrails.defaults(), now, now)));
        when(tenants.findById(UNKNOWN)).thenReturn(Optional.empty());
        return MockMvcBuilders.standaloneSetup(new Guarded(tenants)).setControllerAdvice(new FoundationExceptionHandler())
                .addFilters(new CorrelationIdFilter()).build();
    }

    @Test
    void aRoleWithoutTheActionIs403Rol006InPlainWordsWithTheCapabilityInTheLog() throws Exception {
        MockMvc mvc = mvc();
        var result = mvc.perform(get("/hold")).andReturn();
        assertEquals(403, result.getResponse().getStatus());
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertEquals("SDV-ROL-006", body.get("code").asText());
        assertEquals("Your role doesn't include managing legal holds. Ask someone who has it.", body.get("message").asText());
        List<ILoggingEvent> info = lines(Level.INFO, SdvErrorCode.ROL_006);
        assertEquals(1, info.size());
        assertTrue(info.getFirst().getFormattedMessage().endsWith("reason=capability=MANAGE_LEGAL_HOLD"));

        JsonNode plain = json.readTree(mvc.perform(get("/plain-denied")).andReturn().getResponse().getContentAsString());
        assertEquals("SDV-ROL-006", plain.get("code").asText(), "an uncoded refusal gets the code too (was FORBIDDEN)");
        assertFalse(plain.toString().contains("internal capability name"));
    }

    @Test
    void everyCapabilityHasPlainWords() {
        for (Capability capability : Capability.values()) {
            String words = CapabilityDenied.inPlainWords(capability);
            assertFalse(words.isBlank(), capability.name());
            assertFalse(words.contains("_"), capability.name() + " is shown in plain words, not its name");
        }
    }

    @Test
    void noSignInAtAControllerIs401Aut001() throws Exception {
        var result = mvc().perform(get("/session")).andReturn();
        assertEquals(401, result.getResponse().getStatus());
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        assertEquals("SDV-AUT-001", body.get("code").asText(), "was UNAUTHORIZED");
        assertEquals("Please sign in again.", body.get("message").asText());
    }

    @Test
    void anUnknownAndASuspendedTenantGetTheIdenticalBodyAndTheLogKeepsWhich() throws Exception {
        MockMvc mvc = mvc();
        List<Map<String, String>> bodies = new ArrayList<>();
        for (UUID tenant : new UUID[]{UNKNOWN, SUSPENDED}) {
            var response = mvc.perform(get("/tenant/" + tenant)).andReturn().getResponse();
            assertEquals(403, response.getStatus());
            JsonNode body = json.readTree(response.getContentAsString());
            assertEquals(List.of("code", "message", "reference", "timestamp"), names(body));
            bodies.add(Map.of("code", body.get("code").asText(), "message", body.get("message").asText()));
            assertFalse(response.getContentAsString().contains(tenant.toString()), "the body doesn't name the tenant");
        }
        assertEquals(bodies.get(0), bodies.get(1), "identical apart from the reference and the time");
        assertEquals("SDV-AUT-009", bodies.get(0).get("code"));
        assertEquals("Your organisation's Vero account isn't available. Contact your IT team.", bodies.get(0).get("message"));

        List<ILoggingEvent> warn = lines(Level.WARN, SdvErrorCode.AUT_009);
        assertEquals(2, warn.size());
        assertTrue(warn.get(0).getFormattedMessage().contains("tenant=" + UNKNOWN + " missing"));
        assertTrue(warn.get(1).getFormattedMessage().contains("tenant=" + SUSPENDED + " suspended"));
        assertEquals(200, mvc.perform(get("/tenant/" + KNOWN)).andReturn().getResponse().getStatus());
    }

    private static List<String> names(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
