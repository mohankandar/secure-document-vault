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

        @Override
        public boolean isApprovedPrint(UUID tenantId, String actorId, UUID documentId, UUID printId, Instant notBefore) {
            assertEquals(NOW.minus(Duration.ofMinutes(15)), notBefore);
            return documentId.equals(DOC) && approvedPrints.contains(actorId + printId);
        }

        @Override
        public boolean appendPreviewEventOnce(AuditEvent event, UUID viewingId, Instant notBefore) {
            boolean exists = events.stream().anyMatch(e -> e.action().equals("DOCUMENT_PREVIEWED")
                    && e.safeDetailsJson().contains("\"viewingId\":\"" + viewingId + "\""));
            if (!exists) events.add(event);
            return !exists;
        }
    };

    /** Task 0071: person and print of the approved prints. */
    private final java.util.Set<String> approvedPrints = new java.util.HashSet<>();

    private final DocumentIntakeRepository documents = mock(DocumentIntakeRepository.class);
    private final StorageVolumeRepository volumes = mock(StorageVolumeRepository.class);
    private final StorageProvider provider = mock(StorageProvider.class);
    private final StreamingCryptoPort crypto = mock(StreamingCryptoPort.class);
    private final com.sdv.platform.application.access.DocumentAccessGuard guard =
            mock(com.sdv.platform.application.access.DocumentAccessGuard.class);
    private final DocumentViewerService service = new DocumentViewerService(
            documents, volumes, List.of(provider), crypto, audit, pageViews, guard, com.sdv.platform.application.pdf.PdfWork.defaults(), (t, s) -> 2000, Clock.fixed(NOW, ZoneOffset.UTC));

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
        }).when(crypto).decrypt(org.mockito.ArgumentMatchers.argThat(b -> b != null && b.entityId().equals(TENANT)), any(), any(), any());
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
        service.renderPage(TENANT, DOC, ALL, 2, 1.0, viewing, "steward", "c");
        assertEquals(3, events.size());
        for (var event : events) {
            assertTrue(event.safeDetailsJson().contains("\"sensitivityLevel\":\"HIGHLY_CONFIDENTIAL\""),
                    event.action() + " " + event.safeDetailsJson());
        }
    }

    private void level(SensitivityLevel level) {
        when(guard.requireOpenable(any(), any(), any(), any(), any())).thenReturn(level);
    }

    /** The page of an opening: width, and whether anything is drawn on the empty page. */
    private record Drawn(int width, boolean marked) { }

    private Drawn page(UUID viewing, double scale, UUID printId) throws Exception {
        byte[] png = service.renderPage(TENANT, DOC, ALL, 1, scale, viewing, printId, "Stella Steward", "steward", "c").pngBytes();
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new ByteArrayInputStream(png));
        for (int x = 0; x < image.getWidth(); x++) for (int y = 0; y < image.getHeight(); y++) if ((image.getRGB(x, y) & 0xffffff) != 0xffffff) return new Drawn(image.getWidth(), true);
        return new Drawn(image.getWidth(), false);
    }

    /** Task 0071: Highly Confidential: screen quality on screen (marked when more was asked), print quality only for an approved print. */
    @Test
    void highlyConfidentialPagesInPrintQualityNeedAnApprovedPrintAndCarryAMark() throws Exception {
        level(SensitivityLevel.HIGHLY_CONFIDENTIAL);
        UUID viewing = open(DOC, "steward");
        Drawn screen = page(viewing, 1.5, null);
        assertFalse(screen.marked());
        assertEquals(new Drawn(screen.width(), true), page(viewing, 2.0, null), "no larger than on screen, and marked");
        UUID print = UUID.randomUUID();
        for (UUID none : new UUID[] {print, UUID.randomUUID()}) {
            var ex = assertThrows(com.sdv.platform.domain.error.CodedRefusal.Conflict.class, () -> page(viewing, 2.0, none));
            assertEquals("SDV-DOC-014", ex.errorCode().code());
        }
        approvedPrints.add("someone-else" + print);
        assertThrows(com.sdv.platform.domain.error.CodedRefusal.Conflict.class, () -> page(viewing, 2.0, print));
        approvedPrints.add("steward" + print);
        Drawn printed = page(viewing, 2.0, print);
        assertTrue(printed.width() > screen.width() && printed.marked());
        assertTrue(page(viewing, 1.0, print).marked(), "every page of a print");
    }

    @Test
    void otherLevelsAreDrawnAsAskedAndUnmarked() throws Exception {
        for (SensitivityLevel l : List.of(SensitivityLevel.STANDARD, SensitivityLevel.CONFIDENTIAL)) {
            level(l);
            UUID viewing = open(DOC, "steward");
            Drawn large = page(viewing, 2.0, null), small = page(viewing, 1.0, null);
            assertEquals(2.0, large.width() / (double) small.width(), 0.01);
            assertFalse(large.marked() || small.marked());
        }
    }

    /** Letters are one em of 1000 and don't run into each other, whatever font stands in. */
    @Test
    void theMarksLettersAreReadable() throws Exception {
        var box = PageWatermark.outline("HH").getBounds2D();
        assertTrue(box.getHeight() > 600 && box.getHeight() < 850, box.toString());
        assertTrue(box.getWidth() > 1150 && box.getWidth() < 1444, box.toString());
    }

    @Test
    void theMarkNamesThePersonAndTheTime() {
        assertEquals("Stella Steward  2026-11-18 12:00 UTC", PageWatermark.text("Stella Steward", "s1", NOW));
        assertEquals("s1  2026-11-18 12:00 UTC", PageWatermark.text(" ", "s1", NOW));
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

    private void refused(Runnable read) {
        var ex = org.junit.jupiter.api.Assertions.assertThrows(com.sdv.platform.domain.error.CodedRefusal.Conflict.class, read::run);
        assertEquals("SDV-DOC-011", ex.errorCode().code());
        assertEquals(0, pageEvents(), "no page was handed out");
    }

    /** Task 0063: pages, previews and search need an opening of this document by this person (before, a page without one was recorded normally). */
    @Test
    void withoutAnOpeningOfTheirOwnNothingIsRead() {
        UUID someoneElses = open(DOC, "someone-else");
        UUID otherDocument = open(OTHER_DOC, "steward");
        UUID old = UUID.randomUUID();
        events.add(new AuditEvent(old, TENANT, "USER", "steward", "DOCUMENT_VIEW_OPENED", "DOCUMENT", DOC.toString(),
                NOW.minus(Duration.ofHours(8)).minusSeconds(1), "c", com.sdv.platform.domain.audit.AuditOutcome.SUCCESS, "{}"));
        for (UUID viewing : new UUID[] {null, UUID.randomUUID(), someoneElses, otherDocument, old}) {
            refused(() -> service.renderPage(TENANT, DOC, ALL, 1, 1.0, viewing, "steward", "c"));
            refused(() -> service.previews(TENANT, DOC, 1, 1, viewing, ALL, "steward", "c"));
            refused(() -> service.search(TENANT, DOC, "x", viewing, ALL, "steward", "c"));
        }
        assertEquals(Duration.ofHours(8), DocumentViewerService.VIEWING_LIFETIME);
    }

    @Test
    void previewsAreRecordedOncePerViewingAndASearchNamesItsViewing() {
        UUID viewing = open(DOC, "steward");
        service.previews(TENANT, DOC, 1, 2, viewing, ALL, "steward", "c");
        service.previews(TENANT, DOC, 2, 2, viewing, ALL, "steward", "c");
        assertEquals(1, events.stream().filter(e -> e.action().equals("DOCUMENT_PREVIEWED")).count());
        service.search(TENANT, DOC, "x", viewing, ALL, "steward", "c");
        assertEquals("DOCUMENT_TEXT_SEARCHED", events.getLast().action());
        assertTrue(events.getLast().safeDetailsJson().contains("\"viewingId\":\"" + viewing + "\""));
        UUID again = open(DOC, "steward");
        service.previews(TENANT, DOC, 1, 2, again, ALL, "steward", "c");
        assertEquals(2, events.stream().filter(e -> e.action().equals("DOCUMENT_PREVIEWED")).count());
    }
}
