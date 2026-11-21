package com.sdv.platform.application.viewer;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.PageViewAuditPort;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.document.DocumentCryptoMetadata;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.DocumentStorageRef;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.storage.StorageVolumeState;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0007: one page event per page per viewing, whatever the zoom; the server decides which viewing counts.
 * The port is an in-memory implementation of the PageViewAuditPort contract (the SQL is covered by
 * JdbcPageViewAuditRepositorySqlTest and the live check in report 0007).
 */
class DocumentViewerServicePageViewTest {
    private static final com.sdv.platform.application.access.Clearance ALL = com.sdv.platform.application.access.Clearance.ALL;
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOC = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID OTHER_DOC = UUID.fromString("dddddddd-0000-0000-0000-dddddddddddd");
    private static final UUID VOLUME = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
    private static final byte[] CIPHERTEXT = "ciphertext".getBytes();

    /** Holds every event written, whether through the normal audit port or the page-once port. */
    private final List<AuditEvent> events = new ArrayList<>();
    private final AuditEventPort audit = events::add;
    private final PageViewAuditPort pageViews = new PageViewAuditPort() {
        @Override
        public boolean isViewing(UUID tenantId, String actorId, UUID documentId, UUID viewingId, Instant notBefore) {
            return events.stream().anyMatch(e -> e.auditEventId().equals(viewingId) && e.tenantId().equals(tenantId)
                    && e.actorId().equals(actorId) && e.action().equals("DOCUMENT_VIEW_OPENED")
                    && e.targetId().equals(documentId.toString()) && !e.occurredAt().isBefore(notBefore));
        }

        @Override
        public boolean appendPageEventOnce(AuditEvent pageEvent, UUID viewingId, int pageNumber, Instant notBefore) {
            boolean exists = events.stream().anyMatch(e -> e.action().equals("DOCUMENT_PAGE_VIEWED")
                    && e.tenantId().equals(pageEvent.tenantId()) && e.actorId().equals(pageEvent.actorId())
                    && e.targetId().equals(pageEvent.targetId()) && !e.occurredAt().isBefore(notBefore)
                    && e.safeDetailsJson().contains("\"viewingId\":\"" + viewingId + "\"")
                    && e.safeDetailsJson().contains("\"page\":" + pageNumber + ","));
            if (!exists) events.add(pageEvent);
            return !exists;
        }
    };

    private final DocumentIntakeRepository documents = mock(DocumentIntakeRepository.class);
    private final StorageVolumeRepository volumes = mock(StorageVolumeRepository.class);
    private final StorageProvider provider = mock(StorageProvider.class);
    private final StreamingCryptoPort crypto = mock(StreamingCryptoPort.class);
    private final com.sdv.platform.application.access.DocumentAccessGuard guard =
            mock(com.sdv.platform.application.access.DocumentAccessGuard.class);
    private final DocumentViewerService service = new DocumentViewerService(
            documents, volumes, List.of(provider), crypto, audit, pageViews, guard, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp() throws Exception {
        org.mockito.Mockito.when(guard.requireOpenable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.sdv.platform.domain.document.SensitivityLevel.STANDARD);
        byte[] plaintext;
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < 3; i++) pdf.addPage(new PDPage(PDRectangle.LETTER));
            pdf.save(out);
            plaintext = out.toByteArray();
        }
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plaintext));
        String cipherSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CIPHERTEXT));
        for (UUID id : List.of(DOC, OTHER_DOC)) {
            when(documents.findDocumentByIdForTenant(TENANT, id)).thenReturn(Optional.of(new Document(id, TENANT, UUID.randomUUID(),
                    UUID.randomUUID(), "src", "idem", "a.pdf", "application/pdf", sha, (long) plaintext.length,
                    DocumentStatus.AVAILABLE, 30, NOW, null, NOW, 0, null, null, SensitivityLevel.STANDARD)));
            when(documents.findStorageRef(TENANT, id)).thenReturn(Optional.of(
                    new DocumentStorageRef(id, TENANT, "LOCAL_FILESYSTEM", VOLUME, "obj", CIPHERTEXT.length, cipherSha)));
            when(documents.findCryptoMetadata(TENANT, id)).thenReturn(Optional.of(
                    new DocumentCryptoMetadata(id, TENANT, "SDV-ENC-001", 1, "vault", "ref", "1")));
        }
        when(volumes.findById(TENANT, VOLUME)).thenReturn(Optional.of(new StorageVolume(VOLUME, TENANT, "LOCAL_FILESYSTEM",
                "v", "/tmp", StorageVolumeState.ACTIVE, 1, 80, 95, NOW, NOW)));
        when(provider.providerId()).thenReturn("LOCAL_FILESYSTEM");
        when(provider.open(eq(TENANT), any(), eq("obj"))).thenAnswer(inv -> new ByteArrayInputStream(CIPHERTEXT));
        final byte[] pdfBytes = plaintext;
        doAnswer(inv -> {
            ((InputStream) inv.getArgument(2)).readAllBytes();
            ((OutputStream) inv.getArgument(3)).write(pdfBytes);
            return null;
        }).when(crypto).decrypt(eq(TENANT), any(), any(), any());
    }

    private long pageEvents() {
        return events.stream().filter(e -> e.action().equals("DOCUMENT_PAGE_VIEWED")).count();
    }

    private UUID open(UUID documentId, String actor) {
        return service.metadata(TENANT, documentId, ALL, actor, "c").viewingId();
    }

    /** Task 0031 (0.1): the effective level the guard worked out at the time of viewing is in both events. */
    @Test
    void openingsAndPageViewsRecordTheEffectiveLevelAtTheTimeOfViewing() {
        org.mockito.Mockito.when(guard.requireOpenable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.sdv.platform.domain.document.SensitivityLevel.HIGHLY_CONFIDENTIAL);
        UUID viewing = open(DOC, "steward");
        service.renderPage(TENANT, DOC, ALL, 1, 1.0, viewing, "steward", "c");
        service.renderPage(TENANT, DOC, ALL, 2, 1.0, null, "steward", "c");
        assertEquals(3, events.size());
        for (var event : events) {
            assertTrue(event.safeDetailsJson().contains("\"sensitivityLevel\":\"HIGHLY_CONFIDENTIAL\""),
                    event.action() + " " + event.safeDetailsJson());
        }
    }

    @Test
    void openingReturnsTheOpenEventIdAsViewingId() {
        UUID viewing = open(DOC, "steward");
        assertEquals(1, events.size());
        assertEquals("DOCUMENT_VIEW_OPENED", events.getFirst().action());
        assertEquals(events.getFirst().auditEventId(), viewing);
    }

    @Test
    void samePageTwiceInOneViewingIsRecordedOnce() {
        UUID viewing = open(DOC, "steward");
        service.renderPage(TENANT, DOC, ALL, 2, 1.0, viewing, "steward", "c");
        service.renderPage(TENANT, DOC, ALL, 2, 1.0, viewing, "steward", "c");
        assertEquals(1, pageEvents());
        String details = events.getLast().safeDetailsJson();
        assertTrue(details.contains("\"viewingId\":\"" + viewing + "\"") && details.contains("\"page\":2"), details);
    }

    @Test
    void anotherZoomOfTheSamePageIsStillOneRecordButAnotherPageIsRecorded() {
        UUID viewing = open(DOC, "steward");
        service.renderPage(TENANT, DOC, ALL, 1, 0.93, viewing, "steward", "c");
        service.renderPage(TENANT, DOC, ALL, 1, 1.25, viewing, "steward", "c");
        service.renderPage(TENANT, DOC, ALL, 1, 1.50, viewing, "steward", "c");
        service.renderPage(TENANT, DOC, ALL, 2, 1.50, viewing, "steward", "c");
        assertEquals(2, pageEvents());
    }

    @Test
    void anotherPersonsViewingIdIsRecordedNormally() {
        UUID someoneElses = open(DOC, "someone-else");
        service.renderPage(TENANT, DOC, ALL, 1, 1.0, someoneElses, "steward", "c");
        service.renderPage(TENANT, DOC, ALL, 1, 1.0, someoneElses, "steward", "c");
        assertEquals(2, pageEvents(), "a viewing that is not this person's never suppresses a record");
        assertFalse(events.getLast().safeDetailsJson().contains("viewingId"));
    }

    @Test
    void anotherDocumentsViewingIdIsRecordedNormally() {
        UUID otherDocViewing = open(OTHER_DOC, "steward");
        service.renderPage(TENANT, DOC, ALL, 1, 1.0, otherDocViewing, "steward", "c");
        service.renderPage(TENANT, DOC, ALL, 1, 1.0, otherDocViewing, "steward", "c");
        assertEquals(2, pageEvents());
    }

    @Test
    void noViewingIdIsRecordedNormally() {
        open(DOC, "steward");
        service.renderPage(TENANT, DOC, ALL, 1, 1.0, null, "steward", "c");
        service.renderPage(TENANT, DOC, ALL, 1, 1.0, null, "steward", "c");
        assertEquals(2, pageEvents());
    }

    @Test
    void aViewingOlderThanEightHoursIsRecordedNormally() {
        UUID old = UUID.randomUUID();
        events.add(new AuditEvent(old, TENANT, "USER", "steward", "DOCUMENT_VIEW_OPENED", "DOCUMENT", DOC.toString(),
                NOW.minus(Duration.ofHours(8)).minusSeconds(1), "c", com.sdv.platform.domain.audit.AuditOutcome.SUCCESS, "{}"));
        service.renderPage(TENANT, DOC, ALL, 1, 1.0, old, "steward", "c");
        service.renderPage(TENANT, DOC, ALL, 1, 1.0, old, "steward", "c");
        assertEquals(2, pageEvents());
        assertEquals(Duration.ofHours(8), DocumentViewerService.VIEWING_LIFETIME);
    }
}
