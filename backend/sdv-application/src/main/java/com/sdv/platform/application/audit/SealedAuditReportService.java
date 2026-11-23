package com.sdv.platform.application.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.AuditReportRepository;
import com.sdv.platform.application.port.AuditReportRepository.StoredReport;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Task 0050: the sealed audit report. There is no auditor role: a Business Admin or IT Admin generates the report for
 * a period (whole UTC days) and the Audit list's filters, and hands it over.
 * <ul>
 *   <li>The CSV (for machines) starts with its stamp (report reference, who, when, the period, the filters, the number
 *   of events), then the events as the audit export writes them: document IDs and record identifiers, never content,
 *   file names, titles or reasons.</li>
 *   <li>Its SHA-256 fingerprint is over the whole CSV file. The PDF (for people) is made from that same CSV and shows the
 *   stamp and the fingerprint.</li>
 *   <li>Every report is recorded in the register (with its CSV, so later copies are exactly it) and its generation is
 *   audited (AUDIT_REPORT_GENERATED), in one transaction: if either can't be written, nothing is handed out.</li>
 *   <li>Verify: a fingerprint (the screen computes it from the file in the browser) matches a registered report or not.
 *   A changed file has another fingerprint, so it fails.</li>
 * </ul>
 */
@Service
public class SealedAuditReportService {
    public static final String GENERATED_ACTION = "AUDIT_REPORT_GENERATED";
    public static final String DOWNLOADED_ACTION = "AUDIT_REPORT_DOWNLOADED";
    public static final String VERIFIED_ACTION = "AUDIT_REPORT_VERIFIED";
    static final String TARGET_TYPE = "AUDIT_REPORT";
    static final String TITLE = "Vero sealed audit report";
    /** The register list shows at most this many, newest first. */
    static final int LIST_LIMIT = 200;
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AuditQueryService audits;
    private final AuditReportRepository register;
    private final AuditEventPort audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public SealedAuditReportService(AuditQueryService audits, AuditReportRepository register, AuditEventPort audit,
                                    PlatformTransactionManager transactionManager) {
        this(audits, register, audit, transactionManager, Clock.systemUTC());
    }

    SealedAuditReportService(AuditQueryService audits, AuditReportRepository register, AuditEventPort audit,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.audits = Objects.requireNonNull(audits, "audits");
        this.register = Objects.requireNonNull(register, "register");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The person generating, downloading or verifying (a Business Admin or IT Admin; the web layer checks). */
    public record Actor(UUID tenantId, String actorId, String displayName, String correlationId) { }

    /** from and to: days (YYYY-MM-DD, UTC), both included. The filters are the Audit list's; each may be left out. */
    public record ReportRequest(String from, String to, String action, String outcome, String recordNumber,
                                String sensitivityLevel) { }

    /** A report as the register lists it: never its rows. filters: the filters used, by name. */
    public record Report(UUID reportId, String generatedBy, Instant generatedAt, Instant from, Instant to,
                         Map<String, String> filters, int rowCount, String fingerprint) { }

    /** A file to hand over: the bytes, its name, and the report it is a copy of. */
    public record Download(byte[] bytes, String filename, Report report) { }

    /** matches: a registered report has this fingerprint; report: the newest such report, null when none. */
    public record Verification(boolean matches, String fingerprint, Report report) { }

    public Report generate(Actor actor, ReportRequest request) {
        requireActor(actor);
        if (request == null) throw new CodedRefusal.BadRequest(SdvErrorCode.AUD_011, "Choose the first and last day of the report.");
        Instant from = day(request.from()).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = day(request.to()).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        if (!from.isBefore(to)) throw new CodedRefusal.BadRequest(SdvErrorCode.AUD_011, "The first day comes after the last day.");
        Map<String, String> filters = filters(request);

        List<AuditQueryService.AuditEntry> entries;
        try {
            entries = audits.sealedEntries(actor.tenantId(), new AuditQueryService.SearchRequest(
                    filters.get("action"), outcome(filters.get("outcome")), null, null, null, null, null,
                    from, to.minus(1, ChronoUnit.MICROS), null, null, filters.get("recordNumber"), filters.get("sensitivityLevel")));
        } catch (AuditQueryService.ExportTooLargeException ex) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.AUD_013, "The report would hold more than 10,000 events. Choose fewer days or more filters.");
        } catch (IllegalArgumentException ex) {
            // The list's own checks (a coded refusal such as SDV-SEN-019 included): the report names its own code.
            throw new CodedRefusal.BadRequest(SdvErrorCode.AUD_012, "A filter of the report is not valid.");
        }

        UUID reportId = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String who = name(actor);
        byte[] csv = SealedAuditCsv.write(new SealedAuditCsv.Stamp(reportId, who, now, from, to, filters, entries.size()), entries);
        String fingerprint = sha256(csv);
        StoredReport stored = new StoredReport(reportId, actor.tenantId(), actor.actorId(), actor.displayName(), now, from, to,
                json(filters), entries.size(), fingerprint, csv);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reportId", reportId.toString());
        details.put("from", from.toString());
        details.put("to", to.toString());
        details.put("filters", filters);
        details.put("rowCount", entries.size());
        details.put("fingerprint", fingerprint);
        try {
            transactions.executeWithoutResult(status -> {
                register.insert(stored);
                append(actor, GENERATED_ACTION, reportId, now, details);
            });
        } catch (RuntimeException ex) {
            if (ex instanceof CodedRefusal) throw ex;
            throw InternalCheckFailed.auditNotWritten("The report could not be recorded, so it was not handed out.", ex);
        }
        return report(stored);
    }

    public List<Report> list(Actor actor) {
        requireActor(actor);
        return register.list(actor.tenantId(), LIST_LIMIT).stream().map(this::report).toList();
    }

    /** The sealed CSV exactly as it was generated. The copy is audited. */
    public Download csv(Actor actor, String reportReference) {
        StoredReport stored = find(actor, reportReference);
        appendDownload(actor, stored, "CSV");
        return new Download(stored.csv(), filename(stored, "csv"), report(stored));
    }

    /** The PDF, made from the sealed CSV, with the stamp and the CSV's fingerprint. The copy is audited. */
    public Download pdf(Actor actor, String reportReference) {
        StoredReport stored = find(actor, reportReference);
        byte[] pdf = SealedAuditPdf.write(report(stored), stored.csv());
        appendDownload(actor, stored, "PDF");
        return new Download(pdf, filename(stored, "pdf"), report(stored));
    }

    /** Does this fingerprint belong to a report generated in this entity? Audited either way. */
    public Verification verify(Actor actor, String fingerprint) {
        requireActor(actor);
        String value = fingerprint == null ? "" : fingerprint.strip().toLowerCase(Locale.ROOT);
        if (!FINGERPRINT.matcher(value).matches()) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.AUD_015, "A fingerprint is 64 characters, 0 to 9 and a to f.");
        }
        List<StoredReport> found = register.findByFingerprint(actor.tenantId(), value);
        Report match = found.isEmpty() ? null : report(found.get(0));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("fingerprint", value);
        details.put("matches", match != null);
        if (match != null) details.put("reportId", match.reportId().toString());
        append(actor, VERIFIED_ACTION, match == null ? null : match.reportId(), clock.instant(), details);
        return new Verification(match != null, value, match);
    }

    // ------------------------------------------------------------------ inside

    private StoredReport find(Actor actor, String reference) {
        requireActor(actor);
        UUID id;
        try {
            id = UUID.fromString(reference == null ? "" : reference.strip());
        } catch (IllegalArgumentException ex) {
            throw new CodedRefusal.NotFound(SdvErrorCode.AUD_014, "No audit report has this reference.");
        }
        return register.find(actor.tenantId(), id)
                .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.AUD_014, "No audit report has this reference."));
    }

    private void appendDownload(Actor actor, StoredReport stored, String format) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reportId", stored.reportId().toString());
        details.put("format", format);
        details.put("fingerprint", stored.fingerprint());
        append(actor, DOWNLOADED_ACTION, stored.reportId(), clock.instant(), details);
    }

    private void append(Actor actor, String action, UUID reportId, Instant at, Map<String, Object> details) {
        audit.append(new AuditEvent(UUID.randomUUID(), actor.tenantId(), "USER", actor.actorId(), action, TARGET_TYPE,
                reportId == null ? actor.tenantId().toString() : reportId.toString(), at, actor.correlationId(),
                AuditOutcome.SUCCESS, json(details), actor.displayName()));
    }

    private Report report(StoredReport s) {
        return new Report(s.reportId(), s.generatedByName() != null && !s.generatedByName().isBlank() ? s.generatedByName() : s.generatedBy(),
                s.generatedAt(), s.rangeFrom(), s.rangeTo(), readFilters(s.filtersJson()), s.rowCount(), s.fingerprint());
    }

    private static String filename(StoredReport s, String extension) {
        LocalDate first = s.rangeFrom().atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate last = s.rangeTo().atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        return "vero-audit-report-" + first + (last.equals(first) ? "" : "-to-" + last) + "-"
                + s.reportId().toString().substring(0, 8) + "." + extension;
    }

    private static void requireActor(Actor actor) {
        if (actor == null || actor.tenantId() == null || actor.actorId() == null || actor.actorId().isBlank()) {
            throw new InternalCheckFailed("The person asking for the audit report is not known.");
        }
    }

    private static String name(Actor actor) {
        return actor.displayName() != null && !actor.displayName().isBlank() ? actor.displayName().strip() : actor.actorId();
    }

    private static LocalDate day(String value) {
        String v = value == null ? "" : value.strip();
        if (!DAY.matcher(v).matches()) throw new CodedRefusal.BadRequest(SdvErrorCode.AUD_011, "Choose the first and last day of the report.");
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException ex) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.AUD_011, "Choose the first and last day of the report.");
        }
    }

    /** The filters by name, in a fixed order, blanks left out; each one is checked as the Audit list checks it. */
    private static Map<String, String> filters(ReportRequest r) {
        Map<String, String> f = new LinkedHashMap<>();
        put(f, "action", r.action(), 120);
        put(f, "outcome", r.outcome(), 20);
        put(f, "recordNumber", r.recordNumber(), 300);
        put(f, "sensitivityLevel", r.sensitivityLevel(), 40);
        if (f.containsKey("action") && !f.get("action").matches("[A-Z][A-Z0-9_]{0,119}")) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.AUD_012, "A filter of the report is not valid.");
        }
        if (f.containsKey("outcome")) outcome(f.get("outcome"));
        return f;
    }

    private static void put(Map<String, String> map, String key, String value, int max) {
        if (value == null || value.isBlank()) return;
        String v = value.strip();
        if (v.length() > max) throw new CodedRefusal.BadRequest(SdvErrorCode.AUD_012, "A filter of the report is not valid.");
        map.put(key, v);
    }

    private static AuditOutcome outcome(String value) {
        if (value == null) return null;
        for (AuditOutcome o : AuditOutcome.values()) if (o.name().equals(value)) return o;
        throw new CodedRefusal.BadRequest(SdvErrorCode.AUD_012, "A filter of the report is not valid.");
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw InternalCheckFailed.auditNotWritten("The report could not be recorded, so it was not handed out.", ex);
        }
    }

    private static Map<String, String> readFilters(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return JSON.readValue(json, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (JsonProcessingException ex) {
            return Map.of();
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available.", ex);
        }
    }

    static String text(byte[] csv) {
        return new String(csv, StandardCharsets.UTF_8);
    }
}
