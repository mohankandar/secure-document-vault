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
 */
public interface UnidentifiedIntakeAttemptRepository {
    /** How long attempts are kept. Older ones are removed when a new one is written. */
    int KEEP_DAYS = 30;

    /** Writes the attempt; the tenant is kept only when it exists. */
    void record(Attempt attempt);

    /** Attempts since {@code since} that this tenant may see (its own and those with no tenant). */
    long count(UUID tenantId, Instant since);

    /** The newest first, at most {@code limit}. */
    List<Attempt> findRecent(UUID tenantId, Instant since, int limit);

    /**
     * tenantId: null unless a verified sign-in named it. callerAddress: the network address the request came from (as
     * Vero's server saw it). address: the Vero address called, with its IDs blanked.
     */
    record Attempt(UUID attemptId, UUID tenantId, Instant occurredAt, String callerAddress, String method,
                   String address, int status, String code, String reference) { }
}
