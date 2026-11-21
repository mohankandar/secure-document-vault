package com.sdv.platform.persistence.business;

import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.application.port.RoleGovernancePort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0025 (changeset 025). Lists of role names, rule ids and login groups are stored as comma-separated text: none
 * of them can hold a comma (role names and rule ids are fixed; login groups are read from a comma-separated setting).
 */
@Repository
public class JdbcRoleGovernanceRepository implements RoleGovernancePort {
    private final NamedParameterJdbcTemplate jdbc;

    public JdbcRoleGovernanceRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean recordRoleConflict(RoleConflictProblem problem) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("problemId", problem.problemId())
                .addValue("tenantId", problem.tenantId())
                .addValue("subject", problem.subject())
                .addValue("displayName", trim(problem.displayName(), 300))
                .addValue("resolvedFrom", problem.resolvedFrom())
                .addValue("held", join(problem.heldRoles()))
                .addValue("withheld", join(problem.withheldRoles()))
                .addValue("rules", join(problem.brokenRules()))
                .addValue("seenAt", Timestamp.from(problem.lastSeenAt()));
        int added = jdbc.update("""
                insert into role_conflict_problems(problem_id, tenant_id, subject, display_name, resolved_from,
                                                   held_roles, withheld_roles, broken_rules, first_seen_at, last_seen_at,
                                                   occurrences)
                values (:problemId, :tenantId, :subject, :displayName, :resolvedFrom,
                        :held, :withheld, :rules, :seenAt, :seenAt, 1)
                on conflict (tenant_id, subject, resolved_from, held_roles) do nothing
                """, p);
        if (added == 1) return true;
        jdbc.update("""
                update role_conflict_problems
                   set last_seen_at = greatest(last_seen_at, :seenAt),
                       occurrences = occurrences + 1,
                       display_name = coalesce(:displayName, display_name),
                       withheld_roles = :withheld,
                       broken_rules = :rules
                 where tenant_id = :tenantId and subject = :subject and resolved_from = :resolvedFrom
                   and held_roles = :held
                """, p);
        return false;
    }

    @Override
    public List<RoleConflictProblem> findRoleConflicts(UUID tenantId, int limit) {
        return jdbc.query("""
                select problem_id, tenant_id, subject, display_name, resolved_from, held_roles, withheld_roles,
                       broken_rules, first_seen_at, last_seen_at, occurrences
                  from role_conflict_problems
                 where tenant_id = :tenantId
                 order by last_seen_at desc, problem_id
                 limit :limit
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("limit", limit),
                (rs, row) -> new RoleConflictProblem(rs.getObject("problem_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getString("subject"), rs.getString("display_name"),
                        rs.getString("resolved_from"), split(rs.getString("held_roles")),
                        split(rs.getString("withheld_roles")), split(rs.getString("broken_rules")),
                        instant(rs, "first_seen_at"), instant(rs, "last_seen_at"), rs.getLong("occurrences")));
    }

    /** Task 0026: delete … returning, so two sign-ins at once can't both report the same end. */
    @Override
    public List<RoleConflictProblem> endRoleConflicts(UUID tenantId, String subject, String resolvedFrom) {
        return jdbc.query("""
                delete from role_conflict_problems
                 where tenant_id = :tenantId and subject = :subject and resolved_from = :resolvedFrom
                returning problem_id, tenant_id, subject, display_name, resolved_from, held_roles, withheld_roles,
                          broken_rules, first_seen_at, last_seen_at, occurrences
                """, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("subject", subject)
                        .addValue("resolvedFrom", resolvedFrom),
                (rs, row) -> new RoleConflictProblem(rs.getObject("problem_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getString("subject"), rs.getString("display_name"),
                        rs.getString("resolved_from"), split(rs.getString("held_roles")),
                        split(rs.getString("withheld_roles")), split(rs.getString("broken_rules")),
                        instant(rs, "first_seen_at"), instant(rs, "last_seen_at"), rs.getLong("occurrences")));
    }

    @Override
    public Optional<List<String>> findRecordedGroups(String roleName) {
        List<String> found = jdbc.query("select login_groups from role_group_mappings_seen where role_name = :role",
                new MapSqlParameterSource("role", roleName), (rs, row) -> rs.getString("login_groups"));
        return found.isEmpty() ? Optional.empty() : Optional.of(split(found.get(0)));
    }

    @Override
    public void saveRecordedGroups(String roleName, List<String> groups, Instant recordedAt) {
        jdbc.update("""
                insert into role_group_mappings_seen(role_name, login_groups, recorded_at)
                values (:role, :groups, :at)
                on conflict (role_name) do update set login_groups = excluded.login_groups,
                                                      recorded_at = excluded.recorded_at
                """, new MapSqlParameterSource().addValue("role", roleName).addValue("groups", join(groups))
                .addValue("at", Timestamp.from(recordedAt)));
    }

    @Override
    public List<UUID> tenantIds() {
        return jdbc.query("select tenant_id from tenants order by tenant_id", new MapSqlParameterSource(),
                (rs, row) -> rs.getObject("tenant_id", UUID.class));
    }

    @Override
    public void addNotice(RoleMappingNotice notice) {
        jdbc.update("""
                insert into role_mapping_notices(notice_id, tenant_id, role_name, groups_before, groups_after, created_at)
                values (:id, :tenantId, :role, :before, :after, :at)
                """, new MapSqlParameterSource().addValue("id", notice.noticeId()).addValue("tenantId", notice.tenantId())
                .addValue("role", notice.roleName())
                .addValue("before", notice.groupsBefore() == null ? null : join(notice.groupsBefore()))
                .addValue("after", join(notice.groupsAfter())).addValue("at", Timestamp.from(notice.createdAt())));
    }

    @Override
    public List<RoleMappingNotice> findNotices(UUID tenantId, int limit) {
        return jdbc.query("""
                select notice_id, tenant_id, role_name, groups_before, groups_after, created_at
                  from role_mapping_notices
                 where tenant_id = :tenantId
                 order by created_at desc, notice_id
                 limit :limit
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("limit", limit),
                (rs, row) -> new RoleMappingNotice(rs.getObject("notice_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class), rs.getString("role_name"),
                        rs.getString("groups_before") == null ? null : split(rs.getString("groups_before")),
                        split(rs.getString("groups_after")), instant(rs, "created_at")));
    }

    static String join(List<String> values) {
        for (String value : values) {
            if (value == null || value.contains(",")) throw new InternalCheckFailed("A stored name can't hold a comma.");
        }
        return String.join(",", values);
    }

    static List<String> split(String text) {
        if (text == null || text.isEmpty()) return List.of();
        return Arrays.stream(text.split(",")).toList();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String text = value.strip();
        return text.length() > max ? text.substring(0, max) : text;
    }
}
