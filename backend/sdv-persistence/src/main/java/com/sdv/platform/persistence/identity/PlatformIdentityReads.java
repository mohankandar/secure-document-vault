package com.sdv.platform.persistence.identity;

import com.documentplatform.identity.admission.ConnectionKey;
import com.sdv.platform.domain.identity.VeroProduct;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0041: the few reads of platform-core's idn_ tables that platform-core has no method for. Read only; every row is
 * written by setup or by platform-core's own stores. Always Vero's product ({@link VeroProduct#CODE}).
 */
@Repository
public class PlatformIdentityReads {
    private static final String CONNECTION = "from idn_login_connection c where c.status = 'ACTIVE' "
            + "and c.product_code = :product and c.provider_id = :provider and c.issuer_uri = :issuer "
            + "and c.api_audience = :audience";

    private final NamedParameterJdbcTemplate jdbc;

    public PlatformIdentityReads(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether a customer has an ACTIVE login connection for Vero with this provider, issuer and audience. */
    public boolean connectionActive(ConnectionKey key) {
        if (blank(key)) return false;
        Boolean any = jdbc.queryForObject("select exists(select 1 " + CONNECTION + ")", params(key), Boolean.class);
        return Boolean.TRUE.equals(any);
    }

    /**
     * The slots not activated yet, under that connection, where one of {@code groups} is granted {@code role}. A slot
     * that is SUSPENDED or NOT_IN_LICENCE is never listed.
     */
    public List<String> notActivatedSlotsGranting(ConnectionKey key, Collection<String> groups, String role) {
        if (blank(key) || groups == null || groups.isEmpty()) return List.of();
        return query(params(key).addValue("role", role), groups);
    }

    private List<String> query(MapSqlParameterSource p, Collection<String> groups) {
        return jdbc.getJdbcTemplate().execute((java.sql.Connection c) -> {
            Array array = c.createArrayOf("text", groups.toArray());
            try (var ps = c.prepareStatement("select distinct e.slot_id from idn_login_connection c "
                    + "join idn_entity e on e.tenant_id = c.tenant_id and e.status = 'NOT_ACTIVATED' "
                    + "join idn_group_grant g on g.slot_id = e.slot_id and g.product_code = c.product_code "
                    + "and g.issuer_uri = c.issuer_uri "
                    + "where c.status = 'ACTIVE' and c.product_code = ? and c.provider_id = ? and c.issuer_uri = ? "
                    + "and c.api_audience = ? and g.role = ? and g.external_group = any(?) order by e.slot_id")) {
                ps.setString(1, (String) p.getValue("product"));
                ps.setString(2, (String) p.getValue("provider"));
                ps.setString(3, (String) p.getValue("issuer"));
                ps.setString(4, (String) p.getValue("audience"));
                ps.setString(5, (String) p.getValue("role"));
                ps.setArray(6, array);
                List<String> out = new java.util.ArrayList<>();
                try (var rs = ps.executeQuery()) {
                    while (rs.next()) out.add(rs.getString(1));
                }
                return List.copyOf(out);
            } finally {
                array.free();
            }
        });
    }

    /** The customer ID ({@code TEN-...}) and slot ID ({@code ENT-...}) of an entity, when idn_entity has it. */
    public record EntitySlot(String tenantId, String slotId, String status, String displayName) { }

    public Optional<EntitySlot> slotOf(UUID entityUuid) {
        List<EntitySlot> rows = jdbc.query("select tenant_id, slot_id, status, display_name from idn_entity "
                        + "where entity_uuid = :entity", new MapSqlParameterSource("entity", entityUuid),
                (rs, i) -> new EntitySlot(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        return rows.stream().findFirst();
    }

    private static MapSqlParameterSource params(ConnectionKey key) {
        return new MapSqlParameterSource().addValue("product", VeroProduct.CODE).addValue("provider", key.providerId())
                .addValue("issuer", key.issuer().toString()).addValue("audience", key.audience());
    }

    private static boolean blank(ConnectionKey key) {
        return key == null || key.issuer() == null || key.providerId() == null || key.providerId().isBlank()
                || key.audience() == null || key.audience().isBlank();
    }
}
