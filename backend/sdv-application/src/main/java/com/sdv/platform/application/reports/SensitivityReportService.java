package com.sdv.platform.application.reports;

import com.sdv.platform.domain.error.InternalCheckFailed;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.audit.AuditCsv;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IntakeReportRepository;
import com.sdv.platform.application.port.IntakeReportRepository.SendingSystem;
import com.sdv.platform.application.port.SensitivityReportRepository;
import com.sdv.platform.application.port.SensitivityReportRepository.AttemptCount;
import com.sdv.platform.application.port.SensitivityReportRepository.DocumentLevels;
import com.sdv.platform.application.port.SensitivityReportRepository.LevelChanges;
import com.sdv.platform.application.port.SensitivityReportRepository.RequestCount;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 0033: the Sensitivity report of the Business Admin's Reports tab, and its CSV.
 * <p>
 * <b>Counts only.</b> No name, title, file name, metadata value or content: "who and when" stays in the audit trail.
 * <p>
 * <b>Days are the tenant's</b> (its time zone, as the Daily intake report: {@link TenantZone}). The period is from the
 * start of its first day to the start of the day after its last; left out, it is this month so far.
 * <ol>
 *   <li><b>Documents by level</b>: every document stored now, at its effective level (the higher of its own and its
 *       record's), whatever the period; how many records (contracts) have a record level; how many stored documents
 *       are on legal hold.</li>
 *   <li><b>Requests</b> made in the period, by kind (download, print, lower one document, lower a whole record) and by
 *       what became of them: approved (also used, or approved and run out), declined, expired undecided, waiting.</li>
 *   <li><b>Level changes</b> in the period: raised by a Steward; set or raised by a sending system (a delivery setting a
 *       record's first level above Standard or raising a record level, or a duplicate raising a document level: task
 *       0033, part 2); lowered after a Business Admin's approval.</li>
 *   <li><b>Attempts to open restricted documents</b> in the period (refused with SDV-SEN-008 when opening the
 *       document; pages, previews, text search and other attempts are not counted: task 0033, part 2), by role, with
 *       how many people. Refusals recorded before task 0033 name no role and are counted as "Role not recorded".</li>
 * </ol>
 */
@Service
public class SensitivityReportService {
    public static final int MAX_DAYS = 366;
    public static final String EXPORT_ACTION = "SENSITIVITY_REPORT_EXPORTED";
    /** The request kinds, in the board's order. */
    public static final List<String> KINDS = List.of("DOWNLOAD", "PRINT", "LOWER_LEVEL", "LOWER_RECORD_LEVEL");
    static final String CSV_HEADER = "Section,Line,Measure,Count,From,To,Time zone,Sending system";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern IDENTIFIER_LABEL = Pattern.compile("^(.+?)\\s+(number|no\\.?|id|reference|ref)$",
            Pattern.CASE_INSENSITIVE);

    private final SensitivityReportRepository sensitivity;
    private final IntakeReportRepository tenants;
    private final AuditEventPort audit;
    private final ZoneId installDefault;
    private final Clock clock;

    @Autowired
    public SensitivityReportService(SensitivityReportRepository sensitivity, IntakeReportRepository tenants,
                                    AuditEventPort audit,
                                    @Value("${sdv.tenant-defaults.time-zone:UTC}") String installDefaultZone) {
        this(sensitivity, tenants, audit, installDefaultZone, Clock.systemUTC());
    }

    SensitivityReportService(SensitivityReportRepository sensitivity, IntakeReportRepository tenants,
                             AuditEventPort audit, String installDefaultZone, Clock clock) {
        this.sensitivity = Objects.requireNonNull(sensitivity, "sensitivity");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.installDefault = IntakeReportService.installZone(installDefaultZone);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------ the report

    public SensitivityReport report(UUID tenantId, String from, String to, String sourceId) {
        Scope scope = scope(tenantId, from, to, sourceId);
        return build(tenantId, scope);
    }

    /** One line per count, every block; the export is audited. */
    public Export export(UUID tenantId, String actorId, String correlationId, String from, String to, String sourceId) {
        Scope scope = scope(tenantId, from, to, sourceId);
        SensitivityReport r = build(tenantId, scope);
        String system = scope.sourceId() == null ? "All" : scope.systems().stream()
                .filter(s -> s.sourceId().equals(scope.sourceId())).map(SendingSystem::displayName).findFirst().orElse("");
        Csv csv = new Csv(scope.zone().getId(), system);
        String today = r.today();
        String records = recordWords(r.primaryIdentifierLabel());
        // Documents by level: stored now, so the line's period is today.
        csv.row("Documents by level", "Standard", "Documents stored", r.documents().standard(), today, today);
        csv.row("Documents by level", "Confidential", "Documents stored", r.documents().confidential(), today, today);
        csv.row("Documents by level", "Highly Confidential", "Documents stored", r.documents().highlyConfidential(), today, today);
        csv.row("Documents by level", records + " with a " + singular(records) + " level",
                records, r.documents().recordsWithLevel(), today, today);
        csv.row("Documents by level", "On legal hold", "Documents stored", r.documents().onLegalHold(), today, today);
        for (RequestRow row : r.requests()) {
            String kind = kindWords(row.kind(), records);
            csv.row("Requests", kind, "Approved", row.approved(), r.from(), r.to());
            csv.row("Requests", kind, "Declined", row.declined(), r.from(), r.to());
            csv.row("Requests", kind, "Expired", row.expired(), r.from(), r.to());
            csv.row("Requests", kind, "Waiting", row.waiting(), r.from(), r.to());
        }
        csv.row("Level changes", "Raised by a steward", "Changes", r.levelChanges().raisedBySteward(), r.from(), r.to());
        csv.row("Level changes", "Set or raised by a sending system (document or " + singular(records) + " level)", "Changes",
                r.levelChanges().raisedBySendingSystem(), r.from(), r.to());
        csv.row("Level changes", "Lowered after approval", "Changes", r.levelChanges().lowered(), r.from(), r.to());
        for (AttemptRow row : r.attempts()) {
            String roles = roleWords(row.roles());
            csv.row("Attempts to open restricted documents", roles, "Attempts", row.attempts(), r.from(), r.to());
            csv.row("Attempts to open restricted documents", roles, "People", row.people(), r.from(), r.to());
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", scope.from().toString());
        details.put("to", scope.to().toString());
        details.put("sourceId", scope.sourceId() == null ? null : scope.sourceId().toString());
        details.put("timeZone", scope.zone().getId());
        details.put("rowCount", csv.rows);
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId, EXPORT_ACTION, "REPORT",
                "SENSITIVITY", clock.instant(), correlationId, AuditOutcome.SUCCESS, json(details)));
        String filename = "vero-sensitivity-" + scope.from() + "-to-" + scope.to() + ".csv";
        return new Export(csv.out.toString().getBytes(StandardCharsets.UTF_8), csv.rows, filename);
    }

    private SensitivityReport build(UUID tenantId, Scope scope) {
        UUID source = scope.sourceId();
        DocumentLevels levels = sensitivity.countStoredDocuments(tenantId, source);
        Documents documents = new Documents(levels.standard(), levels.confidential(), levels.highlyConfidential(),
                levels.standard() + levels.confidential() + levels.highlyConfidential(), levels.recordsWithLevel(),
                levels.onLegalHold());

        Map<String, long[]> byKind = new LinkedHashMap<>();
        KINDS.forEach(k -> byKind.put(k, new long[4]));
        for (RequestCount c : sensitivity.countRequests(tenantId, source, scope.start(), scope.end(), clock.instant())) {
            long[] row = byKind.get(c.kind());
            if (row == null) throw new InternalCheckFailed("A request of an unknown kind can't be counted: " + c.kind());
            int column = switch (c.outcome()) {
                case "APPROVED" -> 0;
                case "DECLINED" -> 1;
                case "EXPIRED" -> 2;
                case "WAITING" -> 3;
                default -> throw new InternalCheckFailed("A request outcome Vero doesn't know: " + c.outcome());
            };
            row[column] += c.count();
        }
        List<RequestRow> requests = byKind.entrySet().stream()
                .map(e -> new RequestRow(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]))
                .toList();

        LevelChanges changes = sensitivity.countLevelChanges(tenantId, source, scope.start(), scope.end());

        Map<List<String>, long[]> byRoles = new LinkedHashMap<>();
        for (AttemptCount a : sensitivity.countRestrictedAttempts(tenantId, source, scope.start(), scope.end())) {
            List<String> roles = a.roles() == null ? List.of() : a.roles().stream().sorted().distinct().toList();
            long[] row = byRoles.computeIfAbsent(roles, k -> new long[2]);
            row[0] += a.attempts();
            row[1] += a.people();
        }
        List<AttemptRow> attempts = new ArrayList<>();
        byRoles.forEach((roles, n) -> attempts.add(new AttemptRow(roles, n[0], n[1])));
        attempts.sort(Comparator.comparingLong(AttemptRow::attempts).reversed()
                .thenComparing(row -> String.join(",", row.roles())));

        String label = sensitivity.findPrimaryIdentifierLabel(tenantId, source).orElse(null);
        return new SensitivityReport(scope.from().toString(), scope.to().toString(), scope.today().toString(),
                scope.zone().getId(), scope.zoneSource(), source, label, scope.systems(), documents, requests, changes,
                List.copyOf(attempts));
    }

    private Scope scope(UUID tenantId, String from, String to, String sourceId) {
        TenantZone inUse = TenantZone.of(tenants.findTenantTimeZone(tenantId).orElse(null), installDefault);
        ZoneId zone = inUse.zone();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        boolean noFrom = from == null || from.isBlank();
        boolean noTo = to == null || to.isBlank();
        LocalDate first;
        LocalDate last;
        if (noFrom && noTo) {
            first = today.withDayOfMonth(1);
            last = today;
        } else if (noFrom || noTo) {
            throw badRange("Send both from and to (YYYY-MM-DD), or neither for this month.");
        } else {
            first = day(from);
            last = day(to);
            if (last.isBefore(first)) throw badRange("to is before from.");
            if (ChronoUnit.DAYS.between(first, last) + 1 > MAX_DAYS) {
                throw badRange("The Sensitivity report covers at most " + MAX_DAYS + " days.");
            }
        }
        List<SendingSystem> systems = tenants.findSendingSystems(tenantId);
        UUID source = null;
        if (sourceId != null && !sourceId.isBlank()) {
            try {
                source = UUID.fromString(sourceId.trim());
            } catch (IllegalArgumentException ex) {
                throw unknownSource();
            }
            UUID wanted = source;
            if (systems.stream().noneMatch(s -> s.sourceId().equals(wanted))) throw unknownSource();
        }
        return new Scope(zone, inUse.source(), today, first, last, first.atStartOfDay(zone).toInstant(),
                last.plusDays(1).atStartOfDay(zone).toInstant(), source, systems);
    }

    private static LocalDate day(String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException ex) {
            throw badRange("from and to are days written YYYY-MM-DD.");
        }
    }

    private static CodedRefusal.BadRequest badRange(String why) {
        return new CodedRefusal.BadRequest(SdvErrorCode.RPT_002, "The period isn't valid: " + why);
    }

    private static CodedRefusal.BadRequest unknownSource() {
        return new CodedRefusal.BadRequest(SdvErrorCode.RPT_003, "The sending system isn't one of this tenant's.");
    }

    // ------------------------------------------------------------------ words (the CSV says what the screen says)

    /** "Contract number" gives "Contracts", "Patient ID" gives "Patients"; otherwise "Records" (as the screen). */
    static String recordWords(String label) {
        Matcher m = IDENTIFIER_LABEL.matcher(label == null ? "" : label.trim());
        String word = m.matches() ? m.group(1).trim() : "";
        if (word.isEmpty() || word.split("\\s+").length > 2) return "Records";
        String one = word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT);
        return one.endsWith("s") || one.endsWith("S") ? one : one + "s";
    }

    private static String singular(String many) {
        return many.toLowerCase(Locale.ROOT).replaceAll("s$", "");
    }

    static String kindWords(String kind, String records) {
        return switch (kind) {
            case "DOWNLOAD" -> "Download";
            case "PRINT" -> "Print";
            case "LOWER_LEVEL" -> "Lower level (one document)";
            case "LOWER_RECORD_LEVEL" -> "Lower level (whole " + singular(records) + ")";
            default -> throw new InternalCheckFailed("A request kind Vero doesn't know: " + kind);
        };
    }

    static String roleWords(List<String> roles) {
        if (roles == null || roles.isEmpty()) return "Role not recorded";
        return String.join(" and ", roles.stream().map(SensitivityReportService::roleWord).toList());
    }

    private static String roleWord(String role) {
        return switch (role) {
            case "BUSINESS_USER" -> "Business user";
            case "DOCUMENT_STEWARD" -> "Document steward";
            case "RESTRICTED_STEWARD" -> "Restricted steward";
            case "BUSINESS_ADMIN" -> "Business admin";
            case "TENANT_IT_ADMIN" -> "Tenant IT admin";
            case "PLATFORM_OPERATIONS_ADMIN" -> "Platform operations admin";
            default -> role;
        };
    }

    private static String json(Map<String, Object> details) {
        try {
            return JSON.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            throw InternalCheckFailed.auditNotWritten("The export could not be audited, so it was not made.", ex);
        }
    }

    /** The CSV, with the audit CSV's formula guard on every cell. */
    private static final class Csv {
        final StringBuilder out = new StringBuilder("﻿").append(CSV_HEADER).append("\r\n");
        final String zone;
        final String system;
        int rows;

        Csv(String zone, String system) {
            this.zone = zone;
            this.system = system;
        }

        void row(String section, String line, String measure, long count, String from, String to) {
            out.append(AuditCsv.cell(section)).append(',')
                    .append(AuditCsv.cell(line)).append(',')
                    .append(AuditCsv.cell(measure)).append(',')
                    .append(count).append(',')
                    .append(AuditCsv.cell(from)).append(',')
                    .append(AuditCsv.cell(to)).append(',')
                    .append(AuditCsv.cell(zone)).append(',')
                    .append(AuditCsv.cell(system)).append("\r\n");
            rows++;
        }
    }

    // ------------------------------------------------------------------ views

    private record Scope(ZoneId zone, String zoneSource, LocalDate today, LocalDate from, LocalDate to, Instant start,
                         Instant end, UUID sourceId, List<SendingSystem> systems) { }

    /**
     * from/to/today: days in the tenant's time zone (the period; documents are counted as they are now).
     * timeZoneSource: TENANT or INSTALL_DEFAULT. primaryIdentifierLabel: the display name of the primary identifier
     * most sending systems use ("Contract number"), null when none has one. sendingSystems: for the filter.
     */
    public record SensitivityReport(String from, String to, String today, String timeZone, String timeZoneSource,
                                    UUID sourceId, String primaryIdentifierLabel, List<SendingSystem> sendingSystems,
                                    Documents documents, List<RequestRow> requests, LevelChanges levelChanges,
                                    List<AttemptRow> attempts) { }

    /** Stored now, by effective level; recordsWithLevel: records (contracts) that carry a record level. */
    public record Documents(long standard, long confidential, long highlyConfidential, long total,
                            long recordsWithLevel, long onLegalHold) { }

    public record RequestRow(String kind, long approved, long declined, long expired, long waiting) { }

    /** roles: the role names the refusals recorded (empty: none recorded). */
    public record AttemptRow(List<String> roles, long attempts, long people) { }

    public record Export(byte[] csv, int rowCount, String filename) { }
}
