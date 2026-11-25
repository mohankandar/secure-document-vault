package com.sdv.platform.persistence.business;

import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.application.business.RecordKey;
import com.sdv.platform.application.port.RecordSensitivityRepository;
import com.sdv.platform.domain.document.SensitivityLevel;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Task 0024, part 2: record_sensitivity_levels. Rows are found by the record key's SHA-256 (the unique key). */
@Repository
public class JdbcRecordSensitivityRepository implements RecordSensitivityRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRecordSensitivityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(NewRecordLevel level) {
        // A concurrent first delivery for the same record waits here and then inserts nothing.
        int inserted = jdbc.update("""
                insert into record_sensitivity_levels(
                    record_level_id, tenant_id, source_id, record_id,
                    sensitivity_level, reason, set_by, set_at, updated_at)
                values (:id, :tenantId, :sourceId, :recordId, :level, :reason, :setBy, :setAt, :setAt)
                on conflict (tenant_id, source_id, record_id) do nothing
                """, new MapSqlParameterSource()
                .addValue("id", level.recordLevelId())
                .addValue("tenantId", level.tenantId())
                .addValue("sourceId", level.sourceId())
                .addValue("recordId", level.recordId())
                .addValue("level", level.level().name())
                .addValue("reason", level.reason())
                .addValue("setBy", level.setBy())
                .addValue("setAt", Timestamp.from(level.setAt())));
        return inserted == 1;
    }

    @Override
    public Optional<StoredRecordLevel> lockByKey(UUID tenantId, UUID sourceId, UUID recordId) {
        return jdbc.query("""
                select record_level_id, sensitivity_level
                  from record_sensitivity_levels
                 where tenant_id=:tenantId and source_id=:sourceId and record_id=:recordId
                   for update
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId)
                .addValue("recordId", recordId),
                (rs, rowNum) -> new StoredRecordLevel(
                        rs.getObject("record_level_id", UUID.class),
                        SensitivityLevel.valueOf(rs.getString("sensitivity_level"))))
                .stream().findFirst();
    }

    @Override
    public void raise(UUID tenantId, UUID recordLevelId, SensitivityLevel level, String setBy, Instant updatedAt) {
        int updated = jdbc.update("""
                update record_sensitivity_levels
                   set sensitivity_level=:level, set_by=:setBy, updated_at=greatest(set_at, :updatedAt)
                 where tenant_id=:tenantId and record_level_id=:id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", recordLevelId)
                .addValue("level", level.name())
                .addValue("setBy", setBy)
                .addValue("updatedAt", Timestamp.from(updatedAt)));
        if (updated != 1) throw new InternalCheckFailed("The record's sensitivity level could not be raised.");
    }

    @Override
    public void setLevel(UUID tenantId, UUID recordLevelId, SensitivityLevel level, String setBy, String reason,
                         Instant updatedAt) {
        int updated = jdbc.update("""
                update record_sensitivity_levels
                   set sensitivity_level=:level, set_by=:setBy, reason=:reason, updated_at=greatest(set_at, :updatedAt)
                 where tenant_id=:tenantId and record_level_id=:id
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("id", recordLevelId)
                .addValue("level", level.name())
                .addValue("setBy", setBy)
                .addValue("reason", reason)
                .addValue("updatedAt", Timestamp.from(updatedAt)));
        if (updated != 1) throw new InternalCheckFailed("The record's sensitivity level could not be changed.");
    }

    @Override
    public long countForSource(UUID tenantId, UUID sourceId) {
        Long count = jdbc.queryForObject("""
                select count(*) from record_sensitivity_levels where tenant_id=:tenantId and source_id=:sourceId
                """, new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("sourceId", sourceId), Long.class);
        return count == null ? 0 : count;
    }
}
