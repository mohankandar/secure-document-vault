package com.sdv.platform.application.pdf;

import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.domain.source.IngestionSource;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSDocument;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSObjectKey;
import org.apache.pdfbox.io.RandomAccessRead;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0063: the checks of a PDF at intake, over every object of the file (also ones no page uses). Each can be switched
 * off for one sending system by the installer ({@code sdv.pdf.accept}); the default refuses.
 */
@Component
public class PdfInspector implements PdfIntakeChecks {
    /** What a sending system may be allowed to send all the same. */
    enum Accept { UNREADABLE, ENCRYPTED, ACTIVE_CONTENT }

    static final int MAX_OBJECTS = 2_000_000;
    private static final int MAX_NESTING = 200;
    /** The largest image (its decoded pixels) Vero draws. */
    public static final long MAX_IMAGE_PIXELS = 60_000_000L;

    private static final Set<String> ACTIVE_ACTIONS = Set.of("JavaScript", "Launch", "ImportData", "SubmitForm", "Rendition", "Movie", "Sound", "RichMediaExecute");

    private final PdfWork work;
    private final Map<String, Set<Accept>> accept;

    @Autowired
    public PdfInspector(PdfWork work, @Value("${sdv.pdf.accept:}") String accept) {
        this.work = work;
        this.accept = parse(accept);
    }

    /** {@code source-key=encrypted,active-content;other-key=unreadable}. */
    static Map<String, Set<Accept>> parse(String setting) {
        Map<String, Set<Accept>> out = new HashMap<>();
        if (setting == null || setting.isBlank()) return out;
        try {
            for (String part : setting.split(";")) {
                if (part.isBlank()) continue;
                String[] sides = part.split("=", 2);
                if (sides.length != 2 || sides[0].isBlank()) throw new IllegalArgumentException();
                Set<Accept> kinds = EnumSet.noneOf(Accept.class);
                for (String kind : sides[1].split(",")) kinds.add(Accept.valueOf(kind.trim().toUpperCase(Locale.ROOT).replace('-', '_')));
                out.put(sides[0].trim(), kinds);
            }
        } catch (IllegalArgumentException ex) {
            throw new StartupFailure(SdvErrorCode.SYS_005, "sdv.pdf.accept (SDV_PDF_ACCEPT) must be like "
                    + "\"source-key=encrypted,active-content;other-key=unreadable\".");
        }
        return out;
    }

    @Override
    public EncryptedScratch scratch() {
        return work.scratch();
    }

    @Override
    public void check(UUID tenantId, IngestionSource source, int maxPages, RandomAccessRead pdf, EncryptedScratch scratch) throws IOException {
        Set<Accept> accepted = accept.getOrDefault(source.sourceKey(), Set.of());
        Findings found;
        try {
            found = work.run("intake:" + tenantId + ":" + source.sourceId(), () -> inspect(pdf, scratch));
        } catch (PdfWork.TookTooLong ex) {
            throw refused(SdvErrorCode.INT_019, UNREADABLE, "took too long");
        } catch (PdfWork.NotWorkable ex) {
            throw refused(SdvErrorCode.INT_019, UNREADABLE, "nested too deep");
        }
        if (found.unreadable != null && !accepted.contains(Accept.UNREADABLE)) throw refused(SdvErrorCode.INT_019, UNREADABLE, found.unreadable);
        if (found.encrypted && !accepted.contains(Accept.ENCRYPTED)) throw refused(SdvErrorCode.INT_020, ENCRYPTED, "encrypted");
        if (found.active != null && !accepted.contains(Accept.ACTIVE_CONTENT)) throw refused(SdvErrorCode.INT_021, ACTIVE, found.active);
        if (found.pages > maxPages) {
            throw refused(SdvErrorCode.INT_022, "This PDF has more than " + maxPages + " pages, the most Vero takes from this sending system.",
                    "pages=" + found.pages);
        }
    }

    static final String UNREADABLE = "This PDF can't be read, so it was not stored.";
    static final String ENCRYPTED = "This PDF is encrypted or protected with a password, so it was not stored. Send it without protection.";
    static final String ACTIVE = "This PDF carries active content (scripts, actions or attached files), so it was not stored. Send it without.";

    private static CodedRefusal.BadRequest refused(SdvErrorCode code, String message, String detail) {
        return new CodedRefusal.BadRequest(code, message, detail);
    }

    static final class Findings {
        String unreadable;
        boolean encrypted;
        String active;
        int pages;
    }

    static Findings inspect(RandomAccessRead pdf, EncryptedScratch scratch) {
        Findings found = new Findings();
        pdf: try (PDDocument document = Loader.loadPDF(pdf, () -> new Unclosed(scratch))) {
            found.encrypted = document.isEncrypted();
            found.pages = document.getNumberOfPages();
            if (found.pages < 1) {
                found.unreadable = "no pages";
                break pdf;
            }
            found.active = active(document.getDocument());
            for (PDPage page : document.getPages()) {
                if (page.getCropBox().getWidth() <= 0 || page.getCropBox().getHeight() <= 0) {
                    found.unreadable = "a page without an area";
                    break;
                }
            }
        } catch (InvalidPasswordException ex) {
            found.encrypted = true;
        } catch (IOException | RuntimeException ex) {
            found.unreadable = ex.getClass().getSimpleName();
        }
        return found;
    }

    /** The scratch space is closed by its owner, not by the document. */
    private record Unclosed(EncryptedScratch scratch) implements org.apache.pdfbox.io.RandomAccessStreamCache {
        @Override
        public org.apache.pdfbox.io.RandomAccess createBuffer() throws IOException {
            return scratch.createBuffer();
        }

        @Override
        public void close() { }
    }

    /** For PDFBox: {@code scratch} as its working space. */
    public static org.apache.pdfbox.io.RandomAccessStreamCache.StreamCacheCreateFunction over(EncryptedScratch scratch) {
        return () -> new Unclosed(scratch);
    }

    /** What kind of active content the file holds, or null. Looks at every object, not only at what pages reach. */
    private static String active(COSDocument document) throws IOException {
        List<COSObjectKey> keys = List.copyOf(document.getXrefTable().keySet());
        if (keys.size() > MAX_OBJECTS) throw new IOException("too many objects");
        for (COSObjectKey key : keys) {
            COSObject object = document.getObjectFromPool(key);
            String kind = object == null ? null : activeIn(object.getObject());
            if (kind != null) return kind;
        }
        return activeIn(document.getTrailer());
    }

    /** Direct values only: an indirect one is an object of its own, and looked at there. */
    private static String activeIn(COSBase start) throws IOException {
        record Step(COSBase value, int depth) { }
        Deque<Step> todo = new ArrayDeque<>();
        if (start != null) todo.push(new Step(start, 0));
        while (!todo.isEmpty()) {
            Step step = todo.pop();
            if (step.depth() > MAX_NESTING) throw new IOException("nested too deep");
            if (step.value() instanceof COSArray array) {
                for (int i = 0; i < array.size(); i++) push(todo, array.get(i), step.depth(), Step::new);
            } else if (step.value() instanceof COSDictionary dictionary) {
                String kind = kind(dictionary);
                if (kind != null) return kind;
                for (COSBase value : dictionary.getValues()) push(todo, value, step.depth(), Step::new);
            }
        }
        return null;
    }

    private static <S> void push(Deque<S> todo, COSBase value, int depth, java.util.function.BiFunction<COSBase, Integer, S> step) {
        if (value instanceof COSArray || value instanceof COSDictionary) todo.push(step.apply(value, depth + 1));
    }

    private static String kind(COSDictionary d) {
        if (d.containsKey(COSName.JS) || d.containsKey(COSName.getPDFName("JavaScript"))) return "script";
        String action = d.getNameAsString(COSName.S);
        if (action != null && ACTIVE_ACTIONS.contains(action)) return "action " + action;
        if (d.containsKey(COSName.EF) || d.containsKey(COSName.getPDFName("EmbeddedFiles"))
                || "EmbeddedFile".equals(d.getNameAsString(COSName.TYPE))) return "attached file";
        if (d.containsKey(COSName.getPDFName("XFA"))) return "XFA form";
        String subtype = d.getNameAsString(COSName.SUBTYPE);
        if ("RichMedia".equals(subtype) || "Movie".equals(subtype) || "Sound".equals(subtype) || "Screen".equals(subtype)
                || "FileAttachment".equals(subtype) || "3D".equals(subtype)) return "media " + subtype;
        return null;
    }
}
