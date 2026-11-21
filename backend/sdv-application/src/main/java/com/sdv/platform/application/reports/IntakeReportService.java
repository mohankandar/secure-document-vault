package com.sdv.platform.application.reports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.audit.AuditCsv;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IntakeReportRepository;
import com.sdv.platform.application.port.IntakeReportRepository.Item;
import com.sdv.platform.application.port.IntakeReportRepository.PrimaryIdentifier;
import com.sdv.platform.application.port.IntakeReportRepository.SendingSystem;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Task 0032: the Daily intake report of the Business Admin's Reports tab, its CSV, and the Stewards' notices of
 * uncorrected copies.
 * <p>
 * <b>Counts only.</b> A line shows at most the delivery's request ID (as its sending system sent it), times, the
 * primary identifier and counts. No file name, title, other metadata or content is read.
 * <p>
 * <b>Days are the tenant's.</b> "Today" and every day boundary are in the tenant's time zone: its own setting
 * (tenants.time_zone) or, without one, the install default {@code sdv.tenant-defaults.time-zone}
 * (SDV_DEFAULT_TIME_ZONE, UTC when unset). A wrong install default stops the server from starting; a wrong tenant
 * value refuses the report (SDV-RPT-004). Neither is ever guessed.
 * <p>
 * <b>What is counted, by when it happened</b> (each in the range [start of the first day, start of the day after the
 * last)):
 * <ul>
 *   <li>A <b>delivery</b> (one submission, or one refusal before a submission existed) is listed when it was created in
 *       the range or anything below arrived in it; its time is the first of these.</li>
 *   <li><b>Documents accepted</b> and their pages: documents stored in the range that didn't fail after receipt.</li>
 *   <li><b>Exact duplicates</b> (task 0031, and task 0032's uncorrected copies): acknowledged, not stored again.</li>
 *   <li><b>Refused at intake</b>: each refused submission or upload (INTAKE_REFUSED), with the code the sender got.</li>
 *   <li><b>Failed after receipt</b>: documents stored in the range whose finalization failed (PROCESSING_FAILED).</li>
 *   <li><b>Contracts</b>: distinct primary identifier values (normalized), so a contract sent by two sending systems
 *       counts once in the overall total. A delivery without one is a record on its own. <b>New</b>: no submission
 *       created before the range carried the value (from any sending system); <b>updated</b> otherwise.</li>
 * </ul>
 */
@Service
public class IntakeReportService {
    public static final int MAX_DAYS = 31;
    /** More items than this in one range: refused (SDV-RPT-005) rather than read without end. */
    public static final int MAX_ITEMS = 200_000;
    /** Delivery lines shown per sending system; the rest are in the CSV. */
    public static final int LINES_PER_SYSTEM = 50;
    public static final String EXPORT_ACTION = "INTAKE_REPORT_EXPORTED";
    static final Duration NOTICE_WINDOW = Duration.ofDays(30);
    static final int NOTICE_LIMIT = 50;
    public static final String NOTICE_MESSAGE = "An uncorrected copy of a corrected document was sent again; nothing was stored";
    static final String CSV_HEADER_START = "SHA-256 checksum,Request ID,Sending system,Primary identifier,Pages,";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private final IntakeReportRepository reports;
    private final AuditEventPort audit;
    private final ZoneId installDefault;
    private final Clock clock;

    @Autowired
    public IntakeReportService(IntakeReportRepository reports, AuditEventPort audit,
                               @Value("${sdv.tenant-defaults.time-zone:UTC}") String installDefaultZone) {
        this(reports, audit, installDefaultZone, Clock.systemUTC());
    }

    IntakeReportService(IntakeReportRepository reports, AuditEventPort audit, String installDefaultZone, Clock clock) {
        this.reports = Objects.requireNonNull(reports, "reports");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.installDefault = installZone(installDefaultZone);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The install default, checked when the server starts: an unknown zone stops it. */
    static ZoneId installZone(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("sdv.tenant-defaults.time-zone must name a time zone, for example America/New_York.");
        }
        try {
            return ZoneId.of(value.trim());
        } catch (DateTimeException ex) {
            throw new IllegalStateException("sdv.tenant-defaults.time-zone is not a time zone Vero knows: " + value.trim(), ex);
        }
    }

    // ------------------------------------------------------------------ the report

    public IntakeReport intake(UUID tenantId, String from, String to, String sourceId) {
        Scope scope = scope(tenantId, from, to, sourceId);
        List<Item> items = read(tenantId, scope);
        return build(tenantId, scope, items);
    }

    /** One line per document (and per refusal and duplicate), oldest first; the export is audited. */
    public Export export(UUID tenantId, String actorId, String correlationId, String from, String to, String sourceId) {
        Scope scope = scope(tenantId, from, to, sourceId);
        List<Item> items = read(tenantId, scope);
        Map<UUID, PrimaryIdentifier> pids = reports.findPrimaryIdentifiers(tenantId, submissionIds(items));
        Map<UUID, String> names = new HashMap<>();
        scope.systems().forEach(s -> names.put(s.sourceId(), s.displayName()));

        StringBuilder out = new StringBuilder("﻿").append(CSV_HEADER_START)
                .append(AuditCsv.cell("Received (" + scope.zone().getId() + ")")).append(',')
                .append("Received (UTC),Outcome,Refusal code\r\n");
        int rows = 0;
        for (Item item : items) {
            if (item.kind() == Item.Kind.DELIVERY) continue;
            PrimaryIdentifier pid = item.submissionId() == null ? null : pids.get(item.submissionId());
            Instant at = item.at().truncatedTo(ChronoUnit.SECONDS);
            out.append(AuditCsv.cell(item.contentSha256())).append(',')
                    .append(AuditCsv.cell(item.requestId())).append(',')
                    .append(AuditCsv.cell(names.get(item.sourceId()))).append(',')
                    .append(AuditCsv.cell(pid == null ? null : pid.value())).append(',')
                    .append(AuditCsv.cell(item.pages() == null ? null : item.pages().toString())).append(',')
                    .append(AuditCsv.cell(at.atZone(scope.zone()).toOffsetDateTime().toString())).append(',')
                    .append(AuditCsv.cell(at.toString())).append(',')
                    .append(AuditCsv.cell(outcome(item))).append(',')
                    .append(AuditCsv.cell(item.kind() == Item.Kind.REFUSED ? item.refusalCode() : null)).append("\r\n");
            rows++;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", scope.from().toString());
        details.put("to", scope.to().toString());
        details.put("sourceId", scope.sourceId() == null ? null : scope.sourceId().toString());
        details.put("timeZone", scope.zone().getId());
        details.put("rowCount", rows);
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId, EXPORT_ACTION, "REPORT",
                "DAILY_INTAKE", clock.instant(), correlationId, AuditOutcome.SUCCESS, json(details)));
        String filename = "vero-daily-intake-" + scope.from() + (scope.to().equals(scope.from()) ? "" : "-to-" + scope.to()) + ".csv";
        return new Export(out.toString().getBytes(StandardCharsets.UTF_8), rows, filename);
    }

    static String outcome(Item item) {
        return switch (item.kind()) {
            case DOCUMENT -> "PROCESSING_FAILED".equals(item.documentStatus()) ? "FAILED" : "ACCEPTED";
            case DUPLICATE -> "DUPLICATE_ALREADY_STORED";
            case REFUSED -> "REFUSED";
            case DELIVERY -> throw new IllegalArgumentException("A delivery has no document line.");
        };
    }

    private Scope scope(UUID tenantId, String from, String to, String sourceId) {
        ZoneId zone;
        String zoneSource;
        String own = reports.findTenantTimeZone(tenantId).orElse(null);
        if (own == null) {
            zone = installDefault;
            zoneSource = "INSTALL_DEFAULT";
        } else {
            try {
                zone = ZoneId.of(own);
            } catch (DateTimeException ex) {
                throw new CodedRefusal.Conflict(SdvErrorCode.RPT_004,
                        "The tenant's time zone setting is not a time zone Vero knows, so the report can't work out its days. Ask Tenant IT to correct it.");
            }
            zoneSource = "TENANT";
        }
        LocalDate today = LocalDate.now(clock.withZone(zone));
        boolean noFrom = from == null || from.isBlank();
        boolean noTo = to == null || to.isBlank();
        LocalDate first;
        LocalDate last;
        if (noFrom && noTo) {
            first = today;
            last = today;
        } else if (noFrom || noTo) {
            throw badRange("Send both from and to (YYYY-MM-DD), or neither for today.");
        } else {
            first = day(from);
            last = day(to);
            if (last.isBefore(first)) throw badRange("to is before from.");
            if (ChronoUnit.DAYS.between(first, last) + 1 > MAX_DAYS) {
                throw badRange("A report covers at most " + MAX_DAYS + " days.");
            }
        }
        List<SendingSystem> systems = reports.findSendingSystems(tenantId);
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
        return new Scope(zone, zoneSource, today, first, last, first.atStartOfDay(zone).toInstant(),
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
        return new CodedRefusal.BadRequest(SdvErrorCode.RPT_002, "The day or date range isn't valid: " + why);
    }

    private static CodedRefusal.BadRequest unknownSource() {
        return new CodedRefusal.BadRequest(SdvErrorCode.RPT_003, "The sending system isn't one of this tenant's.");
    }

    private List<Item> read(UUID tenantId, Scope scope) {
        List<Item> items = reports.findItems(tenantId, scope.sourceId(), scope.start(), scope.end(), MAX_ITEMS + 1);
        if (items.size() > MAX_ITEMS) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.RPT_005, "The range holds more than "
                    + String.format("%,d", MAX_ITEMS) + " lines, more than one report reads. Choose fewer days or one sending system.");
        }
        return items;
    }

    private static Set<UUID> submissionIds(List<Item> items) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (Item item : items) if (item.submissionId() != null) ids.add(item.submissionId());
        return ids;
    }

    private IntakeReport build(UUID tenantId, Scope scope, List<Item> items) {
        Map<UUID, PrimaryIdentifier> pids = reports.findPrimaryIdentifiers(tenantId, submissionIds(items));
        Set<UUID> createdInRange = new HashSet<>();
        // Sending system -> delivery -> its items, in time order (the items come oldest first).
        Map<UUID, Map<String, List<Item>>> bySystem = new LinkedHashMap<>();
        for (Item item : items) {
            if (item.kind() == Item.Kind.DELIVERY) createdInRange.add(item.submissionId());
            String delivery = item.submissionId() != null ? "s:" + item.submissionId() : "r:" + item.itemId();
            bySystem.computeIfAbsent(item.sourceId(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(delivery, k -> new ArrayList<>()).add(item);
        }
        Map<UUID, String> names = new HashMap<>();
        scope.systems().forEach(s -> names.put(s.sourceId(), s.displayName()));

        List<SystemBlock> blocks = new ArrayList<>();
        Set<String> allContracts = new LinkedHashSet<>();
        Map<String, Integer> labels = new HashMap<>();
        Map<String, Integer> codes = new TreeMap<>();
        int deliveries = 0, documents = 0, duplicates = 0, refused = 0, failed = 0;
        long pages = 0;
        for (Map.Entry<UUID, Map<String, List<Item>>> system : bySystem.entrySet()) {
            List<DeliveryLine> lines = new ArrayList<>();
            Set<String> contracts = new HashSet<>();
            int sDocs = 0, sDup = 0, sRef = 0, sFail = 0;
            long sPages = 0;
            for (List<Item> delivery : system.getValue().values()) {
                Item first = delivery.get(0);
                PrimaryIdentifier pid = first.submissionId() == null ? null : pids.get(first.submissionId());
                String contract = contractKey(first.submissionId(), pid);
                if (contract != null) {
                    contracts.add(contract);
                    allContracts.add(contract);
                }
                if (pid != null && pid.label() != null) labels.merge(pid.label(), 1, Integer::sum);
                int d = 0, dup = 0, ref = 0, fail = 0;
                long p = 0;
                List<String> lineCodes = new ArrayList<>();
                String requestId = null;
                for (Item item : delivery) {
                    if (requestId == null) requestId = item.requestId();
                    switch (item.kind()) {
                        case DOCUMENT -> {
                            if ("PROCESSING_FAILED".equals(item.documentStatus())) fail++;
                            else {
                                d++;
                                p += item.pages() == null ? 0 : item.pages();
                            }
                        }
                        case DUPLICATE -> dup++;
                        case REFUSED -> {
                            ref++;
                            String code = item.refusalCode() == null ? "REFUSED" : item.refusalCode();
                            if (!lineCodes.contains(code)) lineCodes.add(code);
                            codes.merge(code, 1, Integer::sum);
                        }
                        case DELIVERY -> { }
                    }
                }
                sDocs += d;
                sPages += p;
                sDup += dup;
                sRef += ref;
                sFail += fail;
                lines.add(new DeliveryLine(requestId, first.at(),
                        LOCAL.format(first.at().atZone(scope.zone())), pid == null ? null : pid.value(),
                        d, p, dup, ref, List.copyOf(lineCodes), fail));
            }
            int shown = Math.min(lines.size(), LINES_PER_SYSTEM);
            blocks.add(new SystemBlock(system.getKey(), names.getOrDefault(system.getKey(), "Sending system"),
                    new SystemTotals(lines.size(), contracts.size(), sDocs, sPages, sDup, sRef, sFail),
                    List.copyOf(lines.subList(0, shown)), lines.size() - shown));
            deliveries += lines.size();
            documents += sDocs;
            pages += sPages;
            duplicates += sDup;
            refused += sRef;
            failed += sFail;
        }
        blocks.sort(Comparator.comparingInt((SystemBlock b) -> b.totals().deliveries()).reversed()
                .thenComparing(SystemBlock::displayName, String.CASE_INSENSITIVE_ORDER));

        // New or updated: a value is new when no submission created before the range carried it.
        Set<String> fieldKeys = new HashSet<>();
        Set<String> values = new HashSet<>();
        for (PrimaryIdentifier pid : pids.values()) {
            fieldKeys.add(pid.fieldKey());
            values.add(compared(pid));
        }
        Set<String> known = values.isEmpty() ? Set.of()
                : reports.findValuesKnownBefore(tenantId, fieldKeys, values, scope.start());
        int fresh = 0;
        for (String contract : allContracts) {
            boolean isNew = contract.startsWith("v:")
                    ? !known.contains(contract.substring(2))
                    : createdInRange.contains(UUID.fromString(contract.substring(2)));
            if (isNew) fresh++;
        }
        String label = labels.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey).orElse(null);
        List<CodeCount> codeCounts = codes.entrySet().stream().map(e -> new CodeCount(e.getKey(), e.getValue())).toList();
        Totals totals = new Totals(deliveries, blocks.size(), documents, pages, duplicates, allContracts.size(), fresh,
                allContracts.size() - fresh, refused, codeCounts, failed);
        return new IntakeReport(scope.from().toString(), scope.to().toString(), scope.today().toString(),
                scope.zone().getId(), scope.zoneSource(), scope.sourceId(), label, scope.systems(), totals,
                List.copyOf(blocks), LINES_PER_SYSTEM);
    }

    private static String compared(PrimaryIdentifier pid) {
        return pid.normalizedValue() != null && !pid.normalizedValue().isBlank() ? pid.normalizedValue() : pid.value();
    }

    /** A contract: its primary identifier value; without one, the submission is a record on its own; else none. */
    private static String contractKey(UUID submissionId, PrimaryIdentifier pid) {
        if (pid != null) return "v:" + compared(pid);
        return submissionId == null ? null : "s:" + submissionId;
    }

    // ------------------------------------------------------------------ notices for stewards

    /** Uncorrected copies received in the last 30 days, newest first: for the record's Document stewards. */
    public List<IntakeNotice> uncorrectedCopyNotices(UUID tenantId) {
        return reports.findUncorrectedCopies(tenantId, clock.instant().minus(NOTICE_WINDOW), NOTICE_LIMIT).stream()
                .map(c -> new IntakeNotice(c.noticeId(), "UNCORRECTED_COPY_RECEIVED", c.receivedAt(), c.sendingSystem(),
                        c.primaryIdentifier(), c.primaryIdentifierLabel(), NOTICE_MESSAGE))
                .toList();
    }

    private static String json(Map<String, Object> details) {
        try {
            return JSON.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("The export could not be audited, so it was not made.", ex);
        }
    }

    // ------------------------------------------------------------------ views

    private record Scope(ZoneId zone, String zoneSource, LocalDate today, LocalDate from, LocalDate to, Instant start,
                         Instant end, UUID sourceId, List<SendingSystem> systems) { }

    /**
     * from/to/today: days in the tenant's time zone. timeZoneSource: TENANT (its own setting) or INSTALL_DEFAULT.
     * primaryIdentifierLabel: the display name of the primary identifier most deliveries use ("Contract number"), null
     * when none has one. sendingSystems: every sending system of the tenant, for the filter.
     */
    public record IntakeReport(String from, String to, String today, String timeZone, String timeZoneSource,
                               UUID sourceId, String primaryIdentifierLabel, List<SendingSystem> sendingSystems,
                               Totals totals, List<SystemBlock> systems, int linesPerSystem) { }

    public record Totals(int deliveries, int sendingSystems, int documents, long pages, int duplicates, int contracts,
                         int newContracts, int updatedContracts, int refused, List<CodeCount> refusedCodes,
                         int failed) { }

    public record CodeCount(String code, int count) { }

    /** deliveries: the first LINES_PER_SYSTEM; moreDeliveries: how many more there are (all are in the CSV). */
    public record SystemBlock(UUID sourceId, String displayName, SystemTotals totals, List<DeliveryLine> deliveries,
                              int moreDeliveries) { }

    public record SystemTotals(int deliveries, int contracts, int documents, long pages, int duplicates, int refused,
                               int failed) { }

    /** receivedLocal: the first time, in the tenant's time zone (yyyy-MM-ddTHH:mm). */
    public record DeliveryLine(String requestId, Instant receivedAt, String receivedLocal, String primaryIdentifier,
                               int documents, long pages, int duplicates, int refused, List<String> refusedCodes,
                               int failed) { }

    public record Export(byte[] csv, int rowCount, String filename) { }

    public record IntakeNotice(UUID noticeId, String kind, Instant receivedAt, String sendingSystem,
                               String primaryIdentifier, String primaryIdentifierLabel, String message) { }
}
