package com.sdv.platform.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 0054: the Activity report's reads, from the audit trail (one tenant per call, but {@link #findActiveTenants}).
 * Each closed day is counted once and kept (report_activity_counts, changeset 041); a report adds what the audit holds
 * after the last kept day. Counts only: kinds, role names (sorted, comma-separated; empty: not recorded), levels; a
 * person only for opens, by sign-in name.
 */
public interface ActivityReportRepository {

    /** One count: kind, roles, level (null where none applies), events. */
    record Count(String kind, String roles, String level, long events) { }

    /** One of the people with the most opens: sign-in ID and name, the roles they opened with, opens by level. */
    record Person(String actorId, String actorName, String roles, long standard, long confidential,
                  long highlyConfidential, long total) { }

    /**
     * The window to count: the kept days {@code [firstDay, lastKeptDay]} (none when lastKeptDay is null or before
     * firstDay), and the audit trail from {@code liveFrom} to {@code liveTo} (none when liveFrom isn't before liveTo).
     */
    record Window(LocalDate firstDay, LocalDate lastKeptDay, Instant liveFrom, Instant liveTo) { }

    /** The days already kept, with the time zone each was counted in. */
    Map<LocalDate, String> findKeptDays(UUID tenantId, LocalDate from, LocalDate to);

    /** Counts one day from the audit trail ({@code [start, end)}) and keeps it, replacing what was kept. Returns events. */
    long keepDay(UUID tenantId, LocalDate day, String timeZone, Instant start, Instant end, Instant countedAt);

    List<Count> count(UUID tenantId, Window window);

    List<Person> busiest(UUID tenantId, Window window, int limit);

    List<UUID> findActiveTenants();
}
