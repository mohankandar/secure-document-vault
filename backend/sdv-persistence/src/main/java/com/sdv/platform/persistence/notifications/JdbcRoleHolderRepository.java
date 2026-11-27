package com.sdv.platform.persistence.notifications;

import com.sdv.platform.application.port.RoleHolderRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Task 0100 (VSEC-203): role_holders (changeset 051). */
@Repository
public class JdbcRoleHolderRepository implements RoleHolderRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRoleHolderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<String> roles(UUID entityId, String userId) {
        return new LinkedHashSet<>(jdbc.queryForList("select role from role_holders where entity_id = :entity and user_id = :user order by role",
                new MapSqlParameterSource("entity", entityId).addValue("user", userId), String.class));
    }

    @Override
    public void replace(String customerId, UUID entityId, String userId, Set<String> roles, Instant seenAt) {
        MapSqlParameterSource p = new MapSqlParameterSource("entity", entityId).addValue("user", userId)
                .addValue("customer", customerId).addValue("at", Timestamp.from(seenAt))
                .addValue("roles", roles.isEmpty() ? Set.of("") : roles);
        jdbc.update("delete from role_holders where entity_id = :entity and user_id = :user and role not in (:roles)", p);
        for (String role : roles) {
            jdbc.update("insert into role_holders (entity_id, customer_id, user_id, role, seen_at) values (:entity, :customer, :user, :role, :at) "
                    + "on conflict (entity_id, user_id, role) do update set seen_at = excluded.seen_at, customer_id = excluded.customer_id",
                    new MapSqlParameterSource(p.getValues()).addValue("role", role));
        }
    }

    @Override
    public Set<String> holders(String customerId, UUID entityId, String role, Instant seenSince) {
        return new LinkedHashSet<>(jdbc.queryForList("select distinct user_id from role_holders where customer_id = :customer and role = :role"
                        + " and seen_at >= :since and (cast(:entity as uuid) is null or entity_id = cast(:entity as uuid)) order by user_id",
                new MapSqlParameterSource("customer", customerId).addValue("role", role).addValue("entity", entityId, java.sql.Types.OTHER)
                        .addValue("since", Timestamp.from(seenSince)),
                String.class));
    }
}
