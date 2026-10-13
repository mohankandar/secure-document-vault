package com.sdv.platform.application.audit;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.AuditQueryPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class AuditQueryService {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final AuditQueryPort query;
    private final AuditEventPort audit;
    private final Clock clock = Clock.systemUTC();

    public AuditQueryService(AuditQueryPort query, AuditEventPort audit) {
        this.query = query;
        this.audit = audit;
    }

    public AuditPage search(UUID tenantId,
                            String actorId,
                            SearchRequest request,
                            String correlationId) {
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required.");
        SearchRequest safe = request == null ? new SearchRequest(null, null, null, null, null, null, null, null, null, null, null) : request;
        int limit = safe.limit() == null ? DEFAULT_LIMIT : safe.limit();
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT + ".");
        validateLength(safe.action(), "action", 120);
        validateLength(safe.actorType(), "actorType", 40);
        validateLength(safe.actorId(), "actorId", 300);
        validateLength(safe.targetType(), "targetType", 80);
        validateLength(safe.targetId(), "targetId", 300);
        validateLength(safe.correlationId(), "correlationId", 200);
        if (safe.from() != null && safe.to() != null && safe.from().isAfter(safe.to())) {
            throw new IllegalArgumentException("from cannot be after to.");
        }

        Cursor cursor = decodeCursor(safe.cursor());
        AuditQueryPort.Filter filter = new AuditQueryPort.Filter(
                blankToNull(safe.action()), safe.outcome(), blankToNull(safe.actorType()), blankToNull(safe.actorId()),
                blankToNull(safe.targetType()), blankToNull(safe.targetId()), blankToNull(safe.correlationId()),
                safe.from(), safe.to(), cursor == null ? null : cursor.occurredAt(), cursor == null ? null : cursor.auditEventId());

        List<AuditEvent> fetched = query.find(tenantId, filter, limit + 1);
        boolean hasMore = fetched.size() > limit;
        List<AuditEvent> page = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            AuditEvent last = page.get(page.size() - 1);
            nextCursor = encodeCursor(last.occurredAt(), last.auditEventId());
        }

        audit.append(new AuditEvent(
                UUID.randomUUID(), tenantId, "USER", actorId,
                "AUDIT_VIEWED", "TENANT", tenantId.toString(), clock.instant(), correlationId,
                AuditOutcome.SUCCESS,
                "{\"resultCount\":" + page.size() + ",\"filtered\":" + hasAnyFilter(safe) + "}"));

        return new AuditPage(page.stream().map(AuditEntry::from).toList(), nextCursor, hasMore);
    }

    private static boolean hasAnyFilter(SearchRequest request) {
        return blankToNull(request.action()) != null || request.outcome() != null || blankToNull(request.actorType()) != null
                || blankToNull(request.actorId()) != null || blankToNull(request.targetType()) != null
                || blankToNull(request.targetId()) != null || blankToNull(request.correlationId()) != null
                || request.from() != null || request.to() != null;
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
            String cursor) { }

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
            String safeDetailsJson) {
        static AuditEntry from(AuditEvent event) {
            return new AuditEntry(event.auditEventId(), event.actorType(), event.actorId(), event.action(),
                    event.targetType(), event.targetId(), event.occurredAt(), event.correlationId(),
                    event.outcome(), event.safeDetailsJson());
        }
    }

    public record AuditPage(List<AuditEntry> events, String nextCursor, boolean hasMore) {
        public AuditPage { events = List.copyOf(events); }
    }
}
