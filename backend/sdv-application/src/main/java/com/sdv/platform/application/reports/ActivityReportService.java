package com.sdv.platform.application.reports;

import com.sdv.platform.application.json.VeroJson;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.application.audit.AuditCsv;
import com.sdv.platform.application.port.ActivityReportRepository;
import com.sdv.platform.application.port.ActivityReportRepository.Count;
import com.sdv.platform.application.port.ActivityReportRepository.Person;
import com.sdv.platform.application.port.ActivityReportRepository.Row;
import com.sdv.platform.application.port.ActivityReportRepository.Window;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IntakeReportRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 0054: the Activity report of the Business Admin's Reports tab, and its CSV, built from the audit trail.
 * <ol>
 *   <li><b>Access</b> in the period (days in the entity's time zone; the last 7 days by default): documents opened,
 *       printed and downloaded, by role and by level.</li>
 *   <li><b>Refusals</b>: opens refused because of the level (SDV-SEN-008) or a quarantine (SDV-QUA-001), by role. A
 *       legal hold never refuses an open, so it has no line.</li>
 *   <li><b>Busiest people</b>: the 20 people with the most opens, by sign-in name as on the Audit page, with the roles
 *       they opened with and their opens by level.</li>
 *   <li><b>Highly Confidential approvals</b>: print and download requests made, approved, declined and expired; their
 *       one-time grants used and given back.</li>
 * </ol>
 * <b>Counts only</b>: never content, file names, titles, record identifiers or reasons. Events recorded before task 0054
 * name no role for opens, prints and downloads ("Role not recorded").
 * <p>
 * <b>Fast for a busy month at 10,000 agents.</b> Each closed day is counted once from the audit trail and kept (in the
 * background, {@link #keepClosedDays}; or when a report first needs it); a report reads the kept days and only the rest
 * of the period (today) from the audit trail. A day is closed {@link #SETTLE} after it ends, so events committed late
 * still count.
 */
@Service
public class ActivityReportService {
    public static final String EXPORT_ACTION = "ACTIVITY_REPORT_EXPORTED";
    public static final int DEFAULT_DAYS = 7;
    public static final int MAX_DAYS = 92;
    public static final int BUSIEST = 20;
    /** A day is counted and kept this long after it ends. */
    static final Duration SETTLE = Duration.ofMinutes(10);
    static final String CSV_HEADER = "Section,Line,Level,Measure,Count,From,To,Time zone";
    static final List<String> ACCESS = List.of("OPENED", "PRINTED", "DOWNLOADED");
    static final List<String> LEVELS = List.of("STANDARD", "CONFIDENTIAL", "HIGHLY_CONFIDENTIAL");
    private static final ObjectMapper JSON = VeroJson.mapper();

    private final ActivityReportRepository activity;
    private final IntakeReportRepository tenants;
    private final AuditEventPort audit;
    private final TransactionTemplate transactions;
    private final ZoneId installDefault;
    private final Clock clock;

    @Autowired
    public ActivityReportService(ActivityReportRepository activity, IntakeReportRepository tenants, AuditEventPort audit,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${sdv.tenant-defaults.time-zone:UTC}") String installDefaultZone) {
        this(activity, tenants, audit, transactionManager, installDefaultZone, Clock.systemUTC());
    }

    ActivityReportService(ActivityReportRepository activity, IntakeReportRepository tenants, AuditEventPort audit,
                          PlatformTransactionManager transactionManager, String installDefaultZone, Clock clock) {
        this.activity = Objects.requireNonNull(activity, "activity");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.installDefault = IntakeReportService.installZone(installDefaultZone);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------ the kept days

    /** The background job: for each active entity, keeps the closed days of the last {@link #MAX_DAYS} not yet kept. */
    public int keepClosedDays(int maxDays) {
        int kept = 0;
        for (UUID tenantId : activity.findActiveTenants()) {
            ZoneId zone = zone(tenantId).zone();
            LocalDate today = LocalDate.now(clock.withZone(zone));
            kept += keep(tenantId, zone, today.minusDays(MAX_DAYS - 1L), today, maxDays - kept);
            if (kept >= maxDays) break;
        }
        return kept;
    }

    /** Keeps the closed days of [from, to] not yet kept in this zone, newest first, at most {@code max}. Returns how many. */
    private int keep(UUID tenantId, ZoneId zone, LocalDate from, LocalDate to, int max) {
        Map<LocalDate, String> kept = activity.findKeptDays(tenantId, from, to);
        Instant now = clock.instant();
        int done = 0;
        for (LocalDate day = to; !day.isBefore(from) && done < max; day = day.minusDays(1)) {
            if (!closed(day, zone, now)) continue;
            if (zone.getId().equals(kept.get(day))) continue;
            LocalDate d = day;
            Instant start = day.atStartOfDay(zone).toInstant();
            Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
            transactions.executeWithoutResult(status -> activity.keepDay(tenantId, d, zone.getId(), start, end, now));
            done++;
        }
        return done;
    }

    private static boolean closed(LocalDate day, ZoneId zone, Instant now) {
        return !day.plusDays(1).atStartOfDay(zone).toInstant().plus(SETTLE).isAfter(now);
    }

    // ------------------------------------------------------------------ the report

    public ActivityReport report(UUID tenantId, String from, String to) {
        Scope scope = scope(tenantId, from, to);
        // The period's closed days must be kept; any missing (an older period, or the job hasn't run) are kept now.
        keep(tenantId, scope.zone(), scope.from(), scope.to(), Integer.MAX_VALUE);
        Instant now = clock.instant();
        LocalDate lastKept = null;
        for (LocalDate day = scope.from(); !day.isAfter(scope.to()) && closed(day, scope.zone(), now); day = day.plusDays(1)) {
            lastKept = day;
        }
        Instant liveFrom = lastKept == null ? scope.start() : lastKept.plusDays(1).atStartOfDay(scope.zone()).toInstant();
        Instant liveTo = scope.end().isAfter(now) ? now : scope.end();
        Window window = new Window(scope.from(), lastKept, liveFrom, liveTo);

        Map<String, long[]> access = new LinkedHashMap<>(); // kind|roles -> by level, then not recorded
        Map<String, long[]> refusals = new LinkedHashMap<>(); // roles -> level, quarantine
        long[] approvals = new long[6];
        long[] accessTotals = new long[ACCESS.size() * 4];
        // The audit part (today) is read once; its counts join the kept days' here, its people go to the busiest list.
        List<Row> live = activity.live(tenantId, liveFrom, liveTo);
        List<Count> counts = new ArrayList<>(activity.count(tenantId, window));
        Map<String, long[]> livePeople = new LinkedHashMap<>();
        Map<String, String[]> liveNames = new LinkedHashMap<>();
        for (Row r : live) {
            counts.add(new Count(r.kind(), r.roles(), r.level(), r.events()));
            if (!"OPENED".equals(r.kind()) || r.actorId() == null) continue;
            long[] n = livePeople.computeIfAbsent(r.actorId(), k -> new long[4]);
            n[r.level() == null ? 3 : LEVELS.indexOf(r.level())] += r.events();
            String[] who = liveNames.computeIfAbsent(r.actorId(), k -> new String[2]);
            if (r.actorName() != null) who[0] = r.actorName();
            who[1] = r.roles();
        }
        List<Person> today = new ArrayList<>();
        livePeople.forEach((id, n) -> today.add(new Person(id, liveNames.get(id)[0], liveNames.get(id)[1], n[0], n[1], n[2],
                n[0] + n[1] + n[2] + n[3])));
        // The kept part and the audit part merge by the roles' names, however they were recorded.
        Map<String, Long> merged = new LinkedHashMap<>();
        for (Count c : counts) merged.merge(c.kind() + "|" + String.join(",", roles(c.roles())) + "|" + (c.level() == null ? "" : c.level()),
                c.events(), Long::sum);
        counts = new ArrayList<>();
        for (Map.Entry<String, Long> e : merged.entrySet()) {
            String[] k = e.getKey().split("\\|", -1);
            counts.add(new Count(k[0], k[1], k[2].isEmpty() ? null : k[2], e.getValue()));
        }
        for (Count c : counts) {
            int levelColumn = c.level() == null ? 3 : LEVELS.indexOf(c.level());
            switch (c.kind()) {
                case "OPENED", "PRINTED", "DOWNLOADED" -> {
                    access.computeIfAbsent(c.kind() + "|" + c.roles(), k -> new long[4])[levelColumn] += c.events();
                    accessTotals[ACCESS.indexOf(c.kind()) * 4 + levelColumn] += c.events();
                }
                case "REFUSED_LEVEL" -> refusals.computeIfAbsent(c.roles(), k -> new long[2])[0] += c.events();
                case "REFUSED_QUARANTINE" -> refusals.computeIfAbsent(c.roles(), k -> new long[2])[1] += c.events();
                case "REQUESTED" -> approvals[0] += c.events();
                case "APPROVED" -> approvals[1] += c.events();
                case "DECLINED" -> approvals[2] += c.events();
                case "EXPIRED" -> approvals[3] += c.events();
                case "GRANT_USED" -> approvals[4] += c.events();
                case "GRANT_RETURNED" -> approvals[5] += c.events();
                default -> throw new InternalCheckFailed("An activity kind Vero doesn't know: " + c.kind());
            }
        }
        List<AccessRow> accessRows = new ArrayList<>();
        access.forEach((key, n) -> {
            String[] parts = key.split("\\|", -1);
            accessRows.add(new AccessRow(parts[0], roles(parts[1]), n[0], n[1], n[2], n[3], n[0] + n[1] + n[2] + n[3]));
        });
        accessRows.sort((a, b) -> a.kind().equals(b.kind()) ? Long.compare(b.total(), a.total())
                : Integer.compare(ACCESS.indexOf(a.kind()), ACCESS.indexOf(b.kind())));
        List<AccessTotal> totals = new ArrayList<>();
        for (int i = 0; i < ACCESS.size(); i++) {
            long[] t = Arrays.copyOfRange(accessTotals, i * 4, i * 4 + 4);
            totals.add(new AccessTotal(ACCESS.get(i), t[0], t[1], t[2], t[3], t[0] + t[1] + t[2] + t[3]));
        }
        List<RefusalRow> refusalRows = new ArrayList<>();
        refusals.forEach((r, n) -> refusalRows.add(new RefusalRow(roles(r), n[0], n[1], n[0] + n[1])));
        refusalRows.sort((a, b) -> Long.compare(b.total(), a.total()));
        List<PersonRow> people = new ArrayList<>();
        for (Person p : activity.busiest(tenantId, window, today, BUSIEST)) {
            List<String> roles = p.roles() == null ? List.of() : Arrays.stream(p.roles().split(";"))
                    .flatMap(r -> roles(r).stream()).distinct().sorted().toList();
            String name = p.actorName() == null || p.actorName().isBlank() ? p.actorId() : p.actorName();
            people.add(new PersonRow(name, roles, p.standard(), p.confidential(), p.highlyConfidential(), p.total()));
        }
        return new ActivityReport(scope.from().toString(), scope.to().toString(), scope.today().toString(),
                scope.zone().getId(), scope.zoneSource(), lastKept == null ? null : lastKept.toString(), accessRows, totals,
                refusalRows, people, new Approvals(approvals[0], approvals[1], approvals[2], approvals[3], approvals[4], approvals[5]));
    }

    /** Roles as recorded (a JSON array's text, or names joined by commas): fixed role names only, sorted, once. */
    static List<String> roles(String recorded) {
        if (recorded == null || recorded.isBlank()) return List.of();
        return Arrays.stream(recorded.replaceAll("[\\[\\]\"\\s]", "").split(",")).filter(s -> s.matches("[A-Z_]{1,80}"))
                .distinct().sorted().toList();
    }

    // ------------------------------------------------------------------ the CSV

    public record Export(byte[] csv, int rowCount, String filename, UUID reference, String fingerprint) { }

    public Export export(UUID tenantId, String actorId, String correlationId, String from, String to) {
        ActivityReport r = report(tenantId, from, to);
        Csv csv = new Csv(r.from(), r.to(), r.timeZone());
        for (AccessRow a : r.access()) {
            String line = kindWords(a.kind()) + ", " + SensitivityReportService.roleWords(a.roles());
            csv.row("Access", line, "Standard", "Events", a.standard());
            csv.row("Access", line, "Confidential", "Events", a.confidential());
            csv.row("Access", line, "Highly Confidential", "Events", a.highlyConfidential());
            if (a.levelNotRecorded() > 0) csv.row("Access", line, "Level not recorded", "Events", a.levelNotRecorded());
        }
        for (AccessTotal t : r.accessTotals()) csv.row("Access", kindWords(t.kind()) + ", every role", "All levels", "Events", t.total());
        for (RefusalRow f : r.refusals()) {
            String line = SensitivityReportService.roleWords(f.roles());
            csv.row("Refusals", line, "All levels", "Opens refused for the level", f.level());
            csv.row("Refusals", line, "All levels", "Opens refused for a quarantine", f.quarantine());
        }
        int rank = 0;
        for (PersonRow p : r.busiest()) {
            rank++;
            String line = rank + ". " + p.name() + " (" + SensitivityReportService.roleWords(p.roles()) + ")";
            csv.row("Busiest people", line, "Standard", "Opens", p.standard());
            csv.row("Busiest people", line, "Confidential", "Opens", p.confidential());
            csv.row("Busiest people", line, "Highly Confidential", "Opens", p.highlyConfidential());
        }
        Approvals a = r.approvals();
        csv.row("Highly Confidential approvals", "Print and download requests", "Highly Confidential", "Requested", a.requested());
        csv.row("Highly Confidential approvals", "Print and download requests", "Highly Confidential", "Approved", a.approved());
        csv.row("Highly Confidential approvals", "Print and download requests", "Highly Confidential", "Declined", a.declined());
        csv.row("Highly Confidential approvals", "Print and download requests", "Highly Confidential", "Expired", a.expired());
        csv.row("Highly Confidential approvals", "One-time grants", "Highly Confidential", "Used", a.grantsUsed());
        csv.row("Highly Confidential approvals", "One-time grants", "Highly Confidential", "Given back", a.grantsReturned());
        byte[] bytes = csv.out.toString().getBytes(StandardCharsets.UTF_8);
        String fingerprint = sha256(bytes);
        UUID reference = UUID.randomUUID();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", reference.toString());
        details.put("fingerprint", fingerprint);
        details.put("rowCount", csv.rows);
        details.put("from", r.from());
        details.put("to", r.to());
        details.put("timeZone", r.timeZone());
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId, EXPORT_ACTION, "REPORT", "ACTIVITY",
                clock.instant(), correlationId, AuditOutcome.SUCCESS, json(details)));
        String filename = "vero-activity-" + r.from() + "-to-" + r.to() + "-" + reference.toString().substring(0, 8) + ".csv";
        return new Export(bytes, csv.rows, filename, reference, fingerprint);
    }

    static String kindWords(String kind) {
        return switch (kind) {
            case "OPENED" -> "Opened";
            case "PRINTED" -> "Printed";
            case "DOWNLOADED" -> "Downloaded";
            default -> kind;
        };
    }

    private static final class Csv {
        final StringBuilder out = new StringBuilder("﻿").append(CSV_HEADER).append("\r\n");
        final String from;
        final String to;
        final String zone;
        int rows;

        Csv(String from, String to, String zone) {
            this.from = from;
            this.to = to;
            this.zone = zone;
        }

        void row(String section, String line, String level, String measure, long count) {
            out.append(AuditCsv.cell(section)).append(',').append(AuditCsv.cell(line)).append(',')
                    .append(AuditCsv.cell(level)).append(',').append(AuditCsv.cell(measure)).append(',').append(count)
                    .append(',').append(AuditCsv.cell(from)).append(',').append(AuditCsv.cell(to)).append(',')
                    .append(AuditCsv.cell(zone)).append("\r\n");
            rows++;
        }
    }

    // ------------------------------------------------------------------ inside

    private record Scope(ZoneId zone, String zoneSource, LocalDate today, LocalDate from, LocalDate to, Instant start,
                         Instant end) { }

    private Scope scope(UUID tenantId, String from, String to) {
        TenantZone inUse = zone(tenantId);
        ZoneId zone = inUse.zone();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        boolean noFrom = from == null || from.isBlank();
        boolean noTo = to == null || to.isBlank();
        LocalDate first;
        LocalDate last;
        if (noFrom && noTo) {
            first = today.minusDays(DEFAULT_DAYS - 1L);
            last = today;
        } else if (noFrom || noTo) {
            throw badRange("Send both from and to (YYYY-MM-DD), or neither for the last " + DEFAULT_DAYS + " days.");
        } else {
            first = day(from);
            last = day(to);
            if (last.isBefore(first)) throw badRange("to is before from.");
            if (ChronoUnit.DAYS.between(first, last) + 1 > MAX_DAYS) {
                throw badRange("The Activity report covers at most " + MAX_DAYS + " days.");
            }
            if (last.isAfter(today)) throw badRange("to is after today.");
        }
        return new Scope(zone, inUse.source(), today, first, last, first.atStartOfDay(zone).toInstant(),
                last.plusDays(1).atStartOfDay(zone).toInstant());
    }

    private TenantZone zone(UUID tenantId) {
        return TenantZone.of(tenants.findTenantTimeZone(tenantId).orElse(null), installDefault);
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

    private static String json(Map<String, Object> details) {
        try {
            return JSON.writeValueAsString(details);
        } catch (JacksonException ex) {
            throw InternalCheckFailed.auditNotWritten("The export could not be audited, so it was not made.", ex);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new InternalCheckFailed("SHA-256 is not available.");
        }
    }

    // ------------------------------------------------------------------ views

    /**
     * from/to/today: days in the entity's time zone. keptThrough: the last day read from the kept counts (the rest of
     * the period comes from the audit trail now); null when none was.
     */
    public record ActivityReport(String from, String to, String today, String timeZone, String timeZoneSource,
                                 String keptThrough, List<AccessRow> access, List<AccessTotal> accessTotals,
                                 List<RefusalRow> refusals, List<PersonRow> busiest, Approvals approvals) { }

    /** kind OPENED, PRINTED or DOWNLOADED; roles empty: not recorded (events before task 0054). */
    public record AccessRow(String kind, List<String> roles, long standard, long confidential, long highlyConfidential,
                            long levelNotRecorded, long total) { }

    public record AccessTotal(String kind, long standard, long confidential, long highlyConfidential,
                              long levelNotRecorded, long total) { }

    /** level: opens refused for the level (SDV-SEN-008); quarantine: for a quarantine (SDV-QUA-001). */
    public record RefusalRow(List<String> roles, long level, long quarantine, long total) { }

    /** name: the sign-in name, as on the Audit page. */
    public record PersonRow(String name, List<String> roles, long standard, long confidential, long highlyConfidential,
                            long total) { }

    public record Approvals(long requested, long approved, long declined, long expired, long grantsUsed,
                            long grantsReturned) { }
}
