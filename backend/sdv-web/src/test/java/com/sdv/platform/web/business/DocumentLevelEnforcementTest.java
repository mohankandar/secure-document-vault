package com.sdv.platform.web.business;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.business.BusinessExperienceService;
import com.sdv.platform.application.document.DocumentActionService;
import com.sdv.platform.application.download.ControlledDownloadService;
import com.sdv.platform.application.download.DocumentDownloadUnavailableException;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessActivityQueryPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.AvailableDocumentSnapshot;
import com.sdv.platform.application.port.BusinessRecordRepository.MetadataSnapshot;
import com.sdv.platform.application.port.DocumentCommentRepository;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.DocumentRedactionRepository;
import com.sdv.platform.application.port.DocumentTagRepository;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.PageViewAuditPort;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.application.redaction.CorrectiveRedactionService;
import com.sdv.platform.application.redaction.PdfSanitizingRedactor;
import com.sdv.platform.application.storage.StoragePlacementService;
import com.sdv.platform.application.viewer.DocumentViewerService;
import com.sdv.platform.application.viewer.DocumentViewerUnavailableException;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionStatus;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantStatus;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0026: every cell of {@code docs/design/sensitivity/ROLE-MATRIX.md} section 3.1, for each role and each level,
 * through the real controllers, the real permission check (role matrix, no overrides) and the real services. Only
 * storage, crypto and the database are mocked, so an allowed request stops later with "storage unavailable" and a
 * refused one never reaches storage.
 * <p>
 * Every cell runs twice: once with the document's own level, once with a Standard document in a record that has the
 * level (the effective level).
 */
class DocumentLevelEnforcementTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SUB = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID DOC = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID STANDARD_DOC = UUID.fromString("dddddddd-dddd-dddd-dddd-000000000001");
    private static final Instant NOW = Instant.parse("2026-11-20T12:00:00Z");

    /** ✓ allowed; ✗ the role lacks the permission; L locked (SDV-SEN-008); A needs an approval (SDV-SEN-009). */
    enum Outcome { ALLOWED, NO_ROLE, LOCKED, APPROVAL }

    enum Function { OPEN, PAGE_IMAGE, THUMBNAILS, TEXT_SEARCH, PRINT, DOWNLOAD, REDACT, TAGS, ADD_TAG, COMMENTS, ADD_COMMENT, ACTIVITY }

    private static final List<SdvRole> ROLES = List.of(SdvRole.BUSINESS_USER, SdvRole.DOCUMENT_STEWARD,
            SdvRole.RESTRICTED_STEWARD, SdvRole.BUSINESS_ADMIN, SdvRole.IT_ADMIN, SdvRole.PLATFORM_OPERATIONS_ADMIN);

    /**
     * ROLE-MATRIX.md 3.1, one string per role in the order of {@link #ROLES}, each giving Standard, Confidential and
     * Highly Confidential. Where the matrix says ✗ only because of the level (a Steward redacting, printing or
     * downloading Highly Confidential), the refusal is the level's: L. Reading tags, comments and a document's activity
     * go with opening it.
     */
    private static final Map<Function, String[]> MATRIX = new EnumMap<>(Function.class);

    static {
        String[] open = {"✓LL", "✓✓L", "✓✓✓", "✗✗✗", "✗✗✗", "✗✗✗"};
        MATRIX.put(Function.OPEN, open);
        MATRIX.put(Function.PAGE_IMAGE, open);
        MATRIX.put(Function.THUMBNAILS, open);
        MATRIX.put(Function.TEXT_SEARCH, open);
        MATRIX.put(Function.TAGS, open);
        MATRIX.put(Function.COMMENTS, open);
        MATRIX.put(Function.ACTIVITY, open);
        MATRIX.put(Function.REDACT, new String[]{"✗✗✗", "✓✓L", "✓✓✓", "✗✗✗", "✗✗✗", "✗✗✗"});
        String[] printOrDownload = {"✗✗✗", "✓✓L", "✓✓A", "✗✗✗", "✗✗✗", "✗✗✗"};
        MATRIX.put(Function.PRINT, printOrDownload);
        MATRIX.put(Function.DOWNLOAD, printOrDownload);
        String[] tagAndComment = {"✗✗✗", "✓✓L", "✓✓✓", "✗✗✗", "✗✗✗", "✗✗✗"};
        MATRIX.put(Function.ADD_TAG, tagAndComment);
        MATRIX.put(Function.ADD_COMMENT, tagAndComment);
    }

    private static Outcome expected(SdvRole role, SensitivityLevel level, Function function) {
        char cell = MATRIX.get(function)[ROLES.indexOf(role)].charAt(level.ordinal());
        return switch (cell) {
            case '✓' -> Outcome.ALLOWED;
            case '✗' -> Outcome.NO_ROLE;
            case 'L' -> Outcome.LOCKED;
            case 'A' -> Outcome.APPROVAL;
            default -> throw new IllegalStateException("cell " + cell);
        };
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    /** One world: one record with a Standard document and the document under test. */
    private static final class World {
        final AuditEventPort audit = mock(AuditEventPort.class);
        final DocumentIntakeRepository documents = mock(DocumentIntakeRepository.class);
        final BusinessRecordRepository records = mock(BusinessRecordRepository.class);
        final TenantRepository tenants = mock(TenantRepository.class);
        final IngestionSourceRepository sources = mock(IngestionSourceRepository.class);
        final MetadataFieldDefinitionRepository definitions = mock(MetadataFieldDefinitionRepository.class);
        final DocumentTagRepository tags = mock(DocumentTagRepository.class);
        final DocumentCommentRepository comments = mock(DocumentCommentRepository.class);
        final AuthorizationService authorization = new AuthorizationService(audit,
                new CapabilityPolicyService(mock(RoleCapabilityPolicyPort.class)));
        final SecurityContextFacade security = new SecurityContextFacade();
        final DocumentAccessGuard guard = new DocumentAccessGuard(records, audit);
        final SecurePdfViewerController viewer;
        final ControlledDownloadController download;
        final DocumentActionController actions;
        final CorrectiveRedactionController redaction;
        final BusinessExperienceController business;

        World(SensitivityLevel ownLevel, SensitivityLevel recordLevel) {
            when(documents.findDocumentByIdForTenant(TENANT, DOC)).thenReturn(Optional.of(document(DOC, ownLevel)));
            when(records.findMetadataSnapshots(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                    new MetadataSnapshot(SUB, "contractNumber", "Contract Number", "300000000076", "300000000076", true)));
            when(records.findRecordSensitivityLevel(eq(TENANT), eq(SOURCE), anyString()))
                    .thenReturn(Optional.ofNullable(recordLevel));
            when(records.findRecordSensitivityLevels(eq(TENANT), eq(SOURCE), anyCollection())).thenCallRealMethod();
            when(records.findSubmissionIdsByRecordIdentity(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(SUB));
            Submission submission = new Submission(SUB, TENANT, SOURCE, "src-1", 2, "sha", SubmissionStatus.COMPLETE,
                    NOW.minusSeconds(60), NOW, null);
            when(records.findSubmissionsByIds(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(submission));
            when(records.findMatchingSubmissions(eq(TENANT), eq(SOURCE), anyList(), any(), any(), any(), any(),
                    anyBoolean(), eq(0), anyInt())).thenReturn(List.of(submission));
            when(records.findAvailableDocuments(eq(TENANT), eq(SOURCE), anyList())).thenReturn(List.of(
                    new AvailableDocumentSnapshot(STANDARD_DOC, SUB, "d1", "loan-agreement.pdf", NOW, NOW, null, 12,
                            false, SensitivityLevel.STANDARD),
                    new AvailableDocumentSnapshot(DOC, SUB, "d2", "medical-report.pdf", NOW.minusSeconds(5),
                            NOW.minusSeconds(5), null, 7, false, ownLevel)));
            Tenant tenant = mock(Tenant.class);
            when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
            when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
            IngestionSource source = mock(IngestionSource.class);
            when(source.sourceId()).thenReturn(SOURCE);
            when(sources.findById(TENANT, SOURCE)).thenReturn(Optional.of(source));
            when(tags.add(any(), any(), any(), any(), any())).thenReturn(
                    new DocumentTagRepository.DocumentTag(UUID.randomUUID(), DOC, "t", "x", NOW));
            when(comments.add(any(), any(), any(), any(), any())).thenReturn(
                    new DocumentCommentRepository.DocumentComment(UUID.randomUUID(), DOC, "c", "x", NOW));

            viewer = new SecurePdfViewerController(security, authorization, new DocumentViewerService(documents,
                    mock(StorageVolumeRepository.class), List.of(), mock(StreamingCryptoPort.class), audit,
                    viewings(), guard, com.sdv.platform.application.pdf.PdfWork.defaults(), (t, s) -> 2000));
            download = new ControlledDownloadController(security, authorization, new ControlledDownloadService(documents,
                    mock(StorageVolumeRepository.class), List.of(), mock(StreamingCryptoPort.class), audit, guard));
            actions = new DocumentActionController(security, authorization, new DocumentActionService(documents, tags,
                    comments, mock(BusinessActivityQueryPort.class), audit, guard));
            // Past the level check, the redaction looks up the tenant; this world's redaction has none, so an
            // allowed redaction stops there (task 0038: SDV-AUT-009, was "Tenant not found.") before anything is changed.
            redaction = new CorrectiveRedactionController(security, authorization, new CorrectiveRedactionService(
                    mock(TenantRepository.class), sources, documents, mock(DocumentRedactionRepository.class),
                    mock(StorageVolumeRepository.class), mock(StoragePlacementService.class), List.of(),
                    mock(StreamingCryptoPort.class), mock(PdfSanitizingRedactor.class), audit,
                    mock(PlatformTransactionManager.class), guard, com.sdv.platform.application.pdf.PdfWork.defaults()));
            business = new BusinessExperienceController(security, authorization, new BusinessExperienceService(
                    tenants, sources, definitions, records, audit));
        }

        /** Task 0063: the document was opened; what is tested here is the level. */
        private static final String VIEWING = UUID.randomUUID().toString();

        private static PageViewAuditPort viewings() {
            PageViewAuditPort port = mock(PageViewAuditPort.class);
            when(port.isViewing(any(), any(), any(), any(), any())).thenReturn(true);
            return port;
        }

        Object call(Function function) throws Exception {
            return switch (function) {
                case OPEN -> viewer.metadata(DOC);
                case PAGE_IMAGE -> viewer.page(DOC, 1, 1.0, VIEWING);
                case THUMBNAILS -> viewer.previews(DOC, 1, 12, VIEWING);
                case TEXT_SEARCH -> viewer.search(DOC, "loan", VIEWING);
                case PRINT -> { actions.print(DOC); yield null; }
                case DOWNLOAD -> download.download(DOC);
                case REDACT -> redaction.redact(DOC, new CorrectiveRedactionController.RedactionRequest("Wrong account number",
                        true, List.of(new CorrectiveRedactionController.RegionRequest(1, 0.1, 0.1, 0.2, 0.2))));
                case TAGS -> actions.tags(DOC);
                case ADD_TAG -> actions.addTag(DOC, new DocumentActionController.TagRequest("checked"));
                case COMMENTS -> actions.comments(DOC);
                case ADD_COMMENT -> actions.addComment(DOC, new DocumentActionController.CommentRequest("seen"));
                case ACTIVITY -> actions.activity(DOC, null, null, null, null, false, 100);
            };
        }

        List<AuditEvent> refusals() {
            ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
            verify(audit, atLeast(0)).append(events.capture());
            return events.getAllValues().stream()
                    .filter(e -> DocumentAccessGuard.REFUSED_ACTION.equals(e.action())).toList();
        }

        List<AuditEvent> events(String action) {
            ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
            verify(audit, atLeast(0)).append(events.capture());
            return events.getAllValues().stream().filter(e -> action.equals(e.action())).toList();
        }
    }

    private static Document document(UUID id, SensitivityLevel level) {
        return new Document(id, TENANT, SUB, SOURCE, "d2", null, "medical-report.pdf", "application/pdf", "sha",
                1024L, DocumentStatus.AVAILABLE, 365, NOW, null, NOW, 1, null, null, level);
    }

    private static void signIn(SdvRole role) {
        SdvPrincipal principal = new SdvPrincipal(TENANT, "person-" + role.name().toLowerCase(), Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static Outcome outcomeOf(Throwable thrown) {
        if (thrown == null) return Outcome.ALLOWED;
        if (thrown instanceof AccessDeniedException) return Outcome.NO_ROLE;
        if (thrown instanceof CodedRefusal.Forbidden coded) {
            if (coded.errorCode() == SdvErrorCode.SEN_008) return Outcome.LOCKED;
            if (coded.errorCode() == SdvErrorCode.SEN_009) return Outcome.APPROVAL;
        }
        // Allowed past every check: the mocked storage, or the redaction's missing tenant, stops it afterwards.
        if (thrown instanceof DocumentViewerUnavailableException
                || thrown instanceof DocumentDownloadUnavailableException
                || (thrown instanceof CodedRefusal.Forbidden tenant && tenant.errorCode() == SdvErrorCode.AUT_009)) {
            return Outcome.ALLOWED;
        }
        throw new AssertionError("unexpected " + thrown, thrown);
    }

    @Test
    void everyCellOfSection31ForEveryRoleAndLevel() {
        List<String> wrong = new ArrayList<>();
        int cells = 0;
        for (SdvRole role : ROLES) {
            for (SensitivityLevel level : SensitivityLevel.values()) {
                for (Function function : Function.values()) {
                    for (boolean fromRecord : new boolean[]{false, true}) {
                        cells++;
                        World world = fromRecord ? new World(SensitivityLevel.STANDARD, level) : new World(level, null);
                        signIn(role);
                        Throwable thrown = null;
                        try {
                            world.call(function);
                        } catch (Throwable t) {
                            thrown = t;
                        }
                        Outcome actual = outcomeOf(thrown);
                        Outcome want = expected(role, level, function);
                        String cell = role + " / " + level + (fromRecord ? " (record level)" : "") + " / " + function;
                        if (actual != want) {
                            wrong.add(cell + ": expected " + want + " but was " + actual);
                            continue;
                        }
                        List<AuditEvent> refusals = world.refusals();
                        if (want == Outcome.LOCKED || want == Outcome.APPROVAL) {
                            checkRefusal(cell, refusals, role, level, function, want, thrown, wrong);
                            verify(world.documents, never()).findStorageRef(any(), any());
                        } else if (!refusals.isEmpty()) {
                            wrong.add(cell + ": a refusal was audited for an allowed request");
                        }
                        if (want == Outcome.ALLOWED && function == Function.PRINT) {
                            List<AuditEvent> printed = world.events("DOCUMENT_PRINTED");
                            if (printed.size() != 1 || !printed.getFirst().safeDetailsJson()
                                    .contains("\"sensitivityLevel\":\"" + level.name() + "\"")) {
                                wrong.add(cell + ": the print event doesn't name the level");
                            }
                        }
                    }
                }
            }
        }
        assertEquals(6 * 3 * Function.values().length * 2, cells);
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    private static void checkRefusal(String cell, List<AuditEvent> refusals, SdvRole role, SensitivityLevel level,
                                     Function function, Outcome want, Throwable thrown, List<String> wrong) {
        if (refusals.size() != 1) {
            wrong.add(cell + ": expected one refusal audit, got " + refusals.size());
            return;
        }
        AuditEvent event = refusals.getFirst();
        String code = want == Outcome.LOCKED ? "SDV-SEN-008" : "SDV-SEN-009";
        if (event.outcome() != AuditOutcome.DENIED
                || !event.actorId().equals("person-" + role.name().toLowerCase())
                || !"DOCUMENT".equals(event.targetType())
                || !DOC.toString().equals(event.targetId())
                || event.occurredAt() == null
                || !event.safeDetailsJson().contains("\"level\":\"" + level.name() + "\"")
                || !event.safeDetailsJson().contains("\"code\":\"" + code + "\"")) {
            wrong.add(cell + ": refusal audit is " + event);
        }
        String message = thrown.getMessage();
        String allowedMessage = want == Outcome.LOCKED ? DocumentAccessGuard.RESTRICTED_MESSAGE
                : DocumentAccessGuard.APPROVAL_NEEDED_MESSAGE;
        if (!allowedMessage.equals(message) || message.contains("medical") || message.contains(DOC.toString())) {
            wrong.add(cell + ": the refusal message says more than it should: " + message);
        }
        if (function == null) wrong.add(cell + ": no function");
    }

    @Test
    void searchShowsEveryRecordToRecordRolesWithTheRightRestrictedLevelAndUnchangedCounts() {
        List<String> wrong = new ArrayList<>();
        for (SdvRole role : ROLES) {
            for (SensitivityLevel level : SensitivityLevel.values()) {
                for (boolean fromRecord : new boolean[]{false, true}) {
                    World world = fromRecord ? new World(SensitivityLevel.STANDARD, level) : new World(level, null);
                    signIn(role);
                    String cell = role + " / " + level + (fromRecord ? " (record level)" : "");
                    boolean searches = role == SdvRole.BUSINESS_USER || role == SdvRole.DOCUMENT_STEWARD
                            || role == SdvRole.RESTRICTED_STEWARD;
                    try {
                        var found = world.business.search(new BusinessExperienceController.SearchRequest(SOURCE, Map.of(),
                                null, NOW.minusSeconds(3600), null, null, null, null, 1, 25, null));
                        if (!searches) { wrong.add(cell + ": search allowed"); continue; }
                        assertEquals(1, found.records().size(), cell);
                        var summary = found.records().getFirst();
                        boolean canOpen = expected(role, level, Function.OPEN) == Outcome.ALLOWED;
                        String want = canOpen ? null : level.name();
                        // With a record level, the Standard document is raised too; the highest locked level is the same.
                        if (!java.util.Objects.equals(want, summary.restrictedLevel())) {
                            wrong.add(cell + ": restrictedLevel " + summary.restrictedLevel() + ", expected " + want);
                        }
                        if (summary.availableDocumentCount() != 2) wrong.add(cell + ": count changed");
                        // The record's search fields stay visible.
                        if (summary.identifiers().isEmpty()) wrong.add(cell + ": identifiers hidden");
                    } catch (AccessDeniedException ex) {
                        if (searches) wrong.add(cell + ": search refused");
                    }
                }
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void aLockedDocumentCarriesOnlyItsReferenceAndLevelInTheJson() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        for (SensitivityLevel level : List.of(SensitivityLevel.CONFIDENTIAL, SensitivityLevel.HIGHLY_CONFIDENTIAL)) {
            World world = new World(level, null);
            signIn(SdvRole.BUSINESS_USER);
            var found = world.business.search(new BusinessExperienceController.SearchRequest(SOURCE, Map.of(),
                    null, NOW.minusSeconds(3600), null, null, null, null, 1, 25, null));
            String recordRef = found.records().getFirst().recordRef();
            JsonNode view = json.readTree(json.writeValueAsString(world.business.record(recordRef)));

            assertEquals(1, view.get("documents").size(), "only the Standard document is listed in full");
            assertEquals("loan-agreement.pdf", view.get("documents").get(0).get("originalFilename").asText());
            JsonNode locked = view.get("lockedDocuments");
            assertEquals(1, locked.size());
            Set<String> keys = new HashSet<>();
            locked.get(0).fieldNames().forEachRemaining(keys::add);
            assertEquals(Set.of("documentId", "effectiveSensitivityLevel"), keys, "nothing but a reference and its level");
            assertEquals(DOC.toString(), locked.get(0).get("documentId").asText());
            assertEquals(level.name(), locked.get(0).get("effectiveSensitivityLevel").asText());
            assertEquals(level.name(), view.get("restrictedLevel").asText());
            String text = view.toString();
            for (String forbidden : List.of("medical-report.pdf", "\"d2\"", "\"pageCount\":7")) {
                assertFalse(text.contains(forbidden), level + ": the record JSON contains " + forbidden);
            }
            // The record's own fields stay visible.
            assertEquals("300000000076", view.get("identifiers").get(0).get("value").asText());
        }
    }

    @Test
    void theStewardSeesConfidentialInFullAndHighlyConfidentialLockedEvenWhenTheRecordRaisesIt() throws Exception {
        World world = new World(SensitivityLevel.STANDARD, SensitivityLevel.HIGHLY_CONFIDENTIAL);
        signIn(SdvRole.DOCUMENT_STEWARD);
        var found = world.business.search(new BusinessExperienceController.SearchRequest(SOURCE, Map.of(),
                null, NOW.minusSeconds(3600), null, null, null, null, 1, 25, null));
        var view = world.business.record(found.records().getFirst().recordRef());
        assertTrue(view.documents().isEmpty(), "both documents are Highly Confidential through the record level");
        assertEquals(2, view.lockedDocuments().size());
        assertEquals("HIGHLY_CONFIDENTIAL", view.restrictedLevel());

        World confidential = new World(SensitivityLevel.CONFIDENTIAL, null);
        signIn(SdvRole.DOCUMENT_STEWARD);
        var again = confidential.business.search(new BusinessExperienceController.SearchRequest(SOURCE, Map.of(),
                null, NOW.minusSeconds(3600), null, null, null, null, 1, 25, null));
        assertNull(again.records().getFirst().restrictedLevel());
        assertEquals(2, confidential.business.record(again.records().getFirst().recordRef()).documents().size());
    }

    @Test
    void aMissingDocumentIsStillNotFoundAndARestrictedOneRevealsNotEvenItsStatus() {
        World world = new World(SensitivityLevel.HIGHLY_CONFIDENTIAL, null);
        Document processing = new Document(DOC, TENANT, SUB, SOURCE, "d2", null, "medical-report.pdf", "application/pdf",
                "sha", 1024L, DocumentStatus.PROCESSING, 365, NOW, null, null, 1, null, null, SensitivityLevel.HIGHLY_CONFIDENTIAL);
        when(world.documents.findDocumentByIdForTenant(TENANT, DOC)).thenReturn(Optional.of(processing));
        signIn(SdvRole.BUSINESS_USER);
        var refused = assertThrows(CodedRefusal.Forbidden.class, () -> world.viewer.metadata(DOC));
        assertEquals(SdvErrorCode.SEN_008, refused.errorCode(), "the level check comes before the status check");

        UUID unknown = UUID.randomUUID();
        assertThrows(java.util.NoSuchElementException.class, () -> world.viewer.metadata(unknown));
    }

    @Test
    void aRefusalStandsWhenTheAuditStoreIsDown() {
        World world = new World(SensitivityLevel.CONFIDENTIAL, null);
        doThrow(new IllegalStateException("audit down")).when(world.audit).append(any());
        signIn(SdvRole.BUSINESS_USER);
        var refused = assertThrows(CodedRefusal.Forbidden.class, () -> world.viewer.metadata(DOC));
        assertEquals(SdvErrorCode.SEN_008, refused.errorCode());
    }
}
