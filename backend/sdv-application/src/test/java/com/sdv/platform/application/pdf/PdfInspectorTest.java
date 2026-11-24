package com.sdv.platform.application.pdf;

import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.source.IngestionSourceStatus;
import com.sdv.platform.domain.source.SourceLimits;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0063: what is refused at intake, each with its own code, unless the installer allows it for one sending system. */
class PdfInspectorTest {
    private static final UUID TENANT = UUID.randomUUID();

    private interface Fill {
        void in(PDDocument pdf) throws IOException;
    }

    private static byte[] pdf(int pages, Fill fill) throws IOException {
        try (PDDocument pdf = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) pdf.addPage(new PDPage());
            fill.in(pdf);
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static IngestionSource source(String key) {
        return new IngestionSource(UUID.randomUUID(), TENANT, key, key, IngestionSourceStatus.ACTIVE, null, null,
                new SourceLimits(null, null, null, null, null), Instant.EPOCH, Instant.EPOCH);
    }

    private static String outcome(PdfInspector inspector, String sourceKey, byte[] bytes) throws IOException {
        try (EncryptedScratch scratch = inspector.scratch(); EncryptedScratch.Buffer copy = scratch.createBuffer()) {
            copy.write(bytes);
            copy.seek(0);
            inspector.check(TENANT, source(sourceKey), 5, copy, scratch);
            return "accepted";
        } catch (CodedRefusal.BadRequest refused) {
            return refused.errorCode().code();
        }
    }

    private static String outcome(byte[] bytes) throws IOException {
        return outcome(new PdfInspector(PdfWork.defaults(), ""), "any", bytes);
    }

    @Test
    void anOrdinaryPdfIsAccepted() throws Exception {
        assertEquals("accepted", outcome(pdf(5, pdf -> pdf.getDocumentInformation().setTitle("Plain"))));
    }

    @Test
    void whatCantBeReadIsInt019() throws Exception {
        assertEquals("SDV-INT-019", outcome("%PDF-1.7\nnot really\n%%EOF".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("SDV-INT-019", outcome(pdf(0, pdf -> { })), "no pages");
        assertEquals("SDV-INT-019", outcome("%PDF-".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void encryptedIsInt020WithOrWithoutAnOpenPassword() throws Exception {
        for (String open : new String[] {"", "open-me"}) {
            assertEquals("SDV-INT-020", outcome(pdf(1, pdf -> pdf.protect(new StandardProtectionPolicy("owner", open, new AccessPermission())))), open);
        }
    }

    @Test
    void activeContentIsInt021WhereverItIs() throws Exception {
        assertEquals("SDV-INT-021", outcome(pdf(1, pdf -> pdf.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert(1)")))), "script at opening");
        assertEquals("SDV-INT-021", outcome(pdf(1, pdf -> {
            PDAnnotationLink link = new PDAnnotationLink();
            link.setAction(new PDActionLaunch());
            pdf.getPage(0).getAnnotations().add(link);
        })), "launch from a link");
        assertEquals("SDV-INT-021", outcome(pdf(1, pdf -> {
            PDEmbeddedFile file = new PDEmbeddedFile(pdf, new ByteArrayInputStream(new byte[] {1, 2, 3}));
            PDComplexFileSpecification spec = new PDComplexFileSpecification();
            spec.setFile("a.exe");
            spec.setEmbeddedFile(file);
            PDEmbeddedFilesNameTreeNode tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(Map.of("a", spec));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(pdf.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            pdf.getDocumentCatalog().setNames(names);
        })), "attached file");
        assertEquals("SDV-INT-021", outcome(pdf(1, pdf -> {
            COSDictionary form = new COSDictionary();
            form.setItem(COSName.getPDFName("XFA"), new COSArray());
            pdf.getDocumentCatalog().getCOSObject().setItem(COSName.ACRO_FORM, form);
        })), "XFA form");
        assertEquals("SDV-INT-021", outcome(pdf(1, pdf -> {
            COSDictionary hidden = new COSDictionary();
            hidden.setItem(COSName.S, COSName.getPDFName("SubmitForm"));
            COSArray deep = new COSArray();
            deep.add(hidden);
            pdf.getPage(0).getCOSObject().setItem(COSName.getPDFName("Odd"), deep);
        })), "an action inside an array");
    }

    @Test
    void tooManyPagesIsInt022() throws Exception {
        assertEquals("SDV-INT-022", outcome(pdf(6, pdf -> { })));
    }

    @Test
    void theInstallerCanAllowEachForOneSendingSystemOnly() throws Exception {
        byte[] script = pdf(1, pdf -> pdf.getDocumentCatalog().setOpenAction(new PDActionJavaScript("app.alert(1)")));
        byte[] broken = "%PDF-1.7\nnot really\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        byte[] locked = pdf(1, pdf -> pdf.protect(new StandardProtectionPolicy("owner", "open-me", new AccessPermission())));
        PdfInspector inspector = new PdfInspector(PdfWork.defaults(), "scanner=unreadable,encrypted; forms = active-content");
        assertEquals("accepted", outcome(inspector, "scanner", broken));
        assertEquals("accepted", outcome(inspector, "scanner", locked));
        assertEquals("SDV-INT-021", outcome(inspector, "scanner", script));
        assertEquals("accepted", outcome(inspector, "forms", script));
        assertEquals("SDV-INT-019", outcome(inspector, "forms", broken));
        assertEquals("SDV-INT-021", outcome(inspector, "another", script));
        for (String bad : new String[] {"scanner", "scanner=everything", "=encrypted"}) {
            assertThrows(StartupFailure.class, () -> new PdfInspector(PdfWork.defaults(), bad), bad);
        }
    }
}
