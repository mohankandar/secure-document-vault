package com.sdv.platform.security.auth.enterprise;

import com.sdv.platform.security.SdvRole;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Product-specific authorization mapping. External group names remain outside the shared identity module. */
public final class SdvGroupRoleMapper {
    private final Map<SdvRole, Set<String>> mappings;
    private final boolean directRoleCompatibility;

    public SdvGroupRoleMapper(Map<SdvRole, Set<String>> mappings, boolean directRoleCompatibility) {
        EnumMap<SdvRole, Set<String>> copy = new EnumMap<>(SdvRole.class);
        if (mappings != null) mappings.forEach((role, groups) -> copy.put(role, groups == null ? Set.of() : Set.copyOf(groups)));
        this.mappings = Map.copyOf(copy);
        this.directRoleCompatibility = directRoleCompatibility;
    }

    public EnumSet<SdvRole> map(Set<String> externalGroups) {
        EnumSet<SdvRole> roles = EnumSet.noneOf(SdvRole.class);
        Set<String> groups = externalGroups == null ? Set.of() : externalGroups;
        for (var entry : mappings.entrySet()) {
            for (String configured : entry.getValue()) {
                if (groups.contains(configured)) {
                    roles.add(entry.getKey());
                    break;
                }
            }
        }
        if (roles.isEmpty() && directRoleCompatibility) {
            for (String value : groups) {
                try {
                    roles.add(SdvRole.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // Non-role customer groups are ignored in compatibility mode.
                }
            }
        }
        if (roles.isEmpty()) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("invalid_token"),
                    "Authenticated identity has no mapped SDV role.");
        }
        return roles;
    }

    public static Set<String> split(String raw) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (raw == null) return values;
        for (String token : raw.split(",")) {
            String value = token.trim();
            if (!value.isEmpty()) values.add(value);
        }
        return Set.copyOf(values);
    }
}
