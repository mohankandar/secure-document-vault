package com.sdv.platform.application.port;

import com.sdv.platform.domain.document.SensitivityLevel;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Task 0024, part 2: record (contract) levels, one row per record of a sending system (record_sensitivity_levels). */
public interface RecordSensitivityRepository {

    /** Stores a record's first level. False, with nothing changed, when the record already has one. */
    boolean insertIfAbsent(NewRecordLevel level);

    /** The record's level, locked until the end of the transaction so two deliveries can't both raise it. */
    Optional<StoredRecordLevel> lockByKey(UUID tenantId, UUID sourceId, String recordKey);

    /** Raises a level; the caller has checked under the lock that the new level is higher. */
    void raise(UUID tenantId, UUID recordLevelId, SensitivityLevel level, String setBy, Instant updatedAt);

    /**
     * Task 0028: a person's change of a record's level (a Steward's raise, or a lowering a Business Admin approved).
     * The caller has checked the direction under the lock. setBy is the person's subject; reason replaces the stored one.
     * Only the database repository supports it; the in-memory stand-ins used where no record levels exist refuse it.
     */
    default void setLevel(UUID tenantId, UUID recordLevelId, SensitivityLevel level, String setBy, String reason,
                          Instant updatedAt) {
        throw new UnsupportedOperationException("Record levels can't be changed here.");
    }

    /** How many records of this sending system have a level. */
    long countForSource(UUID tenantId, UUID sourceId);

    record NewRecordLevel(
            UUID recordLevelId,
            UUID tenantId,
            UUID sourceId,
            String recordKey,
            SensitivityLevel level,
            String reason,
            String setBy,
            Instant setAt) { }

    record StoredRecordLevel(UUID recordLevelId, SensitivityLevel level) { }
}
