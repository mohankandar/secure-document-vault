package com.sdv.platform.application.access;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0048: whether documents are quarantined now (a quarantine in force). Every document path asks through
 * {@link DocumentAccessGuard}; record views ask for many at once. A lookup that fails throws: a document is never shown
 * because its quarantine couldn't be read.
 */
public interface QuarantineLookup {
    /** Nothing is ever quarantined (tests and stacks without the quarantine store). */
    QuarantineLookup NONE = new QuarantineLookup() {
        @Override public boolean isQuarantined(UUID tenantId, UUID documentId) { return false; }
        @Override public Set<UUID> quarantined(UUID tenantId, Collection<UUID> documentIds) { return Set.of(); }
    };

    boolean isQuarantined(UUID tenantId, UUID documentId);

    /** The ones of {@code documentIds} that are quarantined now. */
    Set<UUID> quarantined(UUID tenantId, Collection<UUID> documentIds);
}
