package com.sdv.platform.application.access;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Task 0054: the person's role names as an access event records them (opened, printed, downloaded), so the Activity
 * report can count by role, as task 0033 does for refusals. Role names are fixed enum names, never free text; sorted.
 */
public final class AuditRoles {
    private AuditRoles() { }

    public static List<String> of(Clearance clearance) {
        if (clearance == null) return List.of();
        return clearance.roles().stream().filter(r -> r != null && r.matches("[A-Z_]{1,80}")).distinct().sorted().toList();
    }

    /** {@code ,"roles":["A","B"]} for a JSON object being written by hand; empty when no role is known. */
    public static String field(Clearance clearance) {
        List<String> roles = of(clearance);
        if (roles.isEmpty()) return "";
        return ",\"roles\":[" + roles.stream().map(r -> "\"" + r + "\"").collect(Collectors.joining(",")) + "]";
    }
}
