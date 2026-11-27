package com.sdv.platform.application.reports;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Locale;
import java.util.UUID;

/**
 * Task 0081: zones come from identity (idn_entity.time_zone, idn_customer.time_zone), the one source. The PostgreSQL
 * tests give an entity its zones with these rows: a customer and a slot of its own per entity, never activated.
 */
final class IdentityZones {
    private IdentityZones() { }

    /** The entity's own zone (null: none) and its customer's (null: none). */
    static void set(JdbcTemplate db, UUID entity, String entityZone, String customerZone) {
        String key = entity.toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        String customer = "TEN-ZONES-" + key;
        db.update("insert into idn_customer(tenant_id, name, time_zone) values (?, ?, ?) "
                + "on conflict (tenant_id) do update set time_zone = excluded.time_zone", customer, "Zones " + key, customerZone);
        db.update("insert into idn_entity(slot_id, entity_uuid, tenant_id, status, time_zone) values (?, ?, ?, 'NOT_ACTIVATED', ?) "
                + "on conflict (slot_id) do update set time_zone = excluded.time_zone", "ENT-ZONES-" + key, entity, customer, entityZone);
    }

    /** As before the test: no zone of its own and none for its customer. */
    static void clear(JdbcTemplate db, UUID entity) {
        set(db, entity, null, null);
    }
}
