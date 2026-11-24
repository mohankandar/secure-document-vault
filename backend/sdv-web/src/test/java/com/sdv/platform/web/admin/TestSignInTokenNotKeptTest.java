package com.sdv.platform.web.admin;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.LoginConnectionStore;
import com.documentplatform.identity.testkit.MockIssuer;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.security.auth.oidc.OidcJwtAuthenticationConverter;
import com.sdv.platform.web.security.ActiveConnectionCheck;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * Task 0058 (owner answer 2 to report 0057): the test sign-in's audience check uses the IT Admin's own token, which is
 * handed on and forgotten. This runs the real check (platform-core's {@code OidcTestSignIn} against its
 * {@link MockIssuer}) through the endpoint, with a token that passes and one that fails, and proves the token, or any
 * of its three parts, never appears in a log line (at every level), in anything sent to the audit trail, or in the
 * answer.
 */
class TestSignInTokenNotKeptTest {
    static final String SLOT = "ENT-ACME-0001";
    static final UUID ENTITY = EntityUuid.fromSlotId(SLOT);
    static final String AUDIENCE = "sdv-api";

    final MockIssuer issuer = MockIssuer.start();
    final AuditEventPort audit = mock(AuditEventPort.class);
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    Level rootLevel;

    @BeforeEach
    void captureEveryLogLine() {
        rootLevel = root.getLevel();
        root.setLevel(Level.TRACE);
        logs.start();
        root.addAppender(logs);
    }

    @AfterEach
    void restore() {
        root.detachAppender(logs);
        root.setLevel(rootLevel);
        SecurityContextHolder.clearContext();
        issuer.close();
    }

    String token(String audience) {
        var claims = issuer.claims(audience, Duration.ofMinutes(5));
        claims.put("aud", audience);
        claims.put("groups", List.of("acme-it"));
        return issuer.sign(claims);
    }

    LoginConnectionAdminController controller() {
        RoleCapabilityPolicyPort overrides = mock(RoleCapabilityPolicyPort.class);
        when(overrides.findOverrides(any(), anyString())).thenReturn(Map.of());
        AuthorizationService authorization = new AuthorizationService(audit, new CapabilityPolicyService(overrides));
        PlatformIdentityReads reads = mock(PlatformIdentityReads.class);
        when(reads.slotOf(ENTITY)).thenReturn(Optional.of(new PlatformIdentityReads.EntitySlot("TEN-ACME-0001", SLOT, "ACTIVE", "Acme Mortgage")));
        LoginConnectionStore store = mock(LoginConnectionStore.class);
        when(store.active("TEN-ACME-0001", "SDV")).thenReturn(Optional.of(new LoginConnection(UUID.randomUUID(), "TEN-ACME-0001",
                "SDV", "customer-oidc", IdentityProviderType.OIDC, issuer.issuer(), "sdv-web", AUDIENCE, null,
                IdentityConnectionStatus.ACTIVE, 1)));
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("fetch", MockIssuer.fetch());
        DevelopmentLicence development = () -> true;
        var check = new ActiveConnectionCheck(beans.getBeanProvider(IssuerFetch.class), development, "groups");
        return new LoginConnectionAdminController(new SecurityContextFacade(), authorization, reads, store, check, "enterprise-jwt");
    }

    static void signInAsItAdmin() {
        var auth = new UsernamePasswordAuthenticationToken(new SdvPrincipal(ENTITY, "it", Set.of(SdvRole.IT_ADMIN)), null, List.of());
        auth.setDetails(new OidcJwtAuthenticationConverter.VerifiedSignIn(
                new ConnectionKey("customer-oidc", URI.create("https://login.example.test"), AUDIENCE), List.of("acme-it")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    String answerJson(String token) throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        signInAsItAdmin();
        var answer = controller().test(request);
        return com.sdv.platform.application.json.VeroJson.mapper().writeValueAsString(answer);
    }

    /** Everything written while the test ran: each log line (message, arguments, error) and each audit call. */
    String everythingWritten() {
        List<String> out = new ArrayList<>();
        for (ILoggingEvent e : logs.list) {
            out.add(e.getFormattedMessage());
            out.add(e.getMessage());
            if (e.getArgumentArray() != null) out.add(Arrays.deepToString(e.getArgumentArray()));
            if (e.getThrowableProxy() != null) out.add(e.getThrowableProxy().getMessage());
            out.add(String.valueOf(e.getMDCPropertyMap()));
        }
        for (Invocation call : mockingDetails(audit).getInvocations()) {
            out.add(Arrays.deepToString(call.getArguments()));
        }
        return String.join("\n", out);
    }

    static void assertNoPartOf(String token, String text, String where) {
        assertFalse(text.contains(token), "the token appears in " + where);
        for (String part : token.split("\\.")) {
            assertFalse(text.contains(part), "a part of the token appears in " + where);
        }
    }

    @Test
    void aPassingTokenIsNeverLoggedAuditedOrAnswered() throws Exception {
        String token = token(AUDIENCE);
        String answer = answerJson(token);
        assertTrue(answer.contains("\"outcome\":\"PASSED\""), answer);
        assertTrue(answer.contains("\"tokenChecked\":true"), "the admin's own token was checked: " + answer);
        assertFalse(logs.list.isEmpty(), "the test writes its INFO line, so the log capture works");

        assertNoPartOf(token, answer, "the answer");
        assertNoPartOf(token, everythingWritten(), "a log line or the audit trail");
    }

    @Test
    void aFailingTokenIsNeverLoggedAuditedOrAnsweredEither() throws Exception {
        String token = token("another-api");
        String answer = answerJson(token);
        assertTrue(answer.contains("\"outcome\":\"FAILED\""), answer);
        assertTrue(answer.contains("IDN_TEST_AUDIENCE"), answer);

        assertNoPartOf(token, answer, "the answer");
        assertNoPartOf(token, everythingWritten(), "a log line or the audit trail");
        assertEquals(1, logs.list.stream().filter(e -> e.getLoggerName().equals(ActiveConnectionCheck.class.getName())).count(),
                "one INFO line from Vero's check");
    }
}
