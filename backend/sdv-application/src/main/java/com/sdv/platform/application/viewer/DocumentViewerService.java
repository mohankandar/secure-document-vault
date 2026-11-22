package com.sdv.platform.application.viewer;

import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.document.DocumentRefusals;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.PageViewAuditPort;
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
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.storage.StorageVolume;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class DocumentViewerService {
    private static final double MIN_SCALE = 0.50d;
    private static final double MAX_SCALE = 2.50d;
    private static final float BASE_DPI = 96.0f;
    private static final double MAX_RENDER_PIXELS = 24_000_000d;
    private static final int MAX_SEARCH_QUERY_LENGTH = 100;
    public static final int PREVIEW_WIDTH_PX = 120;
    static final int PREVIEW_MAX_HEIGHT_PX = 400;
    public static final int MAX_PREVIEW_COUNT = 24;

    private final DocumentIntakeRepository documents;
    private final StorageVolumeRepository volumes;
    private final List<StorageProvider> storageProviders;
    private final StreamingCryptoPort crypto;
    private final AuditEventPort audit;
    private final PageViewAuditPort pageViews;
    private final DocumentAccessGuard access;
    private final Clock clock;

    /** A viewing (one DOCUMENT_VIEW_OPENED) groups page events for this long. */
    static final Duration VIEWING_LIFETIME = Duration.ofHours(8);

    @Autowired
    public DocumentViewerService(
            DocumentIntakeRepository documents,
            StorageVolumeRepository volumes,
            List<StorageProvider> storageProviders,
            StreamingCryptoPort crypto,
            AuditEventPort audit,
            PageViewAuditPort pageViews,
            DocumentAccessGuard access) {
        this(documents, volumes, storageProviders, crypto, audit, pageViews, access, Clock.systemUTC());
    }

    DocumentViewerService(
            DocumentIntakeRepository documents,
            StorageVolumeRepository volumes,
            List<StorageProvider> storageProviders,
            StreamingCryptoPort crypto,
            AuditEventPort audit,
            PageViewAuditPort pageViews,
            DocumentAccessGuard access,
            Clock clock) {
        this.documents = documents;
        this.volumes = volumes;
        this.storageProviders = List.copyOf(storageProviders);
        this.crypto = crypto;
        this.audit = audit;
        this.pageViews = pageViews;
        this.access = java.util.Objects.requireNonNull(access, "access");
        this.clock = clock;
    }

    public ViewerMetadata metadata(
            UUID tenantId,
            UUID documentId,
            Clearance clearance,
            String actorId,
            String correlationId) {
        Viewable viewable = requireViewable(tenantId, documentId, clearance,
                DocumentAccessGuard.Attempt.OPEN, actorId, correlationId);
        Document document = viewable.document();
        byte[] plaintext = verifiedPlaintext(document);
        try (PDDocument pdf = load(plaintext)) {
            int pageCount = pdf.getNumberOfPages();
            // The open event's ID is the viewing ID: page requests send it back so each page is recorded once.
            UUID viewingId = appendAudit(
                    tenantId,
                    actorId,
                    "DOCUMENT_VIEW_OPENED",
                    documentId,
                    correlationId,
                    // Task 0031 (0.1): the effective level at the time of viewing, for the audit's level filter.
                    "{\"pageCount\":" + pageCount + levelDetail(viewable) + "}");
            return new ViewerMetadata(document.documentId(), document.originalFilename(), pageCount, viewingId);
        } catch (IOException ex) {
            throw cantDraw(documentId, "open", ex);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public PageImage renderPage(
            UUID tenantId,
            UUID documentId,
            Clearance clearance,
            int pageNumber,
            double scale,
            String actorId,
            String correlationId) {
        return renderPage(tenantId, documentId, clearance, pageNumber, scale, null, actorId, correlationId);
    }

    /**
     * Renders one page. With a {@code viewingId} that the server accepts (this person's own DOCUMENT_VIEW_OPENED for
     * this document and tenant, less than {@link #VIEWING_LIFETIME} old) the page event is written only the first
     * time this page is sent in that viewing, whatever the zoom. Without one, or with one it does not accept, the
     * page is recorded as before: leaving the ID out can never avoid the record.
     */
    public PageImage renderPage(
            UUID tenantId,
            UUID documentId,
            Clearance clearance,
            int pageNumber,
            double scale,
            UUID viewingId,
            String actorId,
            String correlationId) {
        if (pageNumber < 1) throw invalid("page must be at least 1.");
        if (!Double.isFinite(scale) || scale < MIN_SCALE || scale > MAX_SCALE) {
            throw invalid(
                    "scale must be between " + MIN_SCALE + " and " + MAX_SCALE + ".");
        }

        Viewable viewable = requireViewable(tenantId, documentId, clearance, DocumentAccessGuard.Attempt.PAGE, actorId, correlationId);
        Document document = viewable.document();
        byte[] plaintext = verifiedPlaintext(document);
        try (PDDocument pdf = load(plaintext)) {
            if (pageNumber > pdf.getNumberOfPages()) {
                throw invalid(
                        "page exceeds the document page count of " + pdf.getNumberOfPages() + ".");
            }

            PDPage page = pdf.getPage(pageNumber - 1);
            PDRectangle box = page.getCropBox();
            float dpi = (float) (BASE_DPI * scale);
            double widthPixels = (box.getWidth() / 72.0d) * dpi;
            double heightPixels = (box.getHeight() / 72.0d) * dpi;
            if (widthPixels <= 0 || heightPixels <= 0
                    || widthPixels * heightPixels > MAX_RENDER_PIXELS) {
                throw invalid(
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

            String scaleText = String.format(Locale.ROOT, "%.2f", scale);
            Instant now = clock.instant();
            Instant notBefore = now.minus(VIEWING_LIFETIME);
            if (viewingId != null && pageViews.isViewing(tenantId, actorId, documentId, viewingId, notBefore)) {
                pageViews.appendPageEventOnce(new AuditEvent(
                        UUID.randomUUID(), tenantId, "USER", actorId, "DOCUMENT_PAGE_VIEWED", "DOCUMENT",
                        documentId.toString(), now, correlationId, AuditOutcome.SUCCESS,
                        "{\"page\":" + pageNumber + ",\"scale\":" + scaleText + ",\"viewingId\":\"" + viewingId + "\"" + levelDetail(viewable) + "}"),
                        viewingId, pageNumber, notBefore);
            } else {
                appendAudit(
                        tenantId,
                        actorId,
                        "DOCUMENT_PAGE_VIEWED",
                        documentId,
                        correlationId,
                        "{\"page\":" + pageNumber + ",\"scale\":" + scaleText + levelDetail(viewable) + "}");
            }

            return new PageImage(
                    png.toByteArray(),
                    image.getWidth(),
                    image.getHeight(),
                    pageNumber,
                    pdf.getNumberOfPages());
        } catch (IOException ex) {
            throw cantDraw(documentId, "page " + pageNumber, ex);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * Small page previews for the viewer's page list: pages {@code first} to {@code first + count - 1} (clipped to the
     * page count), each about {@link #PREVIEW_WIDTH_PX} px wide. Same checks as the viewer (AVAILABLE document in the
     * caller's tenant, verified plaintext of the current PDF, so redactions show), the document is decrypted once per
     * request, and no audit event is written: previews belong to the DOCUMENT_VIEW_OPENED already recorded.
     */
    public PagePreviews previews(UUID tenantId, UUID documentId, int first, int count,
                                 Clearance clearance, String actorId, String correlationId) {
        if (first < 1) throw invalid("first must be at least 1.");
        if (count < 1 || count > MAX_PREVIEW_COUNT) {
            throw invalid("count must be between 1 and " + MAX_PREVIEW_COUNT + ".");
        }

        Document document = requireAvailableDocument(tenantId, documentId, clearance, DocumentAccessGuard.Attempt.PREVIEWS, actorId, correlationId);
        byte[] plaintext = verifiedPlaintext(document);
        try (PDDocument pdf = load(plaintext)) {
            int pageCount = pdf.getNumberOfPages();
            if (first > pageCount) {
                throw invalid("first exceeds the document page count of " + pageCount + ".");
            }
            int last = Math.min(pageCount, first + count - 1);
            PDFRenderer renderer = new PDFRenderer(pdf);
            renderer.setSubsamplingAllowed(true);
            List<PagePreview> previews = new ArrayList<>(last - first + 1);
            Base64.Encoder base64 = Base64.getEncoder();
            for (int pageNumber = first; pageNumber <= last; pageNumber++) {
                PDPage page = pdf.getPage(pageNumber - 1);
                PDRectangle box = page.getCropBox();
                boolean quarterTurn = Math.floorMod(page.getRotation(), 180) == 90;
                double widthPoints = quarterTurn ? box.getHeight() : box.getWidth();
                double heightPoints = quarterTurn ? box.getWidth() : box.getHeight();
                if (widthPoints <= 0 || heightPoints <= 0) {
                    throw new IOException("Page " + pageNumber + " has no printable area.");
                }
                // Fixed width; very tall pages are capped in height instead.
                float dpi = (float) Math.min(
                        PREVIEW_WIDTH_PX * 72.0d / widthPoints,
                        PREVIEW_MAX_HEIGHT_PX * 72.0d / heightPoints);
                BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, dpi, ImageType.RGB);
                ByteArrayOutputStream png = new ByteArrayOutputStream(16 * 1024);
                if (!ImageIO.write(image, "PNG", png)) {
                    throw new IOException("PNG renderer is unavailable.");
                }
                previews.add(new PagePreview(pageNumber, image.getWidth(), image.getHeight(),
                        "data:image/png;base64," + base64.encodeToString(png.toByteArray())));
            }
            return new PagePreviews(document.documentId(), pageCount, first, previews);
        } catch (IOException ex) {
            throw cantDraw(documentId, "previews from page " + first, ex);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public SearchResults search(
            UUID tenantId,
            UUID documentId,
            String query,
            Clearance clearance,
            String actorId,
            String correlationId) {
        String normalizedQuery = query == null ? "" : query.trim();
        if (normalizedQuery.isEmpty()) {
            throw invalid("Viewer text search query is required.");
        }
        if (normalizedQuery.length() > MAX_SEARCH_QUERY_LENGTH) {
            throw invalid(
                    "Viewer text search query exceeds " + MAX_SEARCH_QUERY_LENGTH + " characters.");
        }

        Document document = requireAvailableDocument(tenantId, documentId, clearance, DocumentAccessGuard.Attempt.TEXT_SEARCH, actorId, correlationId);
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
            throw cantDraw(documentId, "text search", ex);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    /**
     * Task 0043: each cause has its own code (it was one VIEWER_UNAVAILABLE): storage metadata, the volume, the
     * storage provider, reading storage or the key service is SDV-DOC-003; a size or fingerprint that no longer
     * matches, a decryption check, or a document too big for the in-memory viewer is SDV-DOC-004.
     */
    private byte[] verifiedPlaintext(Document document) {
        UUID documentId = document.documentId();
        DocumentStorageRef storageRef = documents.findStorageRef(document.tenantId(), documentId)
                .orElseThrow(() -> unreadable(documentId, "storage metadata missing", null));
        DocumentCryptoMetadata cryptoMetadata = documents.findCryptoMetadata(document.tenantId(), documentId)
                .orElseThrow(() -> unreadable(documentId, "crypto metadata missing", null));
        StorageVolume volume = volumes.findById(document.tenantId(), storageRef.storageVolumeId())
                .orElseThrow(() -> unreadable(documentId, "storage volume missing", null));
        StorageProvider provider = provider(documentId, storageRef.providerId());

        Long expectedSize = document.contentSizeBytes();
        if (expectedSize == null || expectedSize <= 0) {
            throw checkFailed(documentId, "size metadata missing", null);
        }
        if (expectedSize > Integer.MAX_VALUE - 8L) {
            throw new DocumentViewerUnavailableException(SdvErrorCode.DOC_004, TOO_LARGE_MESSAGE,
                    "document=" + documentId + " check=too large for the in-memory viewer bytes=" + expectedSize);
        }

        FixedPlaintextBuffer plaintext;
        try {
            plaintext = new FixedPlaintextBuffer(
                    Math.toIntExact(expectedSize),
                    document.contentSha256());

            InputStream opened;
            try {
                opened = provider.open(document.tenantId(), volume, storageRef.objectKey());
            } catch (IOException | RuntimeException ex) {
                throw unreadable(documentId, "storage could not be opened", ex);
            }
            try (InputStream raw = new StorageReadStream(opened);
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
                    throw checkFailed(documentId, "encrypted size no longer matches", null);
                }
                if (!encrypted.sha256().equals(storageRef.integrityValue())) {
                    plaintext.destroy();
                    throw checkFailed(documentId, "encrypted fingerprint no longer matches", null);
                }
            }
            return plaintext.take();
        } catch (DocumentViewerUnavailableException ex) {
            throw ex;
        } catch (StorageReadFailed ex) {
            throw unreadable(documentId, "storage read failed", ex.getCause());
        } catch (CheckFailed ex) {
            throw checkFailed(documentId, ex.getMessage(), null);
        } catch (IOException ex) {
            // The decryption's own checks: header, chunk authentication, final marker.
            throw checkFailed(documentId, "decryption check failed", ex);
        } catch (RuntimeException ex) {
            // The key service, or anything else in decryption that isn't a check.
            throw unreadable(documentId, "key service or decryption failed", ex);
        }
    }

    /** Task 0026: the level check comes first, so a restricted document reveals nothing, not even its status. */
    private Document requireAvailableDocument(UUID tenantId, UUID documentId, Clearance clearance,
                                              DocumentAccessGuard.Attempt attempt, String actorId, String correlationId) {
        return requireViewable(tenantId, documentId, clearance, attempt, actorId, correlationId).document();
    }

    /** A document the person may view, with its effective level at this moment (task 0031, 0.1). */
    private record Viewable(Document document, SensitivityLevel level) { }

    private Viewable requireViewable(UUID tenantId, UUID documentId, Clearance clearance,
                                     DocumentAccessGuard.Attempt attempt, String actorId, String correlationId) {
        Document document = documents.findDocumentByIdForTenant(tenantId, documentId)
                .orElseThrow(() -> DocumentRefusals.notFound(documentId));
        SensitivityLevel level = access.requireOpenable(document, clearance, attempt, actorId, correlationId);
        if (document.status() != DocumentStatus.AVAILABLE) {
            throw DocumentRefusals.notAvailable(documentId, document.status());
        }
        return new Viewable(document, level);
    }

    /**
     * Task 0031 (0.1): openings and page views record the effective level at the time of viewing, under the same key
     * prints and downloads use, so the audit's level filter finds them. Events written before this have no level.
     */
    private static String levelDetail(Viewable viewable) {
        return ",\"sensitivityLevel\":\"" + viewable.level().name() + "\"";
    }

    private StorageProvider provider(UUID documentId, String providerId) {
        return storageProviders.stream()
                .filter(candidate -> candidate.providerId().equals(providerId))
                .findFirst()
                .orElseThrow(() -> unreadable(documentId, "storage provider not installed", null));
    }

    private static PDDocument load(byte[] plaintext) throws IOException {
        try {
            return Loader.loadPDF(plaintext);
        } catch (IOException | RuntimeException ex) {
            throw new IOException("The decrypted document is not renderable as a PDF.", ex);
        }
    }

    /** Appends an audit event and returns its ID. */
    private UUID appendAudit(
            UUID tenantId,
            String actorId,
            String action,
            UUID documentId,
            String correlationId,
            String safeDetails) {
        UUID eventId = UUID.randomUUID();
        audit.append(new AuditEvent(
                eventId,
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
        return eventId;
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

    static final String UNREADABLE_MESSAGE = "Vero couldn't read this document from storage. Try again later.";
    static final String CHECK_FAILED_MESSAGE =
            "This document's stored copy didn't pass Vero's check, so it isn't shown.";
    static final String TOO_LARGE_MESSAGE = "This document is too large to view here.";
    static final String CANT_DRAW_MESSAGE =
            "Vero can't draw this page. Download it if your role allows, or tell your IT team.";

    /** Task 0043: SDV-DOC-006, a viewer request that isn't valid (400); the message says what. */
    private static CodedRefusal.BadRequest invalid(String message) {
        return new CodedRefusal.BadRequest(SdvErrorCode.DOC_006, message);
    }

    /** Task 0043: SDV-DOC-003, the stored copy can't be read; the step goes to the log line only. */
    private static DocumentViewerUnavailableException unreadable(UUID documentId, String step, Throwable cause) {
        return new DocumentViewerUnavailableException(SdvErrorCode.DOC_003, UNREADABLE_MESSAGE,
                "document=" + documentId + " step=" + step, cause);
    }

    /** Task 0043: SDV-DOC-004, the stored copy failed a check; which check goes to the log line only. */
    private static DocumentViewerUnavailableException checkFailed(UUID documentId, String check, Throwable cause) {
        return new DocumentViewerUnavailableException(SdvErrorCode.DOC_004, CHECK_FAILED_MESSAGE,
                "document=" + documentId + " check=" + check, cause);
    }

    /** Task 0043: SDV-DOC-005, the PDF or a page can't be drawn (the file itself passed its checks). */
    private static DocumentViewerUnavailableException cantDraw(UUID documentId, String where, IOException ex) {
        return new DocumentViewerUnavailableException(SdvErrorCode.DOC_005, CANT_DRAW_MESSAGE,
                "document=" + documentId + " at=" + where, ex);
    }

    /** Task 0043: reading the stored copy failed (not the key service, not a check). */
    private static final class StorageReadFailed extends IOException {
        StorageReadFailed(IOException cause) {
            super("storage read failed", cause);
        }
    }

    /** Task 0043: the decrypted copy failed a size, header or fingerprint check. */
    private static final class CheckFailed extends IOException {
        CheckFailed(String check) {
            super(check);
        }
    }

    /** Task 0043: marks a failure reading the stored copy, so the code and the log line can say which step failed. */
    private static final class StorageReadStream extends FilterInputStream {
        StorageReadStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            try {
                return super.read();
            } catch (IOException ex) {
                throw new StorageReadFailed(ex);
            }
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            try {
                return super.read(b, off, len);
            } catch (IOException ex) {
                throw new StorageReadFailed(ex);
            }
        }
    }

    /** viewingId: the ID of this opening's DOCUMENT_VIEW_OPENED event; page requests send it back. */
    public record ViewerMetadata(
            UUID documentId,
            String originalFilename,
            int pageCount,
            UUID viewingId) { }

    public record PageImage(
            byte[] pngBytes,
            int width,
            int height,
            int pageNumber,
            int pageCount) { }

    /** One page preview: a PNG as a data URL, so the browser can show it without another request. */
    public record PagePreview(int pageNumber, int width, int height, String dataUrl) { }

    public record PagePreviews(UUID documentId, int pageCount, int first, List<PagePreview> previews) {
        public PagePreviews {
            previews = List.copyOf(previews);
        }
    }

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
                throw new CheckFailed("decrypted size larger than stored");
            }
            data[position++] = (byte) b;
            digest.update((byte) b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len < 0 || position > data.length - len) {
                throw new CheckFailed("decrypted size larger than stored");
            }
            System.arraycopy(b, off, data, position, len);
            digest.update(b, off, len);
            position += len;
        }

        void finish() throws IOException {
            if (position != data.length) {
                throw new CheckFailed("decrypted size does not match");
            }
            if (data.length < PDF_MAGIC.length) {
                throw new CheckFailed("decrypted content is not a complete PDF");
            }
            for (int i = 0; i < PDF_MAGIC.length; i++) {
                if (data[i] != PDF_MAGIC[i]) {
                    throw new CheckFailed("decrypted content has no PDF header");
                }
            }
            String actualSha = HexFormat.of().formatHex(digest.digest());
            if (expectedSha256 == null || !actualSha.equals(expectedSha256)) {
                throw new CheckFailed("decrypted fingerprint does not match");
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
