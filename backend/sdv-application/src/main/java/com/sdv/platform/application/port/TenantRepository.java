package com.sdv.platform.application.port;

import com.sdv.platform.domain.tenant.Tenant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Vero's entities (table {@code tenants}; task 0041: one row per activated platform-core entity, same UUID). */
public interface TenantRepository {
    Optional<Tenant> findById(UUID tenantId);

    /** Task 0041: the ACTIVE entities, by name (the local sign-in's entity switcher). */
    default List<Tenant> findActive() {
        throw new UnsupportedOperationException("this tenant store does not list entities");
    }

    /**
     * Task 0041: Vero's row for an entity platform-core just activated ({@code tenantId} is the entity UUID derived
     * from the slot). Returns false when the row was there already; it is never changed then.
     */
    default boolean createEntity(UUID tenantId, String tenantKey, String displayName) {
        throw new UnsupportedOperationException("this tenant store does not create entities");
    }
}
