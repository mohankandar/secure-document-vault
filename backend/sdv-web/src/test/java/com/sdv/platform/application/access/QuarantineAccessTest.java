package com.sdv.platform.application.access;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0048: while a document is quarantined, every way of reaching it is refused with SDV-QUA-001 ("temporarily
 * unavailable", never the level or why) and audited, except for a reviewer who can open its level, who may read and
 * correct it. Nobody prints or downloads it.
 */
class QuarantineAccessTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final BusinessRecordRepository records = mock(BusinessRecordRepository.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final AuthorizationService authorization =
            new AuthorizationService(audit, new CapabilityPolicyService(mock(RoleCapabilityPolicyPort.class)));
    private final Set<UUID> quarantined = new java.util.HashSet<>();
    private final QuarantineLookup lookup = new QuarantineLookup() {
        @Override public boolean isQuarantined(UUID tenantId, UUID documentId) { return quarantined.contains(documentId); }
        @Override public Set<UUID> quarantined(UUID tenantId, Collection<UUID> ids) {
            return ids.stream().filter(quarantined::contains).collect(java.util.stream.Collectors.toSet());
        }
    };
    private final DocumentAccessGuard guard = new DocumentAccessGuard(records, audit, SensitiveAccessGrants.NONE, lookup);

    private Document document(SensitivityLevel level) {
        when(records.findMetadataSnapshots(any(), any(), any())).thenReturn(List.of());
        when(records.findRecordSensitivityLevel(any(), any(), any())).thenReturn(Optional.empty());
        return new Document(UUID.randomUUID(), TENANT, UUID.randomUUID(), UUID.randomUUID(), "d1", null, "secret.pdf",
                "application/pdf", "a".repeat(64), 10L, DocumentStatus.AVAILABLE, null, Instant.now(), null, Instant.now(),
                1, null, null, level);
    }

    private Clearance clearance(SdvRole role) {
        return authorization.clearance(new SdvPrincipal(TENANT, role.name().toLowerCase(), Set.of(role)));
    }

    private String attempt(Document document, SdvRole role, DocumentAccessGuard.Attempt attempt) {
        try {
            if (attempt == DocumentAccessGuard.Attempt.PRINT || attempt == DocumentAccessGuard.Attempt.DOWNLOAD) {
                guard.requirePrintOrDownload(document, clearance(role), attempt, "p", "c", SensitiveAccessGrants.Use.CHECK);
            } else {
                guard.requireOpenable(document, clearance(role), attempt, "p", "c");
            }
            return "OK";
        } catch (CodedRefusal.Forbidden ex) {
            return ex.errorCode().code();
        }
    }

    @Test
    void reviewRightsFollowTheRoles() {
        assertTrue(clearance(SdvRole.DOCUMENT_STEWARD).reviewsQuarantine());
        assertTrue(clearance(SdvRole.RESTRICTED_STEWARD).reviewsQuarantine());
        for (SdvRole role : EnumSet.of(SdvRole.BUSINESS_USER, SdvRole.BUSINESS_ADMIN, SdvRole.IT_ADMIN)) {
            assertFalse(clearance(role).reviewsQuarantine(), role + " never reviews");
        }
    }

    @Test
    void everyoneButAReviewerIsToldItIsTemporarilyUnavailable() {
        Document standard = document(SensitivityLevel.STANDARD);
        assertEquals("OK", attempt(standard, SdvRole.BUSINESS_USER, DocumentAccessGuard.Attempt.OPEN), "not quarantined yet");
        quarantined.add(standard.documentId());
        for (DocumentAccessGuard.Attempt a : DocumentAccessGuard.Attempt.values()) {
            assertEquals("SDV-QUA-001", attempt(standard, SdvRole.BUSINESS_USER, a), a.name());
        }
        CodedRefusal.Forbidden refusal = assertThrows(CodedRefusal.Forbidden.class,
                () -> guard.requireOpenable(standard, clearance(SdvRole.BUSINESS_USER), DocumentAccessGuard.Attempt.OPEN, "p", "c"));
        assertEquals(DocumentAccessGuard.UNAVAILABLE_MESSAGE, refusal.getMessage());
    }

    @Test
    void aReviewerReadsAndCorrectsButNeverPrintsOrDownloads() {
        Document confidential = document(SensitivityLevel.CONFIDENTIAL);
        quarantined.add(confidential.documentId());
        for (DocumentAccessGuard.Attempt a : List.of(DocumentAccessGuard.Attempt.OPEN, DocumentAccessGuard.Attempt.PAGE,
                DocumentAccessGuard.Attempt.PREVIEWS, DocumentAccessGuard.Attempt.REDACT)) {
            assertEquals("OK", attempt(confidential, SdvRole.DOCUMENT_STEWARD, a), a.name());
        }
        for (DocumentAccessGuard.Attempt a : List.of(DocumentAccessGuard.Attempt.PRINT, DocumentAccessGuard.Attempt.DOWNLOAD,
                DocumentAccessGuard.Attempt.ADD_TAG, DocumentAccessGuard.Attempt.ADD_COMMENT)) {
            assertEquals("SDV-QUA-001", attempt(confidential, SdvRole.DOCUMENT_STEWARD, a), a.name());
            assertEquals("SDV-QUA-001", attempt(confidential, SdvRole.RESTRICTED_STEWARD, a), a.name());
        }
    }

    @Test
    void highlyConfidentialIsReviewedOnlyByARestrictedStewardAndTheLevelIsNeverSaid() {
        Document high = document(SensitivityLevel.HIGHLY_CONFIDENTIAL);
        quarantined.add(high.documentId());
        assertEquals("SDV-QUA-001", attempt(high, SdvRole.DOCUMENT_STEWARD, DocumentAccessGuard.Attempt.OPEN),
                "a Steward is told it is unavailable, not that its level is restricted");
        assertEquals("OK", attempt(high, SdvRole.RESTRICTED_STEWARD, DocumentAccessGuard.Attempt.OPEN));
    }

    @Test
    void theRefusalIsAudited() {
        Document standard = document(SensitivityLevel.STANDARD);
        quarantined.add(standard.documentId());
        attempt(standard, SdvRole.BUSINESS_USER, DocumentAccessGuard.Attempt.DOWNLOAD);
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        assertEquals(DocumentAccessGuard.REFUSED_ACTION, event.getValue().action());
        assertEquals(standard.documentId().toString(), event.getValue().targetId());
        assertTrue(event.getValue().safeDetailsJson().contains("\"code\":\"SDV-QUA-001\""));
        assertTrue(event.getValue().safeDetailsJson().contains("\"attempt\":\"DOWNLOAD\""));
        assertFalse(event.getValue().safeDetailsJson().contains("secret"));
    }

    @Test
    void theRecordViewsAskForManyAtOnce() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        quarantined.add(a);
        assertEquals(Set.of(a), guard.quarantined(TENANT, List.of(a, b)));
    }
}
