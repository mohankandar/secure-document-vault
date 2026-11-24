package com.sdv.platform.web.security;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import com.sdv.platform.security.auth.SdvPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Task 0011 Part C.2: new audit events carry the signed-in actor's name; nothing else is named. */
class SessionNamedAuditEventPortTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private final AuditEventPort store = mock(AuditEventPort.class);
    private final IngestionSourceRepository sources = mock(IngestionSourceRepository.class);
    private final SessionNamedAuditEventPort port = new SessionNamedAuditEventPort(store, sources);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static AuditEvent event(UUID tenant, String actorType, String actorId) {
        return new AuditEvent(UUID.randomUUID(), tenant, actorType, actorId, "DOCUMENT_VIEW_OPENED", "DOCUMENT",
                UUID.randomUUID().toString(), Instant.parse("2026-11-18T12:00:00Z"), "c", AuditOutcome.SUCCESS, "{}");
    }

    private AuditEvent stored(AuditEvent event) {
        port.append(event);
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(store).append(captor.capture());
        return captor.getValue();
    }

    /** Task 0062: the caller's own reference is a separate field; Vero's is never replaced. */
    @Test
    void theCallersReferenceIsKeptBesideVeros() {
        org.slf4j.MDC.put(com.sdv.platform.web.config.CorrelationIdFilter.CALLER_MDC_KEY, "case-17");
        try {
            AuditEvent saved = stored(event(TENANT, "USER", "someone"));
            assertEquals("{\"callerReference\":\"case-17\"}", saved.safeDetailsJson());
            assertEquals("c", saved.correlationId());
            AuditEvent e = event(TENANT, "USER", "x");
            AuditEvent full = new AuditEvent(e.auditEventId(), e.tenantId(), "USER", "x", e.action(), "DOCUMENT", "t", e.occurredAt(), "c",
                    AuditOutcome.SUCCESS, "{\"a\":1}");
            assertEquals("{\"callerReference\":\"case-17\",\"a\":1}", SessionNamedAuditEventPort.withCallerReference(full, "case-17").safeDetailsJson());
            assertSame(full, SessionNamedAuditEventPort.withCallerReference(full, null));
        } finally {
            org.slf4j.MDC.clear();
        }
    }

    @Test
    void aPersonsEventCarriesTheirNameFromTheSession() {
        signIn(new SdvPrincipal(TENANT, "okta|00u1", Set.of(SdvRole.BUSINESS_USER), "Maya Okafor", null));
        AuditEvent saved = stored(event(TENANT, "USER", "okta|00u1"));
        assertEquals("Maya Okafor", saved.actorDisplayName());
        assertEquals("okta|00u1", saved.actorId(), "the subject is kept as it was");
    }

    @Test
    void aSendingSystemsEventCarriesItsDisplayName() {
        IngestionSource source = mock(IngestionSource.class);
        when(source.displayName()).thenReturn("Loan Origination System");
        when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.of(source));
        signIn(new IngestionSourcePrincipal(TENANT, SOURCE, "client-los"));
        assertEquals("Loan Origination System", stored(event(TENANT, "SOURCE", "client-los")).actorDisplayName());
    }

    @Test
    void eventsAboutSomeoneElseOrWithoutASessionOrNameStayUnnamed() {
        signIn(new SdvPrincipal(TENANT, "okta|00u1", Set.of(SdvRole.BUSINESS_USER), "Maya Okafor", null));
        assertNull(port.named(event(TENANT, "USER", "okta|someone-else")).actorDisplayName());
        assertNull(port.named(event(OTHER_TENANT, "USER", "okta|00u1")).actorDisplayName());
        assertNull(port.named(event(TENANT, "SYSTEM", "okta|00u1")).actorDisplayName());
        signIn(new SdvPrincipal(TENANT, "local-business-user", Set.of(SdvRole.BUSINESS_USER)));
        assertNull(port.named(event(TENANT, "USER", "local-business-user")).actorDisplayName(), "no name claim, no name");
        SecurityContextHolder.clearContext();
        assertNull(port.named(event(TENANT, "SYSTEM", "document-finalizer")).actorDisplayName());
    }

    @Test
    void springUsesTheMarkedPublicConstructor() {
        // Two constructors: without @Autowired on the public one, Spring looks for a no-argument one and the app
        // does not start (seen while building task 0011).
        var marked = java.util.Arrays.stream(SessionNamedAuditEventPort.class.getConstructors())
                .filter(c -> c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)).toList();
        assertEquals(1, marked.size());
        assertEquals(com.sdv.platform.persistence.foundation.JdbcFoundationRepository.class, marked.getFirst().getParameterTypes()[0]);
    }

    @Test
    void aSourceLookupFailureStillWritesTheEvent() {
        when(sources.findById(TENANT, SOURCE)).thenThrow(new IllegalStateException("database down"));
        signIn(new IngestionSourcePrincipal(TENANT, SOURCE, "client-los"));
        assertNull(stored(event(TENANT, "SOURCE", "client-los")).actorDisplayName());
    }
}
