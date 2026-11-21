package com.sdv.platform.application.access;

import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantStatus;

import java.util.UUID;

/**
 * Task 0038 (SDV-AUT-009): the one check that the sign-in's organisation exists and is active. An unknown tenant and
 * a suspended one get the identical answer (403, one code, one sentence), so the answer never tells an outsider which
 * tenants exist. The log line keeps the real reason (the tenant ID, and "missing" or "suspended").
 */
public final class ActiveTenant {
    public static final String MESSAGE = "Your organisation's Vero account isn't available. Contact your IT team.";

    private ActiveTenant() { }

    public static Tenant require(TenantRepository tenants, UUID tenantId) {
        Tenant tenant = tenantId == null ? null : tenants.findById(tenantId).orElse(null);
        if (tenant == null) throw refused(tenantId, "missing");
        if (tenant.status() != TenantStatus.ACTIVE) throw refused(tenantId, "suspended (" + tenant.status() + ")");
        return tenant;
    }

    private static CodedRefusal.Forbidden refused(UUID tenantId, String why) {
        return new CodedRefusal.Forbidden(SdvErrorCode.AUT_009, MESSAGE, "tenant=" + tenantId + " " + why);
    }
}
