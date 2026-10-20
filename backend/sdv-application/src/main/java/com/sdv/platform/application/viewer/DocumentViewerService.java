package com.sdv.platform.application.viewer;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentCryptoMetadata;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.DocumentStorageRef;
import com.sdv.platform.domain.storage.StorageVolume;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class DocumentViewerService {
    private static final double MIN_SCALE = 0.50d;
    private static final double MAX_SCALE = 2.50d;
    private static final float BASE_DPI = 96.0f;
    private static final double MAX_RENDER_PIXELS = 24_000_000d;
    private static final int MAX_SEARCH_QUERY_LENGTH = 100;

    private final DocumentIntakeRepository documents;
    private final StorageVolumeRepository volumes;
    private final List<StorageProvider> storageProviders;
    private final StreamingCryptoPort crypto;
    private final AuditEventPort audit;
    private final Clock clock = Clock.systemUTC();

    public DocumentViewerService(
            DocumentIntakeRepository documents,
            StorageVolumeRepository volumes,
            List<StorageProvider> storageProviders,
            StreamingCryptoPort crypto,
            AuditEventPort audit) {
        this.documents = documents;
        this.volumes = volumes;
        this.storageProviders = List.copyOf(storageProviders);
        this.crypto = crypto;
        this.audit = audit;
    }

    public ViewerMetadata metadata(
            UUID tenantId,
            UUID documentId,
            String actorId,
            String correlationId) {
        Document document = requireAvailableDocument(tenantId, documentId);
        byte[] plaintext = verifiedPlaintext(document);
        try (PDDocument pdf = load(plaintext)) {
            ViewerMetadata metadata = new ViewerMetadata(
                    document.documentId(),
                    document.originalFilename(),
                    pdf.getNumberOfPages());
            appendAudit(
                    tenantId,
                    actorId,
                    "DOCUMENT_VIEW_OPENED",
                    documentId,
                    correlationId,
                    "{\"pageCount\":" + metadata.pageCount() + "}");
            return metadata;
        } catch (IOException ex) {
            throw unavailable(ex);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public PageImage renderPage(
            UUID tenantId,
            UUID documentId,
            int pageNumber,
            double scale,
            String actorId,
            String correlationId) {
        if (pageNumber < 1) throw new IllegalArgumentException("page must be at least 1.");
        if (!Double.isFinite(scale) || scale < MIN_SCALE || scale > MAX_SCALE) {
            throw new IllegalArgumentException(
                    "scale must be between " + MIN_SCALE + " and " + MAX_SCALE + ".");
        }

        Document document = requireAvailableDocument(tenantId, documentId);
        byte[] plaintext = verifiedPlaintext(document);
        try (PDDocument pdf = load(plaintext)) {
            if (pageNumber > pdf.getNumberOfPages()) {
                throw new IllegalArgumentException(
                        "page exceeds the document page count of " + pdf.getNumberOfPages() + ".");
            }

            PDPage page = pdf.getPage(pageNumber - 1);
            PDRectangle box = page.getCropBox();
            float dpi = (float) (BASE_DPI * scale);
            double widthPixels = (box.getWidth() / 72.0d) * dpi;
            double heightPixels = (box.getHeight() / 72.0d) * dpi;
            if (widthPixels <= 0 || heightPixels <= 0
                    || widthPixels * heightPixels > MAX_RENDER_PIXELS) {
                throw new IllegalArgumentException(
                        "Requested viewer zoom exceeds the safe rendering dimensions for this page.");
            }

            PDFRenderer renderer = new PDFRenderer(pdf);
            renderer.setSubsamplingAllowed(true);
            BufferedImage image = renderer.renderImageWithDPI(
                    pageNumber - 1, dpi, ImageType.RGB);

            ByteArrayOutputStream png = new ByteArrayOutputStream(256 * 1024);
            if (!ImageIO.write(image, "PNG", png)) {
                throw new IOException("PNG renderer is unavailable.");
            }

            appendAudit(
                    tenantId,
                    actorId,
                    "DOCUMENT_PAGE_VIEWED",
                    documentId,
                    correlationId,
                    "{\"page\":" + pageNumber + ",\"scale\":" + String.format(Locale.ROOT, "%.2f", scale) + "}");

            return new PageImage(
                    png.toByteArray(),
                    image.getWidth(),
                    image.getHeight(),
                    pageNumber,
                    pdf.getNumberOfPages());
        } catch (IOException ex) {
            throw unavailable(ex);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public SearchResults search(
            UUID tenantId,
            UUID documentId,
            String query,
            String actorId,
            String correlationId) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isEmpty()) {
            throw new IllegalArgumentException("Viewer text search query is required.");
        }
        if (normalizedQuery.length() > MAX_SEARCH_QUERY_LENGTH) {
            throw new IllegalArgumentException(
                    "Viewer text search query exceeds " + MAX_SEARCH_QUERY_LENGTH + " characters.");
        }

        Document document = requireAvailableDocument(tenantId, documentId);
        byte[] plaintext = verifiedPlaintext(document);
        try (PDDocument pdf = load(plaintext)) {
            String needle = normalizedQuery.toLowerCase(Locale.ROOT);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            List<PageMatch> matches = new ArrayList<>();
            List<SearchHighlight> highlights = new ArrayList<>();
            int totalMatches = 0;
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String haystack = stripper.getText(pdf).toLowerCase(Locale.ROOT);
                final int currentPage = page;
                final PDRectangle cropBox = pdf.getPage(page - 1).getCropBox();
                // TextPosition uses top-left oriented display coordinates; page PNGs use the same crop box.
                PDFTextStripper locator = new PDFTextStripper() {
                    @Override
                    protected void writeString(String text, List<TextPosition> positions) throws IOException {
                        if (positions == null || positions.isEmpty()) return;
                        String line = text.toLowerCase(Locale.ROOT);
                        int start = 0;
                        while (start <= line.length() - needle.length()) {
                            int index = line.indexOf(needle, start);
                            if (index < 0) break;
                            // PDFBox can insert spaces/ligatures without a 1:1 position map.
                            // Only create exact geometric highlights when alignment is unambiguous.
                            if (positions.size() == text.length() && index + needle.length() <= positions.size()) {
                                TextPosition first = positions.get(index);
                                TextPosition last = positions.get(index + needle.length() - 1);
                                // PDFTextStripper's YDirAdj tracks the text baseline, not the top
                                // of the glyph. Use the glyph ascent/height to cover the text itself.
                                // For rotated pages PDFRenderer swaps the visible dimensions.
                                boolean quarterTurn = Math.floorMod(pdf.getPage(currentPage - 1).getRotation(), 180) == 90;
                                double width = quarterTurn ? cropBox.getHeight() : cropBox.getWidth();
                                double height = quarterTurn ? cropBox.getWidth() : cropBox.getHeight();
                                double x = Double.POSITIVE_INFINITY, y = Double.POSITIVE_INFINITY;
                                double right = Double.NEGATIVE_INFINITY, bottom = Double.NEGATIVE_INFINITY;
                                for (int n = index; n < index + needle.length(); n++) {
                                    TextPosition glyph = positions.get(n);
                                    double gx = glyph.getXDirAdj();
                                    double gy = glyph.getYDirAdj();
                                    double gh = glyph.getHeightDir();
                                    x = Math.min(x, gx);
                                    y = Math.min(y, gy - gh);
                                    right = Math.max(right, gx + glyph.getWidthDirAdj());
                                    bottom = Math.max(bottom, gy);
                                }
                                if (width > 0 && height > 0 && x >= 0 && y >= 0 && right <= width && bottom <= height
                                        && right > x && bottom > y) {
                                    highlights.add(new SearchHighlight(currentPage,
                                            x / width, y / height, (right - x) / width, (bottom - y) / height));
                                }
                            }
                            start = index + Math.max(1, needle.length());
                        }
                    }
                };
                locator.setSortByPosition(true);
                locator.setStartPage(page);
                locator.setEndPage(page);
                locator.getText(pdf);
                int count = countOccurrences(haystack, needle);
                if (count > 0) {
                    matches.add(new PageMatch(page, count));
                    totalMatches += count;
                }
            }

            appendAudit(
                    tenantId,
                    actorId,
                    "DOCUMENT_TEXT_SEARCHED",
                    documentId,
                    correlationId,
                    "{\"matchingPages\":" + matches.size() + ",\"matchCount\":" + totalMatches + "}");

            return new SearchResults(
                    pdf.getNumberOfPages(),
                    totalMatches,
                    matches,
                    highlights);
        } catch (IOException ex) {
            throw unavailable(ex);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private byte[] verifiedPlaintext(Document document) {
        DocumentStorageRef storageRef = documents.findStorageRef(
                        document.tenantId(), document.documentId())
                .orElseThrow(() -> new DocumentViewerUnavailableException(
                        "Secure viewer storage metadata is unavailable."));
        DocumentCryptoMetadata cryptoMetadata = documents.findCryptoMetadata(
                        document.tenantId(), document.documentId())
                .orElseThrow(() -> new DocumentViewerUnavailableException(
                        "Secure viewer crypto metadata is unavailable."));
        StorageVolume volume = volumes.findById(
                        document.tenantId(), storageRef.storageVolumeId())
                .orElseThrow(() -> new DocumentViewerUnavailableException(
                        "Secure viewer storage volume is unavailable."));
        StorageProvider provider = provider(storageRef.providerId());

        Long expectedSize = document.contentSizeBytes();
        if (expectedSize == null || expectedSize <= 0 || expectedSize > Integer.MAX_VALUE - 8L) {
            throw new DocumentViewerUnavailableException(
                    "Document size is outside the secure in-memory viewer boundary.");
        }

        FixedPlaintextBuffer plaintext;
        try {
            plaintext = new FixedPlaintextBuffer(
                    Math.toIntExact(expectedSize),
                    document.contentSha256());

            try (InputStream raw = provider.open(
                    document.tenantId(), volume, storageRef.objectKey());
                 DigestingCountingInputStream encrypted = new DigestingCountingInputStream(raw)) {
                StreamingCryptoPort.CryptoEnvelope envelope =
                        new StreamingCryptoPort.CryptoEnvelope(
                                cryptoMetadata.cryptoProfile(),
                                cryptoMetadata.cryptoVersion(),
                                cryptoMetadata.keyProviderId(),
                                cryptoMetadata.protectedDataKeyReference(),
                                cryptoMetadata.keyProviderVersion(),
                                storageRef.encryptedSizeBytes(),
                                storageRef.integrityValue());

                crypto.decrypt(document.tenantId(), envelope, encrypted, plaintext);
                plaintext.finish();

                if (encrypted.count() != storageRef.encryptedSizeBytes()) {
                    plaintext.destroy();
                    throw new DocumentViewerUnavailableException(
                            "Encrypted object size no longer matches committed custody metadata.");
                }
                if (!encrypted.sha256().equals(storageRef.integrityValue())) {
                    plaintext.destroy();
                    throw new DocumentViewerUnavailableException(
                            "Encrypted object integrity no longer matches committed custody metadata.");
                }
            }
            return plaintext.take();
        } catch (DocumentViewerUnavailableException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new DocumentViewerUnavailableException(
                    "The document cannot be securely reconstructed for viewing.", ex);
        }
    }

    private Document requireAvailableDocument(UUID tenantId, UUID documentId) {
        Document document = documents.findDocumentByIdForTenant(tenantId, documentId)
                .orElseThrow(() -> new NoSuchElementException("Document not found."));
        if (document.status() != DocumentStatus.AVAILABLE) {
            throw new IllegalStateException(
                    "Only AVAILABLE documents may be opened in the secure viewer.");
        }
        return document;
    }

    private StorageProvider provider(String providerId) {
        return storageProviders.stream()
                .filter(candidate -> candidate.providerId().equals(providerId))
                .findFirst()
                .orElseThrow(() -> new DocumentViewerUnavailableException(
                        "Configured storage provider is unavailable."));
    }

    private static PDDocument load(byte[] plaintext) throws IOException {
        try {
            return Loader.loadPDF(plaintext);
        } catch (IOException | RuntimeException ex) {
            throw new IOException("The decrypted document is not renderable as a PDF.", ex);
        }
    }

    private void appendAudit(
            UUID tenantId,
            String actorId,
            String action,
            UUID documentId,
            String correlationId,
            String safeDetails) {
        audit.append(new AuditEvent(
                UUID.randomUUID(),
                tenantId,
                "USER",
                actorId,
                action,
                "DOCUMENT",
                documentId.toString(),
                clock.instant(),
                correlationId,
                AuditOutcome.SUCCESS,
                safeDetails));
    }

    private static int countOccurrences(String text, String search) {
        int count = 0;
        int from = 0;
        while (from <= text.length() - search.length()) {
            int match = text.indexOf(search, from);
            if (match < 0) break;
            count++;
            from = match + Math.max(1, search.length());
        }
        return count;
    }

    private static DocumentViewerUnavailableException unavailable(IOException ex) {
        return new DocumentViewerUnavailableException(
                "The document cannot be securely rendered right now.", ex);
    }

    public record ViewerMetadata(
            UUID documentId,
            String originalFilename,
            int pageCount) { }

    public record PageImage(
            byte[] pngBytes,
            int width,
            int height,
            int pageNumber,
            int pageCount) { }

    public record PageMatch(int pageNumber, int matchCount) { }

    public record SearchHighlight(int pageNumber, double x, double y, double width, double height) { }

    public record SearchResults(
            int pageCount,
            int totalMatches,
            List<PageMatch> pages,
            List<SearchHighlight> highlights) {
        public SearchResults {
            pages = List.copyOf(pages);
            highlights = List.copyOf(highlights);
        }
        public SearchResults(int pageCount, int totalMatches, List<PageMatch> pages) {
            this(pageCount, totalMatches, pages, List.of());
        }
    }

    private static final class FixedPlaintextBuffer extends OutputStream {
        private static final byte[] PDF_MAGIC = new byte[]{'%', 'P', 'D', 'F', '-'};

        private final byte[] data;
        private final String expectedSha256;
        private final MessageDigest digest;
        private int position;
        private boolean taken;

        FixedPlaintextBuffer(int expectedBytes, String expectedSha256) throws IOException {
            this.data = new byte[expectedBytes];
            this.expectedSha256 = expectedSha256;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException ex) {
                throw new IOException("SHA-256 is unavailable.", ex);
            }
        }

        @Override
        public void write(int b) throws IOException {
            if (position >= data.length) {
                throw new IOException("Decrypted PDF exceeded committed plaintext size.");
            }
            data[position++] = (byte) b;
            digest.update((byte) b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len < 0 || position > data.length - len) {
                throw new IOException("Decrypted PDF exceeded committed plaintext size.");
            }
            System.arraycopy(b, off, data, position, len);
            digest.update(b, off, len);
            position += len;
        }

        void finish() throws IOException {
            if (position != data.length) {
                throw new IOException("Decrypted PDF size does not match committed plaintext size.");
            }
            if (data.length < PDF_MAGIC.length) {
                throw new IOException("Decrypted content is not a complete PDF.");
            }
            for (int i = 0; i < PDF_MAGIC.length; i++) {
                if (data[i] != PDF_MAGIC[i]) {
                    throw new IOException("Decrypted content no longer has the committed PDF header.");
                }
            }
            String actualSha = HexFormat.of().formatHex(digest.digest());
            if (expectedSha256 == null || !actualSha.equals(expectedSha256)) {
                throw new IOException("Decrypted PDF fingerprint does not match committed custody metadata.");
            }
        }

        byte[] take() {
            taken = true;
            return data;
        }

        void destroy() {
            if (!taken) Arrays.fill(data, (byte) 0);
        }
    }

    private static final class DigestingCountingInputStream extends FilterInputStream {
        private final MessageDigest digest;
        private long count;

        DigestingCountingInputStream(InputStream input) throws IOException {
            super(input);
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException ex) {
                throw new IOException("SHA-256 is unavailable.", ex);
            }
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                digest.update((byte) value);
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) {
                digest.update(b, off, read);
                count += read;
            }
            return read;
        }

        long count() {
            return count;
        }

        String sha256() {
            return HexFormat.of().formatHex(digest.digest());
        }
    }
}
