package com.sdv.platform.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Task 0039 (closes the gap from report 0033): intake attempts that ended in an error before Vero knew the sending
 * system, for example a failed sign-in on an intake address (changeset 033, unidentified_intake_attempts). Tenant IT
 * and Platform operations see them in Problems.
 * <p>
 * Only: when, the caller's network address, the Vero address called (IDs blanked), the method, the status, the code
 * and the reference. Never a header, a token or anything from one, a body, or a file name.
 * <p>
 * The tenant is set only when a verified sign-in named one (for example a suspended tenant, or a sending system that
 * isn't one of the tenant's). Without a tenant an attempt is shown to every Tenant IT and Platform operations admin
 * of the install (owner answer, 2026-11-22); with one, to that tenant's only.
 * <p>
 * Task 0039 part 2 (owner answer 2 to report 0039, changeset 034): one row per tenant, caller address, method, address
 * called, status and code <b>per minute</b> (UTC), with a count and the last time, instead of one row per request.
 * A minute that already has {@link #MAX_ROWS_PER_MINUTE} rows adds new keys to one row per key with the caller
 * address {@link #MANY_CALLERS}, so requests from very many addresses can't grow the table without bound either.
 */
public interface UnidentifiedIntakeAttemptRepository {
    /** How long attempts are kept. Older ones are removed when a new row is written. */
    int KEEP_DAYS = 30;
    /** Rows per minute before new caller addresses are counted together. */
    int MAX_ROWS_PER_MINUTE = 100;
    /** The caller address of the row that counts the callers past {@link #MAX_ROWS_PER_MINUTE} in a minute. */
    String MANY_CALLERS = "(many addresses)";

    /**
     * Counts one request: adds it to its minute's row (count + 1, last time), or writes a new row with count 1. The
     * tenant is kept only when it exists.
     */
    void record(Attempt attempt);

    /** Requests (the sum of the counts) since {@code since} that this tenant may see (its own and those with no tenant). */
    long count(UUID tenantId, Instant since);

    /** The rows seen last first, at most {@code limit}. */
    List<Attempt> findRecent(UUID tenantId, Instant since, int limit);

    /**
     * tenantId: null unless a verified sign-in named it. occurredAt: the first request of the row; lastSeenAt: the last.
     * callerAddress: the network address the request came from (task 0039 part 2: from X-Forwarded-For only behind a
     * trusted proxy). address: the Vero address called, with its IDs blanked. count: the requests the row stands for.
     */
    record Attempt(UUID attemptId, UUID tenantId, Instant occurredAt, Instant lastSeenAt, int count,
                   String callerAddress, String method, String address, int status, String code, String reference) {
        /** One request, as Vero records it. */
        public static Attempt one(UUID attemptId, UUID tenantId, Instant at, String callerAddress, String method,
                                  String address, int status, String code, String reference) {
            return new Attempt(attemptId, tenantId, at, at, 1, callerAddress, method, address, status, code, reference);
        }
    }
}
