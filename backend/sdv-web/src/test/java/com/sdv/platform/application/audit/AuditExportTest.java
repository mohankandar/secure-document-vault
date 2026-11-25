package com.sdv.platform.application.audit;

import com.sdv.platform.domain.error.InternalCheckFailed;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.AuditQueryPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0011 Part B.6, owner's option (a): the server builds the CSV and records exactly what it exported. */
class AuditExportTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final Instant NOW = Instant.parse("2026-11-18T15:00:00Z");

    private final AuditQueryPort query = mock(AuditQueryPort.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final AuditQueryService service = new AuditQueryService(query, audit,
            // Task 0026: a real guard; with no record level every document below is Standard, so its name shows.
            new com.sdv.platform.application.access.DocumentAccessGuard(
                    com.sdv.platform.application.business.TestRecordRefs.grouped(), audit),
            Clock.fixed(NOW, ZoneOffset.UTC));

    static final UUID DOCUMENT = UUID.fromString("40000000-0000-0000-0000-000000000076");

    /** Task 0027: a document event names its document by ID, level, received date and pages (pages null: no document). */
    private static AuditQueryPort.Row row(String action, String actorId, String name, String details, String record, Integer pages, AuditOutcome outcome) {
        return new AuditQueryPort.Row(new AuditEvent(UUID.randomUUID(), TENANT, "USER", actorId, action, "DOCUMENT",
                DOCUMENT.toString(), Instant.parse("2026-11-18T12:00:00Z"), "corr", outcome, details, name), record,
                pages == null ? null : new com.sdv.platform.application.access.DocumentAccessGuard.DocumentLevelRef(DOCUMENT,
                        UUID.randomUUID(), UUID.randomUUID(), com.sdv.platform.domain.document.SensitivityLevel.STANDARD),
                pages == null ? null : Instant.parse("2026-11-14T09:30:00Z"), pages);
    }

    private static AuditQueryService.SearchRequest search(AuditOutcome outcome, String recordNumber) {
        return new AuditQueryService.SearchRequest(null, outcome, null, null, null, null, null,
                Instant.parse("2026-10-20T04:00:00Z"), null, null, null, recordNumber);
    }

    private static List<String> csvLines(byte[] csv) {
        String text = new String(csv, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("﻿"), "UTF-8 byte-order mark so spreadsheets read names");
        return List.of(text.substring(1).split("\r\n"));
    }

    @Test
    void theFileHasTheScreensSentencesAndTheExportIsRecordedWithItsFiltersAndRowCount() throws Exception {
        when(query.findRows(eq(TENANT), any(), anyInt())).thenReturn(List.of(
                row("DOCUMENT_DOWNLOADED", "local-document-steward", "Dana Steward", null, "300000000076", 9, AuditOutcome.SUCCESS),
                row("AUTHORIZATION_DENIED", "old-subject", null, "{\"capability\":\"DOWNLOAD_PDF\"}", "300000000076", null, AuditOutcome.DENIED)));

        var export = service.export(TENANT, "local-platform-operations-admin", new AuditQueryService.ExportRequest(
                search(null, " 300000000076 "), List.of("DOCUMENT_DOWNLOADED", "AUTHORIZATION_DENIED"),
                List.of("AUDIT_VIEWED", "BUSINESS_RECENT_RECORDS_LISTED")), "corr-1");

        assertEquals(2, export.rowCount());
        List<String> lines = csvLines(export.csv());
        assertEquals(List.of(
                "When (UTC),What happened,Person,Record,Result,Action code,Document ID,Level,Received (UTC),Pages",
                "\"2026-11-18T12:00:00Z\",\"Dana Steward downloaded a document\",\"Dana Steward\",\"300000000076\",\"Done\",\"DOCUMENT_DOWNLOADED\","
                        + "\"40000000-0000-0000-0000-000000000076\",\"STANDARD\",\"2026-11-14T09:30:00Z\",\"9\"",
                "\"2026-11-18T12:00:00Z\",\"old-subject tried to download a document without permission\",\"old-subject\",\"300000000076\",\"Refused\",\"AUTHORIZATION_DENIED\",\"\",\"\",\"\",\"\""),
                lines);

        // The query is the list's filters plus the activity group and the hidden routine codes, over the whole period.
        ArgumentCaptor<AuditQueryPort.Filter> filter = ArgumentCaptor.forClass(AuditQueryPort.Filter.class);
        verify(query).findRows(eq(TENANT), filter.capture(), eq(AuditQueryService.EXPORT_MAX_ROWS + 1));
        assertEquals(Set.of("DOCUMENT_DOWNLOADED", "AUTHORIZATION_DENIED"), filter.getValue().actions());
        assertEquals(Set.of("AUDIT_VIEWED", "BUSINESS_RECENT_RECORDS_LISTED"), filter.getValue().excludeActions());
        assertEquals("300000000076", filter.getValue().recordNumber());
        assertNull(filter.getValue().beforeOccurredAt());

        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        assertEquals("AUDIT_EXPORTED", event.getValue().action());
        assertEquals("local-platform-operations-admin", event.getValue().actorId());
        assertEquals(AuditOutcome.SUCCESS, event.getValue().outcome());
        assertEquals(NOW, event.getValue().occurredAt());
        assertEquals("corr-1", event.getValue().correlationId());
        JsonNode details = new ObjectMapper().readTree(event.getValue().safeDetailsJson());
        assertEquals(2, details.get("rowCount").asInt());
        assertEquals("CSV", details.get("format").asString());
        assertEquals("300000000076", details.at("/filters/recordNumber").asString());
        assertEquals("2026-10-20T04:00:00Z", details.at("/filters/from").asString());
        assertEquals("AUTHORIZATION_DENIED", details.at("/filters/actions/0").asString());
        assertEquals("AUDIT_VIEWED", details.at("/filters/excludeActions/0").asString());
        assertFalse(details.get("filters").has("to"), "filters not used are left out");
        verify(audit, never()).append(argThat(e -> "AUDIT_VIEWED".equals(e.action())));
    }

    @Test
    void moreThanTenThousandRowsIsRefusedInWordsAndNothingIsExportedOrRecorded() {
        List<AuditQueryPort.Row> many = new ArrayList<>();
        AuditQueryPort.Row one = row("DOCUMENT_VIEW_OPENED", "a", null, null, null, 3, AuditOutcome.SUCCESS);
        for (int i = 0; i < AuditQueryService.EXPORT_MAX_ROWS + 1; i++) many.add(one);
        when(query.findRows(eq(TENANT), any(), anyInt())).thenReturn(many);

        var ex = assertThrows(AuditQueryService.ExportTooLargeException.class, () -> service.export(TENANT, "ops",
                new AuditQueryService.ExportRequest(search(null, null), null, null), "c"));
        assertTrue(ex.getMessage().startsWith("This list has more than 10,000 events"), ex.getMessage());
        verifyNoInteractions(audit);
    }

    @Test
    void exactlyTenThousandRowsIsExported() {
        List<AuditQueryPort.Row> many = new ArrayList<>();
        AuditQueryPort.Row one = row("DOCUMENT_VIEW_OPENED", "a", null, null, null, 3, AuditOutcome.SUCCESS);
        for (int i = 0; i < AuditQueryService.EXPORT_MAX_ROWS; i++) many.add(one);
        when(query.findRows(eq(TENANT), any(), anyInt())).thenReturn(many);
        assertEquals(10_000, service.export(TENANT, "ops", new AuditQueryService.ExportRequest(search(null, null), null, null), "c").rowCount());
        verify(audit).append(argThat(e -> e.action().equals("AUDIT_EXPORTED") && e.safeDetailsJson().contains("\"rowCount\":10000")));
    }

    @Test
    void unclearRequestsAreRefusedBeforeAnythingIsRead() {
        var bad = List.of(
                new AuditQueryService.ExportRequest(search(null, null), List.of("DOCUMENT_VIEWED'); drop table x;--"), null),
                new AuditQueryService.ExportRequest(search(null, null), List.of(" "), null),
                new AuditQueryService.ExportRequest(search(null, null), null, List.of("lower_case")),
                new AuditQueryService.ExportRequest(new AuditQueryService.SearchRequest(null, null, null, null, null, null, null,
                        Instant.parse("2026-11-18T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), null, null, null), null, null),
                new AuditQueryService.ExportRequest(new AuditQueryService.SearchRequest(null, null, null, null, null, null, null,
                        null, null, null, "c29tZQ", null), null, null));
        for (var request : bad) {
            assertThrows(IllegalArgumentException.class, () -> service.export(TENANT, "ops", request, "c"), request.toString());
        }
        assertThrows(InternalCheckFailed.class, () -> service.export(TENANT, "ops", null, "c"), "task 0037: internal, SDV-SYS-016");
        assertThrows(InternalCheckFailed.class, () -> service.export(null, "ops", new AuditQueryService.ExportRequest(null, null, null), "c"));
        verifyNoInteractions(query, audit);
    }

    @Test
    void ifTheExportCannotBeRecordedNothingIsHandedBack() {
        when(query.findRows(eq(TENANT), any(), anyInt())).thenReturn(List.of());
        doThrow(new IllegalStateException("database down")).when(audit).append(any());
        assertThrows(IllegalStateException.class, () -> service.export(TENANT, "ops",
                new AuditQueryService.ExportRequest(search(AuditOutcome.DENIED, null), null, null), "c"));
    }

    @Test
    void springUsesTheMarkedConstructor() {
        // Two constructors (one takes a clock for tests): without @Autowired on the public one the app doesn't start.
        var marked = java.util.Arrays.stream(AuditQueryService.class.getDeclaredConstructors())
                .filter(c -> c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)).toList();
        assertEquals(1, marked.size());
        assertEquals(3, marked.get(0).getParameterCount()); // task 0026 added the document access guard
    }

    @Test
    void cellsAreQuotedAndFormulasAreDisarmed() {
        assertEquals("\"plain\"", AuditCsv.cell("plain"));
        assertEquals("\"say \"\"hi\"\"\"", AuditCsv.cell("say \"hi\""));
        assertEquals("\"'=HYPERLINK(1)\"", AuditCsv.cell("=HYPERLINK(1)"));
        assertEquals("\"'+1\"", AuditCsv.cell("+1"));
        assertEquals("\"'-1\"", AuditCsv.cell("-1"));
        assertEquals("\"'@x\"", AuditCsv.cell("@x"));
        assertEquals("\"\"", AuditCsv.cell(null));
        assertEquals("\"a, b\nc\"", AuditCsv.cell("a, b\nc"));
    }

    @Test
    void sentencesMatchTheScreensWordsIncludingFallbacks() {
        java.util.function.BiFunction<String, String, String> s = (action, details) -> AuditSentences.sentence(
                AuditQueryService.AuditEntry.from(row(action, "subj", "Ari Admin", details, null, null, AuditOutcome.SUCCESS)));
        assertEquals("Ari Admin opened a document", s.apply("DOCUMENT_VIEW_OPENED", null));
        assertEquals("Ari Admin loaded page 3 of a document", s.apply("DOCUMENT_PAGE_VIEWED", "{\"page\":3}"));
        assertEquals("Ari Admin sent a document again; Vero kept the copy it already had", s.apply("DOCUMENT_DUPLICATE_RECEIVED", null));
        // Task 0032.
        assertEquals("Ari Admin sent an uncorrected copy of a corrected document; nothing was stored", s.apply("DOCUMENT_UNCORRECTED_COPY_RECEIVED", null));
        assertEquals("Ari Admin sent a delivery that Vero refused (SDV-SEN-001)", s.apply("INTAKE_REFUSED", "{\"code\":\"SDV-SEN-001\"}"));
        assertEquals("Ari Admin sent a delivery that Vero refused", s.apply("INTAKE_REFUSED", null));
        assertEquals("Ari Admin exported the Daily intake report (1 line)", s.apply("INTAKE_REPORT_EXPORTED", "{\"rowCount\":1}"));
        assertEquals("Ari Admin exported the Daily intake report (9 lines)", s.apply("INTAKE_REPORT_EXPORTED", "{\"rowCount\":9}"));
        // Task 0033.
        assertEquals("Ari Admin exported the Sensitivity report (30 lines)", s.apply("SENSITIVITY_REPORT_EXPORTED", "{\"rowCount\":30}"));
        assertEquals("Ari Admin exported the Sensitivity report", s.apply("SENSITIVITY_REPORT_EXPORTED", null));
        assertEquals("Ari Admin exported the Storage and retention report (40 lines)", s.apply("STORAGE_REPORT_EXPORTED", "{\"rowCount\":40}"));
        assertEquals("Ari Admin exported the Activity report (12 lines)", s.apply("ACTIVITY_REPORT_EXPORTED", "{\"rowCount\":12}"));
        assertEquals("Ari Admin marked a notice of a corrected document sent again as read", s.apply("STEWARD_NOTICE_READ", "{\"markedCount\":1}"));
        assertEquals("Ari Admin marked 3 notices of corrected documents sent again as read", s.apply("STEWARD_NOTICE_READ", "{\"markedCount\":3}"));
        assertEquals("Ari Admin tried to see the reports without permission", s.apply("AUTHORIZATION_DENIED", "{\"capability\":\"VIEW_INTAKE_REPORTS\"}"));
        assertEquals("Ari Admin changed how long to keep new documents to 7 years", s.apply("RETENTION_POLICY_UPDATED", "{\"defaultRetentionDays\":2555}"));
        assertEquals("Ari Admin changed how long to keep new documents to 1,000 days", s.apply("RETENTION_POLICY_UPDATED", "{\"defaultRetentionDays\":1000}"));
        assertEquals("Ari Admin changed how long to keep new documents: keep them forever", s.apply("RETENTION_POLICY_UPDATED", "{\"indefinite\":true}"));
        assertEquals("Ari Admin delivered 1 document", s.apply("SUBMISSION_CREATED", "{\"expectedDocumentCount\":1}"));
        assertEquals("Ari Admin tried to do something without permission", s.apply("AUTHORIZATION_DENIED", "not json"));
        assertEquals("Ari Admin exported the audit (1 event)", s.apply("AUDIT_EXPORTED", "{\"rowCount\":1}"));
        assertEquals("Ari Admin checked a folder for a new volume; Vero couldn't write there", s.apply("STORAGE_FOLDER_CHECKED", "{\"writable\":false}"));
        assertEquals("Ari Admin · Something new happened", s.apply("SOMETHING_NEW_HAPPENED", null));
        assertEquals("Ari Admin put a document on legal hold", s.apply("LEGAL_HOLD_PLACED", "{\"holdId\":\"h\"}"));
        assertEquals("Ari Admin released the legal hold on a document", s.apply("LEGAL_HOLD_RELEASED", null));
        AuditQueryService.AuditEntry system = AuditQueryService.AuditEntry.from(new AuditQueryPort.Row(new AuditEvent(UUID.randomUUID(), TENANT,
                "SYSTEM", "retention-sweep", "DOCUMENT_RETENTION_DELETED", "DOCUMENT", "x", NOW, null, AuditOutcome.SUCCESS, null), null));
        assertEquals("Vero deleted a document for good, as the keeping rule says", AuditSentences.sentence(system));
        assertEquals("Vero", AuditSentences.actorName(system));
    }
}
