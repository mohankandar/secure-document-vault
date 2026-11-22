package com.sdv.platform.application.reports;

import com.sdv.platform.application.port.UnidentifiedIntakeAttemptRepository;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.persistence.reports.JdbcUnidentifiedIntakeAttemptRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0040 (owner answer 1 to report 0039 part 2): the 100-rows-a-minute limit on unidentified intake attempts is a
 * setting (sdv.problems.unidentified-attempts.max-rows-per-minute, SDV_UNIDENTIFIED_ATTEMPTS_MAX_ROWS_PER_MINUTE),
 * default 100, a whole number from 1 to 10,000. Anything else stops the start with SDV-SYS-005. The limit at work is
 * in UnidentifiedIntakeAttemptPostgresTest (part 3b).
 */
class UnidentifiedAttemptLimitSettingTest {
    @Test
    void theDefaultIs100() {
        assertEquals(100, UnidentifiedIntakeAttemptRepository.DEFAULT_MAX_ROWS_PER_MINUTE);
        assertEquals(100, new JdbcUnidentifiedIntakeAttemptRepository(null).maxRowsPerMinute());
        assertEquals(100, new JdbcUnidentifiedIntakeAttemptRepository(null, "100").maxRowsPerMinute());
    }

    @Test
    void aWholeNumberFrom1To10000IsTaken() {
        assertEquals(1, new JdbcUnidentifiedIntakeAttemptRepository(null, "1").maxRowsPerMinute());
        assertEquals(250, new JdbcUnidentifiedIntakeAttemptRepository(null, " 250 ").maxRowsPerMinute());
        assertEquals(10_000, new JdbcUnidentifiedIntakeAttemptRepository(null, "10000").maxRowsPerMinute());
    }

    @Test
    void anythingElseStopsTheStart() {
        for (String bad : new String[]{"", " ", "0", "-1", "10001", "99999", "1e3", "1.5", "100 rows", "0100", "abc"}) {
            StartupFailure failure = assertThrows(StartupFailure.class,
                    () -> new JdbcUnidentifiedIntakeAttemptRepository(null, bad), "\"" + bad + "\"");
            assertEquals(SdvErrorCode.SYS_005, failure.errorCode());
            assertTrue(failure.getMessage().contains("SDV_UNIDENTIFIED_ATTEMPTS_MAX_ROWS_PER_MINUTE"), failure.getMessage());
        }
        assertThrows(StartupFailure.class, () -> new JdbcUnidentifiedIntakeAttemptRepository(null, null));
    }
}
