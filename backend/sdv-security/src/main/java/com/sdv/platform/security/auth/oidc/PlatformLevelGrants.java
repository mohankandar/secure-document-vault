package com.sdv.platform.security.auth.oidc;

import com.documentplatform.identity.admission.ActiveEntityCandidates;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.admission.EntityRoleCatalog;
import com.documentplatform.identity.admission.PlatformGroupAuthorization;
import com.documentplatform.identity.admission.PlatformGroupGrants;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Task 0057 (LV-16 item 3): platform-level grants on platform-core identity. platform-core's
 * {@link PlatformGroupAuthorization} gives the platform roles of the groups under the connection (setup's {@code *}
 * rows, {@code idn_platform_grant}; it refuses a grant that names an entity role); they apply in every ACTIVE entity
 * of that connection ({@link ActiveEntityCandidates}). Nothing is cached here: the lookups behind it are.
 */
public final class PlatformLevelGrants implements OidcJwtAuthenticationConverter.PlatformGrants {
    private final PlatformGroupAuthorization authorization;
    private final ActiveEntityCandidates entities;

    public PlatformLevelGrants(PlatformGroupGrants grants, ActiveEntityCandidates entities, EntityRoleCatalog catalog) {
        this.authorization = new PlatformGroupAuthorization(Objects.requireNonNull(catalog, "catalog"),
                Objects.requireNonNull(grants, "grants"));
        this.entities = Objects.requireNonNull(entities, "entities");
    }

    @Override
    public Map<EntityId, Set<String>> platformRoles(ConnectionKey key, Object groups) {
        Set<String> roles = authorization.platformRoles(key, groups);
        if (roles.isEmpty()) return Map.of();
        Set<EntityId> active = entities.find(key);
        if (active == null) throw new IllegalStateException("The ACTIVE entities could not be read.");
        Map<EntityId, Set<String>> out = new LinkedHashMap<>();
        for (EntityId entity : active) out.put(entity, roles);
        return out;
    }
}
