package com.sdv.platform.application.reports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.audit.AuditCsv;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IntakeReportRepository;
import com.sdv.platform.application.port.IntakeReportRepository.SendingSystem;
import com.sdv.platform.application.port.StorageReportRepository;
import com.sdv.platform.application.port.StorageReportRepository.Due;
import com.sdv.platform.application.port.StorageReportRepository.HeldBack;
import com.sdv.platform.application.port.StorageReportRepository.Kept;
import com.sdv.platform.application.port.StorageReportRepository.Month;
import com.sdv.platform.application.port.StorageReportRepository.Stored;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.InternalCheckFailed;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task 0053: the Storage and retention report of the Business Admin's Reports tab, and its CSV.
 * <p>
 * <b>Counts only.</b> Sending systems, levels, retention lengths and their policy names, and counts: never content,
 * file names, titles, record identifiers or reasons.
 * <ol>
 *   <li><b>Storage now</b>: stored documents, pages and bytes, by sending system and effective level, with totals.</li>
 *   <li><b>Growth</b>: the same, received per month, for the last 12 months (this month included), in the tenant's
 *       time zone; at each document's level now; a document deleted since still counts in its month.</li>
 *   <li><b>Due for deletion</b>: per retention rule (the retention length each document was given, named by the most
 *       recent policy of that length), how many reach the end of their retention within 30, 60 and 90 days. "Kept
 *       forever" shows none.</li>
 *   <li><b>Held back from deletion</b>: past their retention now but kept, because of a legal hold, a quarantine (rows
 *       or a filter's block), or both.</li>
 * </ol>
 * <b>Fast at a million documents.</b> The counts read every document, so they are worked out in the background
 * ({@link #countAll}, every 15 minutes by default) and kept (report_storage_counts). A screen load reads the kept counts
 * only; the first one for an entity with none counts once, while it waits. The report says when it was counted.
 */
@Service
public class StorageReportService {
    public static final String EXPORT_ACTION = "STORAGE_REPORT_EXPORTED";
    public static final int MONTHS = 12;
    static final String CSV_HEADER = "Section,Line,Level,Measure,Count,Counted at (UTC),Time zone";
    private static final ObjectMapper JSON = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final StorageReportRepository storage;
    private final IntakeReportRepository tenants;
    private final AuditEventPort audit;
    private final ZoneId installDefault;
    private final Clock clock;
    private final int recountMinutes;
    private final Map<UUID, Object> counting = new ConcurrentHashMap<>();

    @Autowired
    public StorageReportService(StorageReportRepository storage, IntakeReportRepository tenants, AuditEventPort audit,
                                @Value("${sdv.tenant-defaults.time-zone:UTC}") String installDefaultZone,
                                @Value("${sdv.reports.storage.count-delay-ms:900000}") long countDelayMs) {
        this(storage, tenants, audit, installDefaultZone, Clock.systemUTC(), (int) Math.max(1, countDelayMs / 60_000));
    }

    StorageReportService(StorageReportRepository storage, IntakeReportRepository tenants, AuditEventPort audit,
                         String installDefaultZone, Clock clock, int recountMinutes) {
        this.storage = Objects.requireNonNull(storage, "storage");
        this.tenants = Objects.requireNonNull(tenants, "tenants");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.installDefault = IntakeReportService.installZone(installDefaultZone);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.recountMinutes = recountMinutes;
    }

    // ------------------------------------------------------------------ counting (background)

    /** What is kept for an entity: the raw counts and the period they cover. */
    record Counts(String countedAt, String timeZone, String firstMonth, List<Stored> stored, List<Month> received,
                  List<Due> due, HeldBack heldBack) { }

    /** Counts every active entity again (the background job). Returns how many were counted. */
    public int countAll() {
        int done = 0;
        for (UUID tenantId : storage.findActiveTenants()) {
            count(tenantId);
            done++;
        }
        return done;
    }

    /** Counts one entity and keeps the counts; one count at a time per entity. */
    public void count(UUID tenantId) {
        Object lock = counting.computeIfAbsent(tenantId, k -> new Object());
        synchronized (lock) {
            long started = System.nanoTime();
            Instant now = clock.instant();
            ZoneId zone = zone(tenantId).zone();
            YearMonth thisMonth = YearMonth.from(now.atZone(zone));
            YearMonth first = thisMonth.minusMonths(MONTHS - 1L);
            Instant since = first.atDay(1).atStartOfDay(zone).toInstant();
            Counts counts = new Counts(now.toString(), zone.getId(), first.toString(), storage.countStored(tenantId),
                    storage.countReceived(tenantId, since, zone.getId()), storage.countDue(tenantId, now),
                    storage.countHeldBack(tenantId, now));
            long took = (System.nanoTime() - started) / 1_000_000;
            storage.keep(tenantId, now, took, write(counts));
        }
    }

    // ------------------------------------------------------------------ the report (from the kept counts)

    public StorageReport report(UUID tenantId) {
        Kept kept = storage.findKept(tenantId).orElse(null);
        if (kept == null) {
            count(tenantId);
            kept = storage.findKept(tenantId)
                    .orElseThrow(() -> new InternalCheckFailed("The storage counts could not be kept."));
        }
        return view(tenantId, read(kept.countsJson()), kept);
    }

    private StorageReport view(UUID tenantId, Counts c, Kept kept) {
        Map<UUID, String> names = new LinkedHashMap<>();
        List<SendingSystem> systems = tenants.findSendingSystems(tenantId);
        for (SendingSystem s : systems) names.put(s.sourceId(), s.displayName());

        List<StoredRow> stored = new ArrayList<>();
        Map<SensitivityLevel, long[]> byLevel = new LinkedHashMap<>();
        for (SensitivityLevel l : SensitivityLevel.values()) byLevel.put(l, new long[3]);
        for (Stored s : c.stored()) {
            stored.add(new StoredRow(s.sourceId(), names.getOrDefault(s.sourceId(), "Sending system no longer known"),
                    s.level(), s.documents(), s.pages(), s.bytes()));
            long[] t = byLevel.get(s.level());
            t[0] += s.documents(); t[1] += s.pages(); t[2] += s.bytes();
        }
        List<Total> totals = new ArrayList<>();
        long[] all = new long[3];
        byLevel.forEach((level, t) -> {
            totals.add(new Total(level, t[0], t[1], t[2]));
            all[0] += t[0]; all[1] += t[1]; all[2] += t[2];
        });

        // Growth: every month of the period (none received shows zeros), oldest first.
        YearMonth first = YearMonth.parse(c.firstMonth());
        Map<String, long[]> months = new LinkedHashMap<>();
        for (int i = 0; i < MONTHS; i++) months.put(first.plusMonths(i).toString(), new long[6]);
        List<MonthRow> received = new ArrayList<>();
        for (Month m : c.received()) {
            received.add(new MonthRow(m.month(), m.sourceId(), names.getOrDefault(m.sourceId(), "Sending system no longer known"),
                    m.level(), m.documents(), m.pages(), m.bytes()));
            long[] t = months.get(m.month());
            if (t == null) continue;
            t[0] += m.documents(); t[1] += m.pages(); t[2] += m.bytes();
            t[3 + m.level().ordinal()] += m.documents();
        }
        List<MonthTotal> growth = new ArrayList<>();
        months.forEach((month, t) -> growth.add(new MonthTotal(month, t[0], t[1], t[2], t[3], t[4], t[5])));

        Map<Integer, String> ruleNames = storage.findRuleNames(tenantId);
        List<RuleRow> rules = new ArrayList<>();
        for (Due d : c.due()) {
            String name = ruleNames.get(d.retentionDays());
            if (d.retentionDays() == null) {
                rules.add(new RuleRow(null, name == null ? "Keep forever" : name, true, d.stored(), null, null, null));
            } else {
                rules.add(new RuleRow(d.retentionDays(), name, false, d.stored(), d.within30(), d.within60(), d.within90()));
            }
        }
        HeldBack h = c.heldBack();
        TenantZone zone = zone(tenantId);
        return new StorageReport(c.countedAt(), kept.tookMs(), recountMinutes, c.timeZone(), zone.source(), systems,
                stored, totals, new Total(null, all[0], all[1], all[2]), growth, received, rules,
                new Held(h.legalHold(), h.quarantine(), h.both(), h.legalHold() + h.quarantine() + h.both()));
    }

    // ------------------------------------------------------------------ the CSV

    public record Export(byte[] csv, int rowCount, String filename, UUID reference, String fingerprint) { }

    /** Every count of the report, one line each, with a reference and the file's SHA-256; the export is audited. */
    public Export export(UUID tenantId, String actorId, String correlationId) {
        StorageReport r = report(tenantId);
        Csv csv = new Csv(r.countedAt(), r.timeZone());
        for (StoredRow s : r.stored()) {
            csv.row("Storage now", s.sendingSystem(), levelWords(s.level()), "Documents", s.documents());
            csv.row("Storage now", s.sendingSystem(), levelWords(s.level()), "Pages", s.pages());
            csv.row("Storage now", s.sendingSystem(), levelWords(s.level()), "Bytes", s.bytes());
        }
        for (Total t : r.totals()) {
            csv.row("Storage now", "All sending systems", levelWords(t.level()), "Documents", t.documents());
            csv.row("Storage now", "All sending systems", levelWords(t.level()), "Pages", t.pages());
            csv.row("Storage now", "All sending systems", levelWords(t.level()), "Bytes", t.bytes());
        }
        csv.row("Storage now", "All sending systems", "All levels", "Documents", r.total().documents());
        csv.row("Storage now", "All sending systems", "All levels", "Pages", r.total().pages());
        csv.row("Storage now", "All sending systems", "All levels", "Bytes", r.total().bytes());
        for (MonthRow m : r.received()) {
            String line = m.month() + " " + m.sendingSystem();
            csv.row("Growth", line, levelWords(m.level()), "Documents", m.documents());
            csv.row("Growth", line, levelWords(m.level()), "Pages", m.pages());
            csv.row("Growth", line, levelWords(m.level()), "Bytes", m.bytes());
        }
        for (MonthTotal m : r.growth()) {
            csv.row("Growth", m.month() + " all sending systems", "All levels", "Documents", m.documents());
            csv.row("Growth", m.month() + " all sending systems", "All levels", "Pages", m.pages());
            csv.row("Growth", m.month() + " all sending systems", "All levels", "Bytes", m.bytes());
        }
        for (RuleRow rule : r.retention()) {
            String line = ruleWords(rule);
            csv.row("Due for deletion", line, "All levels", "Documents stored", rule.stored());
            if (rule.keptForever()) continue;
            csv.row("Due for deletion", line, "All levels", "Within 30 days", rule.within30());
            csv.row("Due for deletion", line, "All levels", "Within 60 days", rule.within60());
            csv.row("Due for deletion", line, "All levels", "Within 90 days", rule.within90());
        }
        csv.row("Held back from deletion", "Legal hold", "All levels", "Documents", r.heldBack().legalHold());
        csv.row("Held back from deletion", "Quarantine", "All levels", "Documents", r.heldBack().quarantine());
        csv.row("Held back from deletion", "Legal hold and quarantine", "All levels", "Documents", r.heldBack().both());
        byte[] bytes = csv.out.toString().getBytes(StandardCharsets.UTF_8);
        String fingerprint = sha256(bytes);
        UUID reference = UUID.randomUUID();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reference", reference.toString());
        details.put("fingerprint", fingerprint);
        details.put("rowCount", csv.rows);
        details.put("countedAt", r.countedAt());
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId, EXPORT_ACTION, "REPORT", "STORAGE",
                clock.instant(), correlationId, AuditOutcome.SUCCESS, json(details)));
        String filename = "vero-storage-" + LocalDate.now(clock.withZone(ZoneOffset.UTC)) + "-" + reference.toString().substring(0, 8) + ".csv";
        return new Export(bytes, csv.rows, filename, reference, fingerprint);
    }

    static String ruleWords(RuleRow rule) {
        if (rule.keptForever()) return rule.ruleName();
        String days = rule.retentionDays() + " day" + (rule.retentionDays() == 1 ? "" : "s");
        return rule.ruleName() == null ? "Kept " + days : rule.ruleName() + " (" + days + ")";
    }

    static String levelWords(SensitivityLevel level) {
        if (level == null) return "All levels";
        return switch (level) {
            case STANDARD -> "Standard";
            case CONFIDENTIAL -> "Confidential";
            case HIGHLY_CONFIDENTIAL -> "Highly Confidential";
        };
    }

    private static final class Csv {
        final StringBuilder out = new StringBuilder("﻿").append(CSV_HEADER).append("\r\n");
        final String countedAt;
        final String zone;
        int rows;

        Csv(String countedAt, String zone) {
            this.countedAt = countedAt;
            this.zone = zone;
        }

        void row(String section, String line, String level, String measure, long count) {
            out.append(AuditCsv.cell(section)).append(',').append(AuditCsv.cell(line)).append(',')
                    .append(AuditCsv.cell(level)).append(',').append(AuditCsv.cell(measure)).append(',')
                    .append(count).append(',').append(AuditCsv.cell(countedAt)).append(',')
                    .append(AuditCsv.cell(zone)).append("\r\n");
            rows++;
        }
    }

    // ------------------------------------------------------------------ inside

    private TenantZone zone(UUID tenantId) {
        return TenantZone.of(tenants.findTenantTimeZone(tenantId).orElse(null), installDefault);
    }

    private static String write(Counts counts) {
        try {
            return JSON.writeValueAsString(counts);
        } catch (JsonProcessingException ex) {
            throw new InternalCheckFailed("The storage counts could not be written.");
        }
    }

    private static Counts read(String json) {
        try {
            return JSON.readValue(json, Counts.class);
        } catch (JsonProcessingException ex) {
            throw new InternalCheckFailed("The storage counts kept could not be read.");
        }
    }

    private static String json(Map<String, Object> details) {
        try {
            return JSON.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
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
     * countedAt: when the counts were worked out (UTC); tookMs: how long that took; recountMinutes: how often they are
     * worked out again. timeZone: the months' time zone. total: every stored document.
     */
    public record StorageReport(String countedAt, long tookMs, int recountMinutes, String timeZone, String timeZoneSource,
                                List<SendingSystem> sendingSystems, List<StoredRow> stored, List<Total> totals,
                                Total total, List<MonthTotal> growth, List<MonthRow> received, List<RuleRow> retention,
                                Held heldBack) { }

    public record StoredRow(UUID sourceId, String sendingSystem, SensitivityLevel level, long documents, long pages,
                            long bytes) { }

    /** level null: every level. */
    public record Total(SensitivityLevel level, long documents, long pages, long bytes) { }

    public record MonthTotal(String month, long documents, long pages, long bytes, long standard, long confidential,
                             long highlyConfidential) { }

    public record MonthRow(String month, UUID sourceId, String sendingSystem, SensitivityLevel level, long documents,
                           long pages, long bytes) { }

    /** retentionDays null and keptForever: never due (within* null). ruleName: the most recent policy of that length. */
    public record RuleRow(Integer retentionDays, String ruleName, boolean keptForever, long stored, Long within30,
                          Long within60, Long within90) { }

    public record Held(long legalHold, long quarantine, long both, long total) { }
}
