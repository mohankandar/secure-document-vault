package com.sdv.platform.security;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class RoleCapabilities {
    private static final Map<SdvRole, Set<Capability>> MAPPING = new EnumMap<>(SdvRole.class);

    static {
        MAPPING.put(SdvRole.BUSINESS_USER, EnumSet.of(Capability.SEARCH_RECORDS, Capability.VIEW_PDF));
        EnumSet<Capability> steward = EnumSet.of(
                Capability.SEARCH_RECORDS, Capability.VIEW_PDF,
                Capability.PERMANENT_REDACTION, Capability.DOWNLOAD_CURRENT_PDF,
                Capability.PRINT_DOCUMENT, Capability.ADD_DOCUMENT_TAG, Capability.ADD_DOCUMENT_COMMENT,
                Capability.VIEW_CONFIDENTIAL, Capability.CHANGE_SENSITIVITY_LEVEL);
        MAPPING.put(SdvRole.DOCUMENT_STEWARD, steward);
        // Task 0025: everything a Document Steward has, plus Highly Confidential documents and asking to print or
        // download one.
        EnumSet<Capability> restricted = EnumSet.copyOf(steward);
        restricted.add(Capability.VIEW_HIGHLY_CONFIDENTIAL);
        restricted.add(Capability.REQUEST_SENSITIVE_ACCESS);
        MAPPING.put(SdvRole.RESTRICTED_STEWARD, restricted);
        MAPPING.put(SdvRole.BUSINESS_ADMIN, EnumSet.of(
                Capability.CONFIGURE_METADATA_SEARCH, Capability.CONFIGURE_RETENTION,
                Capability.CONFIGURE_BUSINESS_LIMITS, Capability.CONFIGURE_ROLE_PERMISSIONS,
                Capability.APPROVE_SENSITIVE_ACCESS, Capability.MANAGE_LEGAL_HOLD, Capability.VIEW_INTAKE_REPORTS));
        MAPPING.put(SdvRole.IT_ADMIN, EnumSet.of(
                Capability.CONFIGURE_INGESTION_SOURCES, Capability.CONFIGURE_TENANT_AUTH,
                Capability.CONFIGURE_STORAGE_KEY_PROVIDER, Capability.VIEW_TECHNICAL_FAILURES));
        MAPPING.put(SdvRole.PLATFORM_OPERATIONS_ADMIN, EnumSet.of(
                Capability.VIEW_TECHNICAL_FAILURES));
    }

    private RoleCapabilities() { }

    public static boolean has(Set<SdvRole> roles, Capability capability) {
        return roles.stream().anyMatch(role -> MAPPING.getOrDefault(role, Set.of()).contains(capability));
    }

    public static Set<Capability> capabilities(Set<SdvRole> roles) {
        EnumSet<Capability> result = EnumSet.noneOf(Capability.class);
        roles.forEach(role -> result.addAll(MAPPING.getOrDefault(role, Set.of())));
        return Set.copyOf(result);
    }

    public static Set<Capability> capabilities(SdvRole role) {
        return Set.copyOf(MAPPING.getOrDefault(role, Set.of()));
    }
}
