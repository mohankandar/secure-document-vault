package com.sdv.platform.application.port;

import com.sdv.platform.domain.audit.AuditEvent;

import java.util.List;

public interface AuditEventPort {
    void append(AuditEvent event);

    /** Task 0052: many events in one go (a batch of 1,000 quarantined or released documents), in the caller's transaction. */
    default void appendAll(List<AuditEvent> events) {
        for (AuditEvent event : events) append(event);
    }
}
