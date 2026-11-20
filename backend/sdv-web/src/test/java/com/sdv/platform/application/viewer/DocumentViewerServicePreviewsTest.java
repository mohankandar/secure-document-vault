package com.sdv.platform.application.viewer;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.application.redaction.PdfSanitizingRedactor;
import com.sdv.platform.application.redaction.RedactionRegion;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.document.DocumentCryptoMetadata;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.DocumentStorageRef;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.storage.StorageVolumeState;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Viewer page previews: same checks as the viewer, one decryption, no audit, built from the current (redacted) PDF. */
class DocumentViewerServicePreviewsTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DOC = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID VOLUME = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final byte[] CIPHERTEXT = "not-really-encrypted".getBytes();

    private final DocumentIntakeRepository documents = mock(DocumentIntakeRepository.class);
    private final StorageVolumeRepository volumes = mock(StorageVolumeRepository.class);
    private final StorageProvider provider = mock(StorageProvider.class);
    private final StreamingCryptoPort crypto = mock(StreamingCryptoPort.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final DocumentViewerService service =
            new DocumentViewerService(documents, volumes, List.of(provider), crypto, audit, mock(com.sdv.platform.application.port.PageViewAuditPort.class),
                    mock(com.sdv.platform.application.access.DocumentAccessGuard.class));

    /** A grey page per page number; page content does not matter except for the redaction test. */
    private static byte[] pdf(int pages) throws Exception {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    content.setNonStrokingColor(new Color(200, 200, 200));
                    content.addRect(0, 0, page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                    content.fill();
                }
            }
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private void stored(byte[] plaintext, DocumentStatus status) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plaintext));
        String cipherSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CIPHERTEXT));
        Document doc = new Document(DOC, TENANT, UUID.randomUUID(), UUID.randomUUID(), "src-doc", "idem", "a.pdf",
                "application/pdf", sha, (long) plaintext.length, status, 30, Instant.now(), null, Instant.now(), 0, null, null, SensitivityLevel.STANDARD);
        when(documents.findDocumentByIdForTenant(TENANT, DOC)).thenReturn(Optional.of(doc));
        when(documents.findDocumentByIdForTenant(eq(OTHER_TENANT), any())).thenReturn(Optional.empty());
        when(documents.findStorageRef(TENANT, DOC)).thenReturn(Optional.of(
                new DocumentStorageRef(DOC, TENANT, "LOCAL_FILESYSTEM", VOLUME, "obj", CIPHERTEXT.length, cipherSha)));
        when(documents.findCryptoMetadata(TENANT, DOC)).thenReturn(Optional.of(
                new DocumentCryptoMetadata(DOC, TENANT, "SDV-ENC-001", 1, "vault", "ref", "1")));
        when(volumes.findById(TENANT, VOLUME)).thenReturn(Optional.of(new StorageVolume(VOLUME, TENANT, "LOCAL_FILESYSTEM",
                "v", "/tmp", StorageVolumeState.ACTIVE, 1, 80, 95, Instant.now(), Instant.now())));
        when(provider.providerId()).thenReturn("LOCAL_FILESYSTEM");
        when(provider.open(eq(TENANT), any(), eq("obj"))).thenAnswer(inv -> new ByteArrayInputStream(CIPHERTEXT));
        doAnswer(inv -> {
            InputStream in = inv.getArgument(2);
            in.readAllBytes();
            OutputStream out = inv.getArgument(3);
            out.write(plaintext);
            return null;
        }).when(crypto).decrypt(eq(TENANT), any(), any(), any());
    }

    private static BufferedImage image(DocumentViewerService.PagePreview preview) throws Exception {
        String data = preview.dataUrl().substring("data:image/png;base64,".length());
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(data)));
    }

    @Test
    void returnsSmallPngPreviewsForTheRequestedPagesDecryptingOnceAndWritingNoAuditEvent() throws Exception {
        stored(pdf(5), DocumentStatus.AVAILABLE);
        var result = service.previews(TENANT, DOC, 2, 12, com.sdv.platform.application.access.Clearance.ALL, "steward", "c");
        assertEquals(5, result.pageCount());
        assertEquals(List.of(2, 3, 4, 5), result.previews().stream().map(DocumentViewerService.PagePreview::pageNumber).toList());
        for (var preview : result.previews()) {
            assertTrue(preview.dataUrl().startsWith("data:image/png;base64,"));
            assertEquals(DocumentViewerService.PREVIEW_WIDTH_PX, preview.width(), 1);
            assertEquals(preview.width(), image(preview).getWidth());
        }
        verify(crypto, times(1)).decrypt(eq(TENANT), any(), any(), any());
        verifyNoInteractions(audit);
    }

    @Test
    void anotherTenantsDocumentIsRefused() throws Exception {
        stored(pdf(2), DocumentStatus.AVAILABLE);
        assertThrows(NoSuchElementException.class, () -> service.previews(OTHER_TENANT, DOC, 1, 12, com.sdv.platform.application.access.Clearance.ALL, "steward", "c"));
        verifyNoInteractions(crypto);
    }

    @Test
    void aDocumentThatIsNotAvailableIsRefused() throws Exception {
        stored(pdf(2), DocumentStatus.RETENTION_DELETING);
        assertThrows(IllegalStateException.class, () -> service.previews(TENANT, DOC, 1, 12, com.sdv.platform.application.access.Clearance.ALL, "steward", "c"));
        verifyNoInteractions(crypto);
    }

    @Test
    void moreThan24PagesOrAnInvalidRangeIsRefused() throws Exception {
        stored(pdf(2), DocumentStatus.AVAILABLE);
        assertThrows(IllegalArgumentException.class, () -> service.previews(TENANT, DOC, 1, 25, com.sdv.platform.application.access.Clearance.ALL, "steward", "c"));
        assertThrows(IllegalArgumentException.class, () -> service.previews(TENANT, DOC, 0, 12, com.sdv.platform.application.access.Clearance.ALL, "steward", "c"));
        assertThrows(IllegalArgumentException.class, () -> service.previews(TENANT, DOC, 3, 12, com.sdv.platform.application.access.Clearance.ALL, "steward", "c"));
        assertDoesNotThrow(() -> service.previews(TENANT, DOC, 1, 24, com.sdv.platform.application.access.Clearance.ALL, "steward", "c"));
    }

    @Test
    void previewsOfARedactedDocumentComeFromTheRedactedPdf() throws Exception {
        byte[] original = pdf(1);
        // Permanent redaction replaces the stored PDF; the previews read whatever is stored now.
        byte[] redacted = new PdfSanitizingRedactor().sanitize(original,
                List.of(new RedactionRegion(1, 0.25, 0.25, 0.5, 0.5)), 50_000_000L);
        stored(redacted, DocumentStatus.AVAILABLE);
        BufferedImage preview = image(service.previews(TENANT, DOC, 1, 1, com.sdv.platform.application.access.Clearance.ALL, "steward", "c").previews().getFirst());
        Color centre = new Color(preview.getRGB(preview.getWidth() / 2, preview.getHeight() / 2));
        Color corner = new Color(preview.getRGB(3, 3));
        assertTrue(centre.getRed() < 40 && centre.getGreen() < 40 && centre.getBlue() < 40, "redacted area is black: " + centre);
        assertTrue(corner.getRed() > 150, "unredacted area keeps its content: " + corner);
    }
}
