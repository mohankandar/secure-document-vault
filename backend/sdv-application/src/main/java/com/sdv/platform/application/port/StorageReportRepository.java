package com.sdv.platform.application.port;

import com.sdv.platform.domain.document.SensitivityLevel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0053: the Storage and retention report's reads (counts only, one tenant per call) and its kept counts
 * (report_storage_counts, changeset 040). Documents are counted at their effective level, as the Sensitivity report.
 */
public interface StorageReportRepository {

    /** Stored now, by sending system and level: documents, pages, bytes. */
    record Stored(UUID sourceId, SensitivityLevel level, long documents, long pages, long bytes) { }

    /** Received in a month (YYYY-MM in the tenant's time zone), by sending system and level, still stored now. */
    record Month(String month, UUID sourceId, SensitivityLevel level, long documents, long pages, long bytes) { }

    /**
     * One retention rule (the days kept, as each document was given them; null: kept forever): the stored documents
     * under it, and how many reach the end of their retention within 30, 60 and 90 days of {@code now}.
     */
    record Due(Integer retentionDays, long stored, long within30, long within60, long within90) { }

    /** Past their retention now but kept: by a legal hold only, a quarantine only (rows or filter), or both. */
    record HeldBack(long legalHold, long quarantine, long both) { }

    /** The counts kept for an entity. */
    record Kept(Instant countedAt, long tookMs, String countsJson) { }

    List<Stored> countStored(UUID tenantId);

    List<Month> countReceived(UUID tenantId, Instant since, String timeZone);

    List<Due> countDue(UUID tenantId, Instant now);

    HeldBack countHeldBack(UUID tenantId, Instant now);

    /** The rule names: each retention length's most recent policy name (a length no policy had has none). */
    Map<Integer, String> findRuleNames(UUID tenantId);

    Optional<Kept> findKept(UUID tenantId);

    void keep(UUID tenantId, Instant countedAt, long tookMs, String countsJson);

    /** Active entities, for the background count. */
    List<UUID> findActiveTenants();
}
