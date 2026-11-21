package com.sdv.platform.application.reports;

import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Task 0032, shared with task 0033: the time zone a report's days are in. The tenant's own setting
 * (tenants.time_zone) or, without one, the install default. A wrong tenant value refuses the report (SDV-RPT-004);
 * nothing is guessed.
 */
record TenantZone(ZoneId zone, String source) {
    static final String TENANT = "TENANT";
    static final String INSTALL_DEFAULT = "INSTALL_DEFAULT";

    static TenantZone of(String own, ZoneId installDefault) {
        if (own == null) return new TenantZone(installDefault, INSTALL_DEFAULT);
        try {
            return new TenantZone(ZoneId.of(own), TENANT);
        } catch (DateTimeException ex) {
            throw new CodedRefusal.Conflict(SdvErrorCode.RPT_004,
                    "The tenant's time zone setting is not a time zone Vero knows, so the report can't work out its days. Ask Tenant IT to correct it.");
        }
    }
}
