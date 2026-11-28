package com.sdv.platform.web.admin;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/** Task 0098 follow-up (answer Q-7): table first_start_attempts (changeset 052). */
public final class JdbcFirstStartAttempts implements FirstStart.Attempts {
    private final JdbcTemplate db;

    public JdbcFirstStartAttempts(JdbcTemplate db) {
        this.db = db;
    }

    @Override
    public void add(FirstStart.Refused r) {
        db.update("insert into first_start_attempts (attempt_id, attempted_at, user_id, display_name, slot_id, result_code) values (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), Timestamp.from(r.at()), r.subject(), r.displayName(), r.slotId(), r.code());
    }

    @Override
    public List<FirstStart.Refused> takeAll() {
        return db.query("delete from first_start_attempts returning user_id, display_name, slot_id, result_code, attempted_at",
                (rs, i) -> new FirstStart.Refused(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()))
                .stream().sorted(java.util.Comparator.comparing(FirstStart.Refused::at)).toList();
    }
}
