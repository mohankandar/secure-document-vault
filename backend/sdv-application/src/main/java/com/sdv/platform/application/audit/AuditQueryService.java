package com.sdv.platform.application.audit;

import com.sdv.platform.domain.error.InternalCheckFailed;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.AuditQueryPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class AuditQueryService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    /** Task 0011 export (owner, 2026-11-18): at most this many rows; a bigger list is refused with a plain message. */
    public static final int EXPORT_MAX_ROWS = 10_000;
    public static final String EXPORT_TOO_BIG = "This list has more than 10,000 events, so it can't be exported in one file. "
            + "Choose a shorter period or more filters, then export again.";
    private static final int MAX_ACTION_CODES = 80;
    private static final Pattern ACTION_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,119}");

    private final AuditQueryPort query;
    private final AuditEventPort audit;
    private final DocumentAccessGuard access;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public AuditQueryService(AuditQueryPort query, AuditEventPort audit, DocumentAccessGuard access) {
        this(query, audit, access, Clock.systemUTC());
    }

    AuditQueryService(AuditQueryPort query, AuditEventPort audit, DocumentAccessGuard access, Clock clock) {
        this.query = query;
        this.audit = audit;
        this.access = java.util.Objects.requireNonNull(access, "access");
        this.clock = clock;
    }

    public AuditPage search(UUID tenantId,
                            String actorId,
                            SearchRequest request,
                            String correlationId) {
        if (tenantId == null) throw new InternalCheckFailed("tenantId is required.");
        SearchRequest safe = request == null ? new SearchRequest(null, null, null, null, null, null, null, null, null, null, null, null) : request;
        int limit = safe.limit() == null ? DEFAULT_LIMIT : safe.limit();
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ".");
        validate(safe);

        Cursor cursor = decodeCursor(safe.cursor());
        AuditQueryPort.Filter filter = filter(safe, cursor, null, null);

        List<AuditQueryPort.Row> fetched = query.findRows(tenantId, filter, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<AuditQueryPort.Row> page = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            AuditEvent last = page.get(page.size() - 1).event();
            nextCursor = encodeCursor(last.occurredAt(), last.auditEventId());
        }

        audit.append(new AuditEvent(
                UUID.randomUUID(), tenantId, "USER", actorId,
                "AUDIT_VIEWED", "TENANT", tenantId.toString(), clock.instant(), correlationId,
                AuditOutcome.SUCCESS,
                "{\"resultCount\":" + page.size() + ",\"filtered\":" + hasAnyFilter(safe) + "}"));

        return new AuditPage(entries(tenantId, page), nextCursor, hasMore);
    }

    /**
     * Task 0011 Part B.6, owner's option (a): the server builds the CSV of the filtered list, from the same filters as
     * the list plus the screen's activity group ({@code actions}) and its hidden routine events ({@code excludeActions}),
     * over the whole period (not only the loaded pages). At most {@link #EXPORT_MAX_ROWS} rows; a bigger list is refused
     * and nothing is exported. The export is recorded as AUDIT_EXPORTED with the filters and the row count before the
     * file is handed back; if that record can't be written, nothing is exported (fail closed).
     */
    public AuditExport export(UUID tenantId, String actorId, ExportRequest request, String correlationId) {
        if (tenantId == null) throw new InternalCheckFailed("tenantId is required.");
        if (request == null) throw new InternalCheckFailed("export filters are required.");
        SearchRequest search = request.search() == null
                ? new SearchRequest(null, null, null, null, null, null, null, null, null, null, null, null)
                : request.search();
        if (search.cursor() != null && !search.cursor().isBlank()) throw new IllegalArgumentException("An export always starts from the newest event.");
        validate(search);
        Set<String> actions = actionCodes(request.actions(), "actions");
        Set<String> excluded = actionCodes(request.excludeActions(), "excludeActions");
        if (actions != null && actions.isEmpty()) throw new IllegalArgumentException("actions must name at least one action code.");

        List<AuditQueryPort.Row> rows = query.findRows(tenantId, filter(search, null, actions, excluded), EXPORT_MAX_ROWS + 1);
        if (rows.size() > EXPORT_MAX_ROWS) throw new ExportTooLargeException(EXPORT_TOO_BIG);
        List<AuditEntry> entries = entries(tenantId, rows);
        byte[] csv = AuditCsv.write(entries);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rowCount", entries.size());
        details.put("format", "CSV");
        details.put("filters", appliedFilters(search, actions, excluded));
        audit.append(new AuditEvent(
                UUID.randomUUID(), tenantId, "USER", actorId,
                "AUDIT_EXPORTED", "TENANT", tenantId.toString(), clock.instant(), correlationId,
                AuditOutcome.SUCCESS, toJson(details)));
        return new AuditExport(csv, entries.size());
    }

    /**
     * Task 0027 (0.4, separation rule 4): admin views never show a file name, at any level. A document event names
     * its document by the record's primary identifier (recordNumber), the document ID, its effective level, its
     * received date and its page count.
     */
    private List<AuditEntry> entries(UUID tenantId, List<AuditQueryPort.Row> rows) {
        List<DocumentAccessGuard.DocumentLevelRef> refs = rows.stream()
                .map(AuditQueryPort.Row::document)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<UUID, SensitivityLevel> levels = refs.isEmpty() ? Map.of() : access.effectiveLevels(tenantId, refs);
        return rows.stream().map(row -> AuditEntry.from(row,
                row.document() == null ? null : levels.get(row.document().documentId()))).toList();
    }

    private static Map<String, Object> appliedFilters(SearchRequest s, Set<String> actions, Set<String> excluded) {
        Map<String, Object> f = new LinkedHashMap<>();
        put(f, "action", blankToNull(s.action()));
        put(f, "outcome", s.outcome() == null ? null : s.outcome().name());
        put(f, "actorType", blankToNull(s.actorType()));
        put(f, "actorId", blankToNull(s.actorId()));
        put(f, "targetType", blankToNull(s.targetType()));
        put(f, "targetId", blankToNull(s.targetId()));
        put(f, "correlationId", blankToNull(s.correlationId()));
        put(f, "from", s.from() == null ? null : s.from().toString());
        put(f, "to", s.to() == null ? null : s.to().toString());
        put(f, "recordNumber", blankToNull(s.recordNumber()));
        put(f, "sensitivityLevel", s.sensitivityLevel() == null || s.sensitivityLevel().isEmpty() ? null : s.sensitivityLevel());
        put(f, "actions", actions == null ? null : List.copyOf(new TreeSet<>(actions)));
        put(f, "excludeActions", excluded == null || excluded.isEmpty() ? null : List.copyOf(new TreeSet<>(excluded)));
        return f;
    }

    private static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw InternalCheckFailed.auditNotWritten("The export could not be recorded, so nothing was exported.", ex);
        }
    }

    /** Null: no such filter. Each value must look like an action code; anything else is refused (fail closed). */
    private static Set<String> actionCodes(List<String> values, String name) {
        if (values == null) return null;
        if (values.size() > MAX_ACTION_CODES) throw new IllegalArgumentException(name + " names more than " + MAX_ACTION_CODES + " action codes.");
        Set<String> codes = new TreeSet<>();
        for (String value : values) {
            String code = value == null ? "" : value.strip();
            if (code.isEmpty()) continue;
            if (!ACTION_CODE.matcher(code).matches()) throw new IllegalArgumentException(name + " has a value that is not an action code.");
            codes.add(code);
        }
        return codes;
    }

    private static void validate(SearchRequest safe) {
        validateLength(safe.action(), "action", 120);
        validateLength(safe.actorType(), "actorType", 40);
        validateLength(safe.actorId(), "actorId", 300);
        validateLength(safe.targetType(), "targetType", 80);
        validateLength(safe.targetId(), "targetId", 300);
        validateLength(safe.correlationId(), "correlationId", 200);
        validateLength(safe.recordNumber(), "recordNumber", 300);
        levelFilter(safe.sensitivityLevel());
        if (safe.from() != null && safe.to() != null && safe.from().isAfter(safe.to())) {
            throw new IllegalArgumentException("from cannot be after to.");
        }
    }

    private static AuditQueryPort.Filter filter(SearchRequest safe, Cursor cursor, Set<String> actions, Set<String> excluded) {
        return new AuditQueryPort.Filter(
                blankToNull(safe.action()), safe.outcome(), blankToNull(safe.actorType()), blankToNull(safe.actorId()),
                blankToNull(safe.targetType()), blankToNull(safe.targetId()), blankToNull(safe.correlationId()),
                safe.from(), safe.to(), cursor == null ? null : cursor.occurredAt(), cursor == null ? null : cursor.auditEventId(),
                blankToNull(safe.recordNumber() == null ? null : safe.recordNumber().strip()), actions, excluded,
                levelFilter(safe.sensitivityLevel()));
    }

    private static boolean hasAnyFilter(SearchRequest request) {
        return blankToNull(request.action()) != null || request.outcome() != null || blankToNull(request.actorType()) != null
                || blankToNull(request.actorId()) != null || blankToNull(request.targetType()) != null
                || blankToNull(request.targetId()) != null || blankToNull(request.correlationId()) != null
                || request.from() != null || request.to() != null || blankToNull(request.recordNumber()) != null
                || (request.sensitivityLevel() != null && !request.sensitivityLevel().isEmpty());
    }

    private static void validateLength(String value, String name, int max) {
        if (value != null && value.trim().length() > max) throw new IllegalArgumentException(name + " exceeds " + max + " characters.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String encodeCursor(Instant occurredAt, UUID id) {
        String raw = occurredAt + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String token) {
        if (token == null || token.isBlank()) return null;
        if (token.length() > 300) throw new IllegalArgumentException("audit cursor is invalid.");
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 2);
            if (parts.length != 2) throw new IllegalArgumentException("audit cursor is invalid.");
            return new Cursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new IllegalArgumentException("audit cursor is invalid.");
        }
    }

    private record Cursor(Instant occurredAt, UUID auditEventId) { }

    /**
     * sensitivityLevel (task 0030, item 6): only events that recorded this level for their document when they
     * happened. Exactly STANDARD, CONFIDENTIAL or HIGHLY_CONFIDENTIAL; anything else is refused with SDV-SEN-019.
     */
    /**
     * Task 0050: the events of a sealed audit report: the list's own filters over the whole period, oldest first, as
     * the audit list names them (never a file name or title). At most {@link #EXPORT_MAX_ROWS}; a bigger report is
     * refused with {@link ExportTooLargeException}. Not audited here: the report service records the report itself.
     */
    public List<AuditEntry> sealedEntries(UUID tenantId, SearchRequest search) {
        if (tenantId == null) throw new InternalCheckFailed("tenantId is required.");
        if (search == null) throw new InternalCheckFailed("report filters are required.");
        if (search.cursor() != null && !search.cursor().isBlank()) throw new IllegalArgumentException("A report always covers its whole period.");
        validate(search);
        List<AuditQueryPort.Row> rows = query.findRows(tenantId, filter(search, null, null, null), EXPORT_MAX_ROWS + 1);
        if (rows.size() > EXPORT_MAX_ROWS) throw new ExportTooLargeException(EXPORT_TOO_BIG);
        List<AuditEntry> entries = new java.util.ArrayList<>(entries(tenantId, rows));
        entries.sort(java.util.Comparator.comparing(AuditEntry::occurredAt).thenComparing(e -> e.auditEventId().toString()));
        return List.copyOf(entries);
    }

    public record SearchRequest(
            String action,
            AuditOutcome outcome,
            String actorType,
            String actorId,
            String targetType,
            String targetId,
            String correlationId,
            Instant from,
            Instant to,
            Integer limit,
            String cursor,
            String recordNumber,
            String sensitivityLevel) {
        public SearchRequest(String action, AuditOutcome outcome, String actorType, String actorId, String targetType,
                             String targetId, String correlationId, Instant from, Instant to, Integer limit,
                             String cursor, String recordNumber) {
            this(action, outcome, actorType, actorId, targetType, targetId, correlationId, from, to, limit, cursor,
                    recordNumber, null);
        }
    }

    /** Null for no level filter; a level for exactly its name; a coded refusal otherwise (fail closed). */
    static SensitivityLevel levelFilter(String value) {
        if (value == null || value.isEmpty()) return null;
        for (SensitivityLevel level : SensitivityLevel.values()) {
            if (level.name().equals(value)) return level;
        }
        throw new CodedRefusal.BadRequest(SdvErrorCode.SEN_019,
                "The sensitivity filter must be one of STANDARD, CONFIDENTIAL, HIGHLY_CONFIDENTIAL (exactly, in capitals); leave it out for any level.");
    }

    /** The list's filters, plus the activity group's action codes and the routine codes the screen hides. */
    public record ExportRequest(SearchRequest search, List<String> actions, List<String> excludeActions) { }

    public record AuditExport(byte[] csv, int rowCount) { }

    /** The filtered list is bigger than {@link #EXPORT_MAX_ROWS}; nothing was exported. */
    public static class ExportTooLargeException extends RuntimeException {
        public ExportTooLargeException(String message) { super(message); }
    }

    public record AuditEntry(
            UUID auditEventId,
            String actorType,
            String actorId,
            String action,
            String targetType,
            String targetId,
            Instant occurredAt,
            String correlationId,
            AuditOutcome outcome,
            String safeDetailsJson,
            /* Null for events before migration 020: show actorId. */
            String actorDisplayName,
            /* Document events: looked up when the list is read. recordNumber is the record's primary identifier. */
            String recordNumber,
            /* Task 0027 (0.4): the document instead of its file name, null when the event names no stored document.
               documentId is for support; sensitivityLevel is the effective level. */
            UUID documentId,
            SensitivityLevel sensitivityLevel,
            Instant documentReceivedAt,
            Integer documentPageCount) {
        static AuditEntry from(AuditQueryPort.Row row, SensitivityLevel level) {
            AuditEvent event = row.event();
            return new AuditEntry(event.auditEventId(), event.actorType(), event.actorId(), event.action(),
                    event.targetType(), event.targetId(), event.occurredAt(), event.correlationId(),
                    event.outcome(), event.safeDetailsJson(), event.actorDisplayName(), row.recordNumber(),
                    row.document() == null ? null : row.document().documentId(), level,
                    row.documentReceivedAt(), row.documentPageCount());
        }

        static AuditEntry from(AuditQueryPort.Row row) {
            return from(row, null);
        }
    }

    public record AuditPage(List<AuditEntry> events, String nextCursor, boolean hasMore) {
        public AuditPage { events = List.copyOf(events); }
    }
}
