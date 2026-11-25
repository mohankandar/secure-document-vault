package com.sdv.platform.application.restrictions;

import com.sdv.platform.application.port.RestrictionsRepository;
import com.sdv.platform.application.port.RestrictionsRepository.Holds;
import com.sdv.platform.application.port.RestrictionsRepository.Identifier;
import com.sdv.platform.application.port.RestrictionsRepository.LevelChange;
import com.sdv.platform.application.port.RestrictionsRepository.Quarantine;
import com.sdv.platform.application.port.RestrictionsRepository.RecordLevel;
import com.sdv.platform.application.port.RestrictionsRepository.Waiting;
import com.sdv.platform.application.reports.StorageReportService;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0055: the Business Admin's read-only Restrictions page: everything restricted, in one place, each part linking
 * to the page that acts on it (where the Business Admin may go).
 * <ol>
 *   <li>Confidential and Highly Confidential documents by sending system, from the Storage and retention report's
 *       counts (task 0053, kept in the background), so no document is read here;</li>
 *   <li>records with a record (contract) level: how many, and a page of them by primary identifier, level and date
 *       set;</li>
 *   <li>level changes of the last 30 days, a page at a time: who, when, document or record, from, to;</li>
 *   <li>lowering requests waiting: how many and the oldest (Approvals);</li>
 *   <li>legal holds in place and the documents they hold (Legal holds);</li>
 *   <li>open quarantine incidents: how many, their documents, and those being prepared (counts only).</li>
 * </ol>
 * Separation rule 4: a record is named by its primary identifier only, never the internal document ID; never a title,
 * file name, content or reason.
 */
@Service
public class RestrictionsService {
    public static final int PAGE = 50;
    public static final Duration RECENT = Duration.ofDays(30);

    private final RestrictionsRepository restrictions;
    private final StorageReportService storage;
    private final Clock clock;

    @Autowired
    public RestrictionsService(RestrictionsRepository restrictions, StorageReportService storage) {
        this(restrictions, storage, Clock.systemUTC());
    }

    RestrictionsService(RestrictionsRepository restrictions, StorageReportService storage, Clock clock) {
        this.restrictions = Objects.requireNonNull(restrictions, "restrictions");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Overview overview(UUID tenantId) {
        StorageReportService.StorageReport counted = storage.report(tenantId);
        Map<UUID, long[]> bySystem = new LinkedHashMap<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        for (StorageReportService.StoredRow s : counted.stored()) {
            if (s.level() == SensitivityLevel.STANDARD) continue;
            names.putIfAbsent(s.sourceId(), s.sendingSystem());
            bySystem.computeIfAbsent(s.sourceId(), k -> new long[2])[s.level() == SensitivityLevel.CONFIDENTIAL ? 0 : 1] += s.documents();
        }
        List<SystemLevels> levels = new ArrayList<>();
        long[] total = new long[2];
        bySystem.forEach((id, n) -> {
            levels.add(new SystemLevels(names.get(id), n[0], n[1]));
            total[0] += n[0]; total[1] += n[1];
        });
        Instant since = clock.instant().minus(RECENT);
        Waiting waiting = restrictions.findWaitingLowerings(tenantId);
        Holds holds = restrictions.countHolds(tenantId);
        Quarantine q = restrictions.countQuarantine(tenantId);
        return new Overview(counted.countedAt(), levels, total[0], total[1], restrictions.countRestrictedRecords(tenantId),
                records(tenantId, null), restrictions.countLevelChanges(tenantId, since), changes(tenantId, null),
                new Lowerings(waiting.count(), waiting.oldest()), new LegalHolds(holds.holds(), holds.documents()),
                new QuarantineCounts(q.incidents(), q.documents(), q.preparing()));
    }

    /** A page of restricted records, after the previous page's {@code next}. */
    public RecordPage records(UUID tenantId, String after) {
        RecordLevel from = null;
        if (after != null && !after.isBlank()) {
            String[] k = cursor(after);
            from = new RecordLevel(UUID.fromString(k[1]), null, null, null, Instant.parse(k[0]));
        }
        List<RecordLevel> rows = restrictions.findRestrictedRecords(tenantId, from, PAGE + 1);
        String next = null;
        if (rows.size() > PAGE) {
            rows = rows.subList(0, PAGE);
            RecordLevel last = rows.getLast();
            next = cursor(last.setAt(), last.levelId());
        }
        Names names = names(tenantId, rows.stream().map(r -> new Ref(r.recordId(), null)).toList());
        List<RestrictedRecord> items = new ArrayList<>();
        for (RecordLevel r : rows) {
            Identifier id = names.of(new Ref(r.recordId(), null));
            items.add(new RestrictedRecord(id.label(), id.value(), SensitivityLevel.valueOf(r.level()), r.setAt()));
        }
        return new RecordPage(items, next);
    }

    /** A page of the last 30 days' level changes, after the previous page's {@code next}. */
    public ChangePage changes(UUID tenantId, String after) {
        LevelChange from = null;
        if (after != null && !after.isBlank()) {
            String[] k = cursor(after);
            from = new LevelChange(UUID.fromString(k[1]), null, Instant.parse(k[0]), null, null, null, null, null, null);
        }
        Instant since = clock.instant().minus(RECENT);
        List<LevelChange> rows = restrictions.findLevelChanges(tenantId, since, from, PAGE + 1);
        String next = null;
        if (rows.size() > PAGE) {
            rows = rows.subList(0, PAGE);
            LevelChange last = rows.getLast();
            next = cursor(last.changedAt(), last.changeId());
        }
        Names names = names(tenantId, rows.stream().map(c -> new Ref(c.recordId(), c.submissionId())).toList());
        List<Change> items = new ArrayList<>();
        for (LevelChange c : rows) {
            Identifier id = names.of(new Ref(c.recordId(), c.submissionId()));
            items.add(new Change(c.changedBy(), c.changedAt(), c.scope(), c.fromLevel(), c.toLevel(), id.label(), id.value()));
        }
        return new ChangePage(items, next);
    }

    // ------------------------------------------------------------------ naming a record by its primary identifier

    /** A record by its ID, or as a document's submission. */
    private record Ref(UUID recordId, UUID submissionId) { }

    private record Names(Map<UUID, Identifier> submissions, Map<UUID, Identifier> records) {
        static final Identifier UNKNOWN = new Identifier(null, null);

        Identifier of(Ref ref) {
            if (ref.submissionId() != null) return submissions.getOrDefault(ref.submissionId(), UNKNOWN);
            return ref.recordId() == null ? UNKNOWN : records.getOrDefault(ref.recordId(), UNKNOWN);
        }
    }

    private Names names(UUID tenantId, List<Ref> refs) {
        Set<UUID> submissions = new LinkedHashSet<>(), recordIds = new LinkedHashSet<>();
        for (Ref r : refs) {
            if (r.submissionId() != null) submissions.add(r.submissionId());
            else if (r.recordId() != null) recordIds.add(r.recordId());
        }
        return new Names(restrictions.findSubmissionIdentifiers(tenantId, submissions), restrictions.findRecordIdentifiers(tenantId, recordIds));
    }

    // ------------------------------------------------------------------ page positions

    static String cursor(Instant at, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString((at + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    private static String[] cursor(String value) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value.strip()), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            Instant.parse(parts[0]);
            UUID.fromString(parts[1]);
            return parts;
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.RST_002, "This page of the list isn't known. Start again from the first page.");
        }
    }

    // ------------------------------------------------------------------ views

    /**
     * levelsCountedAt: when the documents were counted by level (the Storage and retention report's counts). Each list
     * names records by their primary identifier only.
     */
    public record Overview(String levelsCountedAt, List<SystemLevels> levels, long confidential, long highlyConfidential,
                           long restrictedRecords, RecordPage records, long levelChanges, ChangePage changes,
                           Lowerings lowerings, LegalHolds legalHolds, QuarantineCounts quarantine) { }

    public record SystemLevels(String sendingSystem, long confidential, long highlyConfidential) { }

    public record RestrictedRecord(String identifierLabel, String identifier, SensitivityLevel level, Instant setAt) { }

    public record RecordPage(List<RestrictedRecord> items, String next) { }

    /** scope: DOCUMENT (one document of the record) or RECORD (the whole record). */
    public record Change(String changedBy, Instant changedAt, String scope, String fromLevel, String toLevel,
                         String identifierLabel, String identifier) { }

    public record ChangePage(List<Change> items, String next) { }

    public record Lowerings(long waiting, Instant oldest) { }

    public record LegalHolds(long holds, long documents) { }

    public record QuarantineCounts(long incidents, long documents, long preparing) { }
}
