package com.sdv.platform.application.notifications;

import com.sdv.platform.domain.document.SensitivityLevel;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 0081: one thing that happened, for {@link com.sdv.platform.application.port.NotificationPort}. Structured on
 * purpose: an event, the entity, counts and times, and for an event with an identifier the record's primary identifier
 * and the record's level. There is no field for a title, a name, a file name, a reason or the level itself.
 *
 * @param entityId          Vero's entity (its tenant id); required for business events, null for an install-wide one
 * @param recordIdentifier  the record's primary identifier, or null; used only by e-mail and only when
 *                          {@link #mayNameRecord()}
 * @param level             the record's level when the identifier was given; null counts as Highly Confidential
 * @param requester         for {@link VeroEvent.Audience#REQUESTER} events: the person who asked (sign-in subject)
 */
public record VeroNotice(VeroEvent event, UUID entityId, String recordIdentifier, SensitivityLevel level,
                         Map<String, Long> counts, Map<String, Instant> times, String requester) {

    public VeroNotice {
        Objects.requireNonNull(event, "event");
        if (event.kind() == VeroEvent.Kind.BUSINESS) Objects.requireNonNull(entityId, "entityId");
        if (event.audience() == VeroEvent.Audience.REQUESTER && (requester == null || requester.isBlank())) {
            throw new IllegalArgumentException("a requester's event needs the requester");
        }
        counts = Map.copyOf(counts);
        times = Map.copyOf(times);
        counts.keySet().forEach(name -> {
            if (!event.counts().contains(name)) throw new IllegalArgumentException("count " + name + " is not one of " + event);
        });
        times.keySet().forEach(name -> {
            if (!event.times().contains(name)) throw new IllegalArgumentException("time " + name + " is not one of " + event);
        });
    }

    /**
     * Whether an e-mail may name the record: the event carries identifiers, one was given, and the record is known not
     * to be Highly Confidential. Unknown level: no (fail closed).
     */
    public boolean mayNameRecord() {
        return event.identifier() && recordIdentifier != null && !recordIdentifier.isBlank()
                && level != null && level != SensitivityLevel.HIGHLY_CONFIDENTIAL;
    }

    public static Builder of(VeroEvent event, UUID entityId) {
        return new Builder(event, entityId);
    }

    public static final class Builder {
        private final VeroEvent event;
        private final UUID entityId;
        private String recordIdentifier;
        private SensitivityLevel level;
        private final Map<String, Long> counts = new LinkedHashMap<>();
        private final Map<String, Instant> times = new LinkedHashMap<>();
        private String requester;

        private Builder(VeroEvent event, UUID entityId) {
            this.event = event;
            this.entityId = entityId;
        }

        /** The record's primary identifier and its level (Highly Confidential, or null, keeps it out of every message). */
        public Builder record(String identifier, SensitivityLevel level) {
            this.recordIdentifier = identifier;
            this.level = level;
            return this;
        }

        public Builder count(String name, long value) {
            counts.put(name, value);
            return this;
        }

        public Builder time(String name, Instant value) {
            if (value != null) times.put(name, value);
            return this;
        }

        public Builder to(String requester) {
            this.requester = requester;
            return this;
        }

        public VeroNotice build() {
            return new VeroNotice(event, entityId, recordIdentifier, level, counts, times, requester);
        }
    }
}
