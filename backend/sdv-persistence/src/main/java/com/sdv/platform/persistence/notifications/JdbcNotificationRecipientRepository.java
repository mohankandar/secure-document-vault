package com.sdv.platform.persistence.notifications;

import com.sdv.platform.application.port.NotificationRecipientRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Task 0081: notification_recipients (changeset 049). */
@Repository
public class JdbcNotificationRecipientRepository implements NotificationRecipientRepository {
    private static final String COLUMNS = "recipient_id, customer_id, entity_id, role, user_id, email, added_by, added_at";
    /** entity_id is null for the customer-wide list: "is not distinct from" matches both. */
    private static final String LIST = " where customer_id = :customer and entity_id is not distinct from cast(:entity as uuid)";
    private static final RowMapper<Entry> ROW = (rs, n) -> new Entry(rs.getObject("recipient_id", UUID.class),
            rs.getString("customer_id"), rs.getObject("entity_id", UUID.class), rs.getString("role"),
            rs.getString("user_id"), rs.getString("email"), rs.getString("added_by"),
            rs.getTimestamp("added_at").toInstant());

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcNotificationRecipientRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static MapSqlParameterSource scope(String customerId, UUID entityId) {
        return new MapSqlParameterSource("customer", customerId).addValue("entity", entityId, java.sql.Types.OTHER);
    }

    @Override
    public List<Entry> list(String customerId, UUID entityId) {
        return jdbc.query("select " + COLUMNS + " from notification_recipients" + LIST + " order by role, user_id",
                scope(customerId, entityId), ROW);
    }

    @Override
    public List<Entry> holders(String customerId, UUID entityId, String role) {
        return jdbc.query("select " + COLUMNS + " from notification_recipients" + LIST + " and role = :role order by user_id",
                scope(customerId, entityId).addValue("role", role), ROW);
    }

    @Override
    public List<Entry> person(String customerId, String userId) {
        return jdbc.query("select " + COLUMNS + " from notification_recipients where customer_id = :customer and user_id = :user",
                new MapSqlParameterSource("customer", customerId).addValue("user", userId), ROW);
    }

    @Override
    public boolean insert(Entry e) {
        try {
            return jdbc.update("insert into notification_recipients(" + COLUMNS + ") values "
                    + "(:id, :customer, cast(:entity as uuid), :role, :user, :email, :by, :at)",
                    scope(e.customerId(), e.entityId()).addValue("id", e.recipientId()).addValue("role", e.role())
                            .addValue("user", e.userId()).addValue("email", e.email()).addValue("by", e.addedBy())
                            .addValue("at", Timestamp.from(e.addedAt()))) == 1;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Override
    public Optional<Entry> delete(String customerId, UUID entityId, UUID recipientId) {
        return jdbc.query("delete from notification_recipients" + LIST + " and recipient_id = :id returning " + COLUMNS,
                scope(customerId, entityId).addValue("id", recipientId), ROW).stream().findFirst();
    }
}
