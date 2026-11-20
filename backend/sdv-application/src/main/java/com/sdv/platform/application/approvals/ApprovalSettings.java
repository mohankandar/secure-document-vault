package com.sdv.platform.application.approvals;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Task 0028: how long an approved print or download stays usable (24 hours by default) and how long a request may
 * wait undecided (7 days by default), with per-tenant values. Both come from the server's settings:
 * <pre>
 * sdv.approvals.grant-hours=24
 * sdv.approvals.pending-days=7
 * sdv.approvals.tenant-grant-hours=&lt;tenant id&gt;=12,&lt;tenant id&gt;=48
 * sdv.approvals.tenant-pending-days=&lt;tenant id&gt;=3
 * </pre>
 * A value that isn't a whole number in range, or a tenant entry that isn't "tenant id=number", stops the server from
 * starting: an unclear setting is refused, never guessed.
 */
@Component
public class ApprovalSettings {
    public static final int DEFAULT_GRANT_HOURS = 24;
    public static final int DEFAULT_PENDING_DAYS = 7;
    static final int MAX_GRANT_HOURS = 24 * 7;
    static final int MAX_PENDING_DAYS = 90;

    private final int grantHours;
    private final int pendingDays;
    private final Map<UUID, Integer> tenantGrantHours;
    private final Map<UUID, Integer> tenantPendingDays;

    public ApprovalSettings(
            @Value("${sdv.approvals.grant-hours:24}") String grantHours,
            @Value("${sdv.approvals.pending-days:7}") String pendingDays,
            @Value("${sdv.approvals.tenant-grant-hours:}") String tenantGrantHours,
            @Value("${sdv.approvals.tenant-pending-days:}") String tenantPendingDays) {
        this.grantHours = number("sdv.approvals.grant-hours", grantHours, MAX_GRANT_HOURS);
        this.pendingDays = number("sdv.approvals.pending-days", pendingDays, MAX_PENDING_DAYS);
        this.tenantGrantHours = perTenant("sdv.approvals.tenant-grant-hours", tenantGrantHours, MAX_GRANT_HOURS);
        this.tenantPendingDays = perTenant("sdv.approvals.tenant-pending-days", tenantPendingDays, MAX_PENDING_DAYS);
    }

    /** The defaults: 24 hours and 7 days for every tenant. */
    public static ApprovalSettings defaults() {
        return new ApprovalSettings("24", "7", "", "");
    }

    public Duration grantWindow(UUID tenantId) {
        return Duration.ofHours(tenantGrantHours.getOrDefault(tenantId, grantHours));
    }

    public Duration pendingWindow(UUID tenantId) {
        return Duration.ofDays(tenantPendingDays.getOrDefault(tenantId, pendingDays));
    }

    private static int number(String name, String value, int max) {
        try {
            int parsed = Integer.parseInt(value == null ? "" : value.strip());
            if (parsed >= 1 && parsed <= max) return parsed;
        } catch (NumberFormatException ignored) {
            // refused below
        }
        throw new IllegalStateException(name + " must be a whole number from 1 to " + max + ".");
    }

    private static Map<UUID, Integer> perTenant(String name, String value, int max) {
        Map<UUID, Integer> result = new HashMap<>();
        if (value == null || value.isBlank()) return Map.of();
        for (String entry : value.split(",")) {
            String[] parts = entry.strip().split("=", -1);
            if (parts.length != 2) throw new IllegalStateException(name + " entries must be written as tenant id=number.");
            UUID tenant;
            try {
                tenant = UUID.fromString(parts[0].strip());
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException(name + " has an entry that doesn't start with a tenant id.");
            }
            if (result.put(tenant, number(name, parts[1], max)) != null) {
                throw new IllegalStateException(name + " names the same tenant twice.");
            }
        }
        return Map.copyOf(result);
    }
}
