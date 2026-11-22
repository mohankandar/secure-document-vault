package com.sdv.platform.application.viewer;

import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.PageViewAuditPort;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentCryptoMetadata;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.DocumentStorageRef;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.storage.StorageVolumeState;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Task 0043, item 1: the viewer's failures each carry their own code (they were one VIEWER_UNAVAILABLE): SDV-DOC-001
 * and -002 for the document itself, -003 when the stored copy can't be read (metadata, volume, provider, storage, key
 * service), -004 when it fails a check or is too large, -005 when it can't be drawn, -006 for a request that isn't
 * valid. The log detail names the step; the message is the catalog's sentence.
 */
class DocumentViewerCodesTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DOC = UUID.fromString("dddddddd-0043-0000-0000-dddddddddddd");
    private static final UUID VOLUME = UUID.fromString("eeeeeeee-0043-0000-0000-eeeeeeeeeeee");
    private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
    private static final byte[] CIPHERTEXT = "ciphertext".getBytes(StandardCharsets.UTF_8);

    private final DocumentIntakeRepository documents = mock(DocumentIntakeRepository.class);
    private final StorageVolumeRepository volumes = mock(StorageVolumeRepository.class);
    private final StorageProvider provider = mock(StorageProvider.class);
    private final StreamingCryptoPort crypto = mock(StreamingCryptoPort.class);
    private final DocumentAccessGuard guard = mock(DocumentAccessGuard.class);
    private final PageViewAuditPort pageViews = mock(PageViewAuditPort.class);
    private final DocumentViewerService viewer = new DocumentViewerService(documents, volumes, List.of(provider), crypto,
            e -> { }, pageViews, guard, Clock.fixed(NOW, ZoneOffset.UTC));
    private byte[] plaintext;

    @BeforeEach
    void setUp() throws Exception {
        when(guard.requireOpenable(any(), any(), any(), any(), any())).thenReturn(SensitivityLevel.STANDARD);
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            pdf.addPage(new PDPage(PDRectangle.LETTER));
            pdf.save(out);
            plaintext = out.toByteArray();
        }
        document(sha(plaintext), plaintext.length, DocumentStatus.AVAILABLE);
        when(documents.findStorageRef(TENANT, DOC)).thenReturn(Optional.of(
                new DocumentStorageRef(DOC, TENANT, "LOCAL_FILESYSTEM", VOLUME, "obj", CIPHERTEXT.length, sha(CIPHERTEXT))));
        when(documents.findCryptoMetadata(TENANT, DOC)).thenReturn(Optional.of(
                new DocumentCryptoMetadata(DOC, TENANT, "SDV-ENC-001", 1, "vault", "ref", "1")));
        when(volumes.findById(TENANT, VOLUME)).thenReturn(Optional.of(new StorageVolume(VOLUME, TENANT, "LOCAL_FILESYSTEM",
                "v", "/tmp", StorageVolumeState.ACTIVE, 1, 80, 95, NOW, NOW)));
        when(provider.providerId()).thenReturn("LOCAL_FILESYSTEM");
        when(provider.open(eq(TENANT), any(), eq("obj"))).thenAnswer(i -> new java.io.ByteArrayInputStream(CIPHERTEXT));
        decryptsTo(plaintext);
    }

    private void document(String sha, long size, DocumentStatus status) {
        when(documents.findDocumentByIdForTenant(TENANT, DOC)).thenReturn(Optional.of(new Document(DOC, TENANT,
                UUID.randomUUID(), UUID.randomUUID(), "src", "idem", "a.pdf", "application/pdf", sha, size, status, 30,
                NOW, null, NOW, 0, null, null, SensitivityLevel.STANDARD)));
    }

    private void decryptsTo(byte[] bytes) throws IOException {
        doAnswer(i -> {
            ((InputStream) i.getArgument(2)).readAllBytes();
            ((OutputStream) i.getArgument(3)).write(bytes);
            return null;
        }).when(crypto).decrypt(eq(TENANT), any(), any(), any());
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private void open() {
        viewer.metadata(TENANT, DOC, Clearance.ALL, "steward", "c");
    }

    private static DocumentViewerUnavailableException fault(String code, Executable call) {
        DocumentViewerUnavailableException ex = assertThrows(DocumentViewerUnavailableException.class, call);
        assertEquals(code, ex.errorCode().code(), ex.logDetail());
        assertTrue(ex.logDetail().contains("document=" + DOC), ex.logDetail());
        return ex;
    }

    private static String refusal(Executable call) {
        Throwable thrown = assertThrows(RuntimeException.class, call);
        assertInstanceOf(CodedRefusal.class, thrown, thrown.toString());
        return ((CodedRefusal) thrown).errorCode().code();
    }

    @Test
    void theStoredCopyThatCantBeReadIsDoc003WithTheStep() throws Exception {
        when(provider.open(eq(TENANT), any(), eq("obj"))).thenThrow(new IOException("volume offline"));
        assertTrue(fault("SDV-DOC-003", this::open).logDetail().contains("storage could not be opened"));

        when(provider.open(eq(TENANT), any(), eq("obj"))).thenAnswer(i -> new FilterInputStream(
                new java.io.ByteArrayInputStream(CIPHERTEXT)) {
            @Override public int read(byte[] b, int off, int len) throws IOException { throw new IOException("bad sector"); }
        });
        DocumentViewerUnavailableException read = fault("SDV-DOC-003", this::open);
        assertTrue(read.logDetail().contains("storage read failed"), read.logDetail());
        assertEquals("Vero couldn't read this document from storage. Try again later.", read.getMessage());

        when(provider.open(eq(TENANT), any(), eq("obj"))).thenAnswer(i -> new java.io.ByteArrayInputStream(CIPHERTEXT));
        doThrow(new IllegalStateException("key service unreachable")).when(crypto).decrypt(eq(TENANT), any(), any(), any());
        assertTrue(fault("SDV-DOC-003", this::open).logDetail().contains("key service"));

        when(documents.findStorageRef(TENANT, DOC)).thenReturn(Optional.empty());
        assertTrue(fault("SDV-DOC-003", this::open).logDetail().contains("storage metadata missing"));
    }

    @Test
    void aStoredCopyThatFailsItsCheckIsDoc004WithWhichCheck() throws Exception {
        document("0".repeat(64), plaintext.length, DocumentStatus.AVAILABLE);
        DocumentViewerUnavailableException fingerprint = fault("SDV-DOC-004", this::open);
        assertTrue(fingerprint.logDetail().contains("decrypted fingerprint does not match"), fingerprint.logDetail());
        assertEquals("This document's stored copy didn't pass Vero's check, so it isn't shown.", fingerprint.getMessage());

        document(sha(plaintext), plaintext.length, DocumentStatus.AVAILABLE);
        when(documents.findStorageRef(TENANT, DOC)).thenReturn(Optional.of(
                new DocumentStorageRef(DOC, TENANT, "LOCAL_FILESYSTEM", VOLUME, "obj", CIPHERTEXT.length, "f".repeat(64))));
        assertTrue(fault("SDV-DOC-004", this::open).logDetail().contains("encrypted fingerprint no longer matches"));

        when(documents.findStorageRef(TENANT, DOC)).thenReturn(Optional.of(
                new DocumentStorageRef(DOC, TENANT, "LOCAL_FILESYSTEM", VOLUME, "obj", CIPHERTEXT.length, sha(CIPHERTEXT))));
        doThrow(new IOException("chunk authentication failed")).when(crypto).decrypt(eq(TENANT), any(), any(), any());
        assertTrue(fault("SDV-DOC-004", this::open).logDetail().contains("decryption check failed"));

        document(sha(plaintext), Integer.MAX_VALUE, DocumentStatus.AVAILABLE);
        assertEquals("This document is too large to view here.", fault("SDV-DOC-004", this::open).getMessage());
    }

    @Test
    void aFileThatPassesItsChecksButCantBeDrawnIsDoc005() throws Exception {
        byte[] notAPdf = "%PDF-not really a pdf".getBytes(StandardCharsets.US_ASCII);
        document(sha(notAPdf), notAPdf.length, DocumentStatus.AVAILABLE);
        decryptsTo(notAPdf);
        DocumentViewerUnavailableException drawn = fault("SDV-DOC-005", this::open);
        assertTrue(drawn.logDetail().contains("at=open"), drawn.logDetail());
        assertEquals("SDV-DOC-005", fault("SDV-DOC-005",
                () -> viewer.renderPage(TENANT, DOC, Clearance.ALL, 1, 1.0, "s", "c")).errorCode().code());
    }

    @Test
    void aRequestThatIsntValidIsDoc006() {
        assertEquals("SDV-DOC-006", refusal(() -> viewer.renderPage(TENANT, DOC, Clearance.ALL, 0, 1.0, "s", "c")));
        assertEquals("SDV-DOC-006", refusal(() -> viewer.renderPage(TENANT, DOC, Clearance.ALL, 1, 9.0, "s", "c")));
        assertEquals("SDV-DOC-006", refusal(() -> viewer.renderPage(TENANT, DOC, Clearance.ALL, 2, 1.0, "s", "c")),
                "past the last page");
        assertEquals("SDV-DOC-006", refusal(() -> viewer.previews(TENANT, DOC, 1, 0, Clearance.ALL, "s", "c")));
        assertEquals("SDV-DOC-006", refusal(() -> viewer.search(TENANT, DOC, " ", Clearance.ALL, "s", "c")));
    }

    @Test
    void aMissingOrUnavailableDocumentIsDoc001OrDoc002() {
        assertEquals("SDV-DOC-001", refusal(() -> viewer.metadata(TENANT, UUID.randomUUID(), Clearance.ALL, "s", "c")));
        document("x", 10, DocumentStatus.PROCESSING_FAILED);
        assertEquals("SDV-DOC-002", refusal(this::open));
    }
}
