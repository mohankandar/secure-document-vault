package com.sdv.platform.application.legalhold;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.LegalHoldRepository;
import com.sdv.platform.application.port.LegalHoldRepository.DocumentFacts;
import com.sdv.platform.application.port.LegalHoldRepository.LegalHold;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0027: the legal-hold rules, with the repository and the transaction mocked. */
class LegalHoldServiceTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SUB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID DOCUMENT = UUID.fromString("40000000-0000-0000-0000-000000000001");
    private static final UUID HOLD = UUID.fromString("50000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");

    private final LegalHoldRepository repository = mock(LegalHoldRepository.class);
    private final BusinessRecordRepository records = mock(BusinessRecordRepository.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final TransactionStatus txStatus = new SimpleTransactionStatus();
    private final LegalHoldService service;

    LegalHoldServiceTest() {
        when(tx.getTransaction(any())).thenReturn(txStatus);
        service = new LegalHoldService(repository, new DocumentAccessGuard(records, audit), audit, tx,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static DocumentFacts facts(String status, UUID activeHold) {
        return new DocumentFacts(DOCUMENT, SOURCE, SUB, status, SensitivityLevel.CONFIDENTIAL, NOW.minusSeconds(3600), 9,
                "300000000001", "Contract number", activeHold);
    }

    private static String code(Throwable thrown) {
        return ((CodedRefusal) thrown).errorCode().code();
    }

    @Test
    void aHoldIsPlacedWithATrimmedReasonAndAuditedInTheSameTransactionWithoutTheReason() throws Exception {
        when(repository.lockDocument(TENANT, DOCUMENT)).thenReturn(Optional.of(facts("AVAILABLE", null)));

        var view = service.place(TENANT, "admin", "Ari Admin", DOCUMENT.toString(), "  Litigation: Ellis v. Acme  ", "corr-1");

        ArgumentCaptor<LegalHold> hold = ArgumentCaptor.forClass(LegalHold.class);
        verify(repository).insert(hold.capture());
        assertEquals("Litigation: Ellis v. Acme", hold.getValue().reason());
        assertEquals(TENANT, hold.getValue().tenantId());
        assertEquals(DOCUMENT, hold.getValue().documentId());
        assertEquals("admin", hold.getValue().placedBy());
        assertEquals("Ari Admin", hold.getValue().placedByName(), "task 0028, 0.3: the name at the time");
        assertEquals(NOW, hold.getValue().placedAt());
        assertNull(hold.getValue().releasedAt());

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        assertEquals("LEGAL_HOLD_PLACED", event.getValue().action());
        assertEquals("DOCUMENT", event.getValue().targetType());
        assertEquals(DOCUMENT.toString(), event.getValue().targetId());
        assertEquals("admin", event.getValue().actorId());
        assertEquals("Ari Admin", event.getValue().actorDisplayName());
        assertEquals(AuditOutcome.SUCCESS, event.getValue().outcome());
        assertEquals("corr-1", event.getValue().correlationId());
        JsonNode details = new ObjectMapper().readTree(event.getValue().safeDetailsJson());
        assertEquals(hold.getValue().holdId().toString(), details.get("holdId").asText());
        assertFalse(event.getValue().safeDetailsJson().contains("Ellis"), "the free-text reason is not copied into the audit");

        verify(tx).commit(txStatus);
        assertEquals("300000000001", view.primaryIdentifier());
        assertEquals(SensitivityLevel.CONFIDENTIAL, view.sensitivityLevel(), "the effective level");
        assertEquals(9, view.pageCount());
    }

    @Test
    void ifTheAuditCantBeWrittenTheHoldIsRolledBack() {
        when(repository.lockDocument(TENANT, DOCUMENT)).thenReturn(Optional.of(facts("AVAILABLE", null)));
        doThrow(new IllegalStateException("audit store down")).when(audit).append(any());
        assertThrows(IllegalStateException.class,
                () -> service.place(TENANT, "admin", null, DOCUMENT.toString(), "Litigation", "c"));
        verify(tx).rollback(txStatus);
        verify(tx, never()).commit(any());
    }

    @Test
    void reasonsMustBeThreeToFiveHundredCharacters() {
        for (String bad : new String[]{null, "", "   ", "ab", "  ab  ", "x".repeat(501)}) {
            assertEquals("SDV-HLD-002", code(assertThrows(CodedRefusal.BadRequest.class,
                    () -> service.place(TENANT, "admin", null, DOCUMENT.toString(), bad, "c"))), String.valueOf(bad));
        }
        verifyNoInteractions(repository, audit);
        when(repository.lockDocument(TENANT, DOCUMENT)).thenReturn(Optional.of(facts("AVAILABLE", null)));
        service.place(TENANT, "admin", null, DOCUMENT.toString(), "abc", "c");
        service.place(TENANT, "admin", null, DOCUMENT.toString(), "x".repeat(500), "c");
        verify(repository, times(2)).insert(any());
    }

    @Test
    void unknownDeletedAndAlreadyHeldDocumentsAreRefused() {
        for (String reference : new String[]{null, "", "not-a-uuid", "1-1-1-1-1", DOCUMENT + "x"}) {
            assertEquals("SDV-HLD-004", code(assertThrows(CodedRefusal.NotFound.class,
                    () -> service.place(TENANT, "admin", null, reference, "Litigation", "c"))), String.valueOf(reference));
        }
        when(repository.lockDocument(any(), any())).thenReturn(Optional.empty());
        assertEquals("SDV-HLD-004", code(assertThrows(CodedRefusal.NotFound.class,
                () -> service.place(OTHER_TENANT, "admin", null, DOCUMENT.toString(), "Litigation", "c"))));
        verify(repository).lockDocument(OTHER_TENANT, DOCUMENT); // looked up within the caller's own tenant only

        for (String gone : new String[]{"EXPIRED_DELETED", "RETENTION_DELETING"}) {
            when(repository.lockDocument(TENANT, DOCUMENT)).thenReturn(Optional.of(facts(gone, null)));
            assertEquals("SDV-HLD-006", code(assertThrows(CodedRefusal.Conflict.class,
                    () -> service.place(TENANT, "admin", null, DOCUMENT.toString(), "Litigation", "c"))), gone);
        }
        when(repository.lockDocument(TENANT, DOCUMENT)).thenReturn(Optional.of(facts("AVAILABLE", HOLD)));
        assertEquals("SDV-HLD-005", code(assertThrows(CodedRefusal.Conflict.class,
                () -> service.place(TENANT, "admin", null, DOCUMENT.toString(), "Litigation", "c"))));
        verify(repository, never()).insert(any());
        verifyNoInteractions(audit);
    }

    @Test
    void holdsCanBePlacedWhileADocumentIsBeingStoredOrCorrected() {
        for (String status : new String[]{"RECEIVED", "PROCESSING", "AVAILABLE", "PROCESSING_FAILED", "REDACTION_PROCESSING"}) {
            when(repository.lockDocument(TENANT, DOCUMENT)).thenReturn(Optional.of(facts(status, null)));
            assertNotNull(service.place(TENANT, "admin", null, DOCUMENT.toString(), "Litigation", "c"), status);
        }
    }

    @Test
    void aHoldIsReleasedOnceAndAudited() {
        LegalHold active = new LegalHold(HOLD, TENANT, DOCUMENT, "Litigation", "admin", "Ari Admin", NOW.minusSeconds(60), null, null, null);
        when(repository.lockHold(TENANT, HOLD)).thenReturn(Optional.of(active));
        when(repository.lockDocument(TENANT, DOCUMENT)).thenReturn(Optional.of(facts("AVAILABLE", null)));
        when(repository.release(TENANT, HOLD, "admin2", "Bo Admin", NOW)).thenReturn(true); // task 0037: a row changed

        var view = service.release(TENANT, "admin2", "Bo Admin", HOLD.toString(), "corr-r");
        verify(repository).release(TENANT, HOLD, "admin2", "Bo Admin", NOW);
        // Task 0028, 0.3: the names at the time of each action.
        assertEquals("Ari Admin", view.placedByName());
        assertEquals("Bo Admin", view.releasedByName());
        assertEquals("admin2", view.releasedBy());
        assertEquals(NOW, view.releasedAt());
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        assertEquals("LEGAL_HOLD_RELEASED", event.getValue().action());
        assertEquals(DOCUMENT.toString(), event.getValue().targetId());
        assertTrue(event.getValue().safeDetailsJson().contains(HOLD.toString()));
        assertFalse(event.getValue().safeDetailsJson().contains("Litigation"));

        when(repository.lockHold(TENANT, HOLD)).thenReturn(Optional.of(
                new LegalHold(HOLD, TENANT, DOCUMENT, "Litigation", "admin", null, NOW.minusSeconds(60), "admin2", null, NOW)));
        assertEquals("SDV-HLD-008", code(assertThrows(CodedRefusal.Conflict.class,
                () -> service.release(TENANT, "admin", null, HOLD.toString(), "c"))));
        when(repository.lockHold(OTHER_TENANT, HOLD)).thenReturn(Optional.empty());
        assertEquals("SDV-HLD-007", code(assertThrows(CodedRefusal.NotFound.class,
                () -> service.release(OTHER_TENANT, "admin", null, HOLD.toString(), "c"))));
        assertEquals("SDV-HLD-007", code(assertThrows(CodedRefusal.NotFound.class,
                () -> service.release(TENANT, "admin", null, "nope", "c"))));
        verify(repository, times(1)).release(any(), any(), any(), any(), any());
    }

    /**
     * Task 0037 (owner answer 2 to report 0036): the hold looked active, but the release changed no row because
     * someone else released it at the same moment. A refusal: 409 SDV-HLD-008 with the owner's sentence, audited as
     * LEGAL_HOLD_RELEASE_REFUSED (DENIED), and the release's own audit event is not written.
     */
    @Test
    void aHoldReleasedBySomeoneElseAtTheSameMomentIsHld008AndAudited() {
        LegalHold active = new LegalHold(HOLD, TENANT, DOCUMENT, "Litigation", "admin", "Ari Admin", NOW.minusSeconds(60), null, null, null);
        when(repository.lockHold(TENANT, HOLD)).thenReturn(Optional.of(active));
        when(repository.release(any(), any(), any(), any(), any())).thenReturn(false);

        CodedRefusal.Conflict refused = assertThrows(CodedRefusal.Conflict.class,
                () -> service.release(TENANT, "admin2", "Bo Admin", HOLD.toString(), "corr-race"));
        assertEquals("SDV-HLD-008", code(refused));
        assertEquals("This hold was already released.", refused.getMessage());
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, times(1)).append(event.capture());
        assertEquals("LEGAL_HOLD_RELEASE_REFUSED", event.getValue().action());
        assertEquals(AuditOutcome.DENIED, event.getValue().outcome());
        assertEquals("corr-race", event.getValue().correlationId());
        assertEquals(DOCUMENT.toString(), event.getValue().targetId());
        assertTrue(event.getValue().safeDetailsJson().contains("SDV-HLD-008"));
        assertFalse(event.getValue().safeDetailsJson().contains("Litigation"));
    }

    @Test
    void aRefusedReleaseStandsWhenItsAuditCantBeWritten() {
        when(repository.lockHold(TENANT, HOLD)).thenReturn(Optional.of(
                new LegalHold(HOLD, TENANT, DOCUMENT, "Litigation", "admin", null, NOW.minusSeconds(60), "admin2", null, NOW)));
        doThrow(new IllegalStateException("audit store down")).when(audit).append(any());
        assertEquals("SDV-HLD-008", code(assertThrows(CodedRefusal.Conflict.class,
                () -> service.release(TENANT, "admin", null, HOLD.toString(), "c"))));
    }

    @Test
    void theLookupNeedsAnIdentifierAndReturnsOnlyWhatAnAdminMaySee() throws Exception {
        for (String bad : new String[]{null, "", "   ", "9".repeat(301)}) {
            assertEquals("SDV-HLD-003", code(assertThrows(CodedRefusal.BadRequest.class, () -> service.find(TENANT, bad))));
        }
        when(repository.findByPrimaryIdentifier(TENANT, "300000000001", 100)).thenReturn(List.of(facts("AVAILABLE", HOLD)));
        var found = service.find(TENANT, " 300000000001 ");
        assertEquals(1, found.size());
        assertTrue(found.getFirst().onHold());
        assertEquals(SensitivityLevel.CONFIDENTIAL, found.getFirst().sensitivityLevel());
        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(found);
        assertFalse(json.contains("status"), "the document's state is for the server's checks only");
        assertFalse(json.contains("submissionId") || json.contains("sourceId"), json);
    }
}
