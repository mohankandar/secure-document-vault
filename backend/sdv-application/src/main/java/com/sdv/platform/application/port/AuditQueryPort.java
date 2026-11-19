package com.sdv.platform.application.port;

import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface AuditQueryPort {
    List<AuditEvent> find(UUID tenantId, Filter filter, int limitPlusOne);

    /**
     * Task 0011 Part C.3: the events with, for document events, the record number and file name looked up when the
     * list is read (never stored in the audit). Implementations without the lookup return the events alone.
     */
    default List<Row> findRows(UUID tenantId, Filter filter, int limitPlusOne) {
        return find(tenantId, filter, limitPlusOne).stream().map(event -> new Row(event, null, null)).toList();
    }

    record Row(AuditEvent event, String recordNumber, String filename) { }

    record Filter(
            String action,
            AuditOutcome outcome,
            String actorType,
            String actorId,
            String targetType,
            String targetId,
            String correlationId,
            Instant from,
            Instant to,
            Instant beforeOccurredAt,
            UUID beforeEventId,
            /* Task 0011 Part C.4: only document events whose record has this identifier value, and (task 0020
               item 1) record opens whose submission has it. */
            String recordNumber,
            /* Task 0011 export: only these action codes (null: any; empty: nothing), and never these (null: none). */
            Set<String> actions,
            Set<String> excludeActions) {
        public Filter {
            actions = actions == null ? null : Set.copyOf(actions);
            excludeActions = excludeActions == null || excludeActions.isEmpty() ? null : Set.copyOf(excludeActions);
        }

        public Filter(String action, AuditOutcome outcome, String actorType, String actorId, String targetType,
                      String targetId, String correlationId, Instant from, Instant to, Instant beforeOccurredAt,
                      UUID beforeEventId, String recordNumber) {
            this(action, outcome, actorType, actorId, targetType, targetId, correlationId, from, to, beforeOccurredAt,
                    beforeEventId, recordNumber, null, null);
        }
    }
}
