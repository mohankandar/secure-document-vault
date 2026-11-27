package com.sdv.platform.application.reports;

import com.sdv.platform.application.port.IntakeReportRepository;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Task 0032, shared with task 0033: the time zone a report's days are in. Task 0081: identity is the one source
 * (platform-core's {@code EntityTimeZones}): the entity's own zone (idn_entity.time_zone), else its customer's
 * (idn_customer.time_zone), else UTC. The install default {@code SDV_DEFAULT_TIME_ZONE} is gone. A stored value that
 * is not a zone refuses the report (SDV-RPT-004); nothing is guessed.
 */
record TenantZone(ZoneId zone, String source) {
    /** The entity's own zone. */
    static final String ENTITY = "ENTITY";
    /** The customer's zone, which an entity without its own follows. */
    static final String CUSTOMER = "CUSTOMER";
    /** Neither is set: UTC. */
    static final String NONE = "NONE";

    static TenantZone of(IntakeReportRepository zones, java.util.UUID tenantId) {
        String own = zones.findTenantTimeZone(tenantId).orElse(null);
        if (own != null) return new TenantZone(parse(own), ENTITY);
        String customer = zones.findCustomerTimeZone(tenantId).orElse(null);
        if (customer != null) return new TenantZone(parse(customer), CUSTOMER);
        return new TenantZone(ZoneId.of("UTC"), NONE);
    }

    private static ZoneId parse(String name) {
        try {
            return ZoneId.of(name);
        } catch (DateTimeException ex) {
            throw new CodedRefusal.Conflict(SdvErrorCode.RPT_004,
                    "The entity's time zone setting is not a time zone Vero knows, so the report can't work out its days. Ask IT to correct it.");
        }
    }
}
