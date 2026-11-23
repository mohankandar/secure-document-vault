package com.sdv.platform.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0050: the register of sealed audit reports (changeset 037). Rows are written once: the table is append-only
 * at the database level, so there is no update or delete here. Every method is limited to one entity (tenant).
 */
public interface AuditReportRepository {

    /** filtersJson: the filters as JSON (never a reason); csv: the sealed file, kept so later copies are exactly it. */
    record StoredReport(UUID reportId, UUID tenantId, String generatedBy, String generatedByName, Instant generatedAt,
                        Instant rangeFrom, Instant rangeTo, String filtersJson, int rowCount, String fingerprint,
                        byte[] csv) { }

    void insert(StoredReport report);

    Optional<StoredReport> find(UUID tenantId, UUID reportId);

    /** Newest first, at most {@code limit}; the CSV is left out (null). */
    List<StoredReport> list(UUID tenantId, int limit);

    /** The reports of this entity whose CSV has this fingerprint, newest first; the CSV is left out (null). */
    List<StoredReport> findByFingerprint(UUID tenantId, String fingerprint);
}
