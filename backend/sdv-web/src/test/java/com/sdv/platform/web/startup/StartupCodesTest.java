package com.sdv.platform.web.startup;

import com.sdv.platform.application.approvals.ApprovalSettings;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.FailureAnalysis;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0037, item 5: a start that stops says its code (SDV-SYS-004 to SDV-SYS-012, SDV-KEY-001), the catalog's
 * sentence, the exact cause and what to do, as one block without a stack trace.
 */
class StartupCodesTest {
    static final Set<SdvErrorCode> START_UP = EnumSet.of(SdvErrorCode.SYS_001, SdvErrorCode.SYS_002, SdvErrorCode.SYS_003,
            SdvErrorCode.SYS_004, SdvErrorCode.SYS_005, SdvErrorCode.SYS_006, SdvErrorCode.SYS_007, SdvErrorCode.SYS_008,
            SdvErrorCode.SYS_009, SdvErrorCode.SYS_010, SdvErrorCode.SYS_012, SdvErrorCode.KEY_001);

    private static SdvErrorCode code(Executable start) {
        return assertThrows(StartupFailure.class, start).errorCode();
    }

    @Test
    void everyStartUpCodeHasItsOwnSentenceAndAction() {
        String fallbackSentence = StartupFailure.sentence(SdvErrorCode.SYS_013);
        String fallbackAction = StartupFailure.action(SdvErrorCode.SYS_013);
        for (SdvErrorCode c : START_UP) {
            assertNotEquals(fallbackSentence, StartupFailure.sentence(c), c.code());
            assertNotEquals(fallbackAction, StartupFailure.action(c), c.code());
            assertTrue(StartupFailure.sentence(c).endsWith("."), c.code());
        }
    }

    @Test
    void theFailureReportHasTheCodeTheSentenceTheCauseAndTheAction() {
        StartupFailure cause = new StartupFailure(SdvErrorCode.SYS_010, "sdv.tenant-defaults.time-zone is not a time zone Vero knows: Eastern");
        // Spring wraps what a bean throws; the report still finds it.
        BeanCreationException wrapped = new BeanCreationException("intakeReportService", "Failed to instantiate", cause);
        FailureAnalysis analysis = new StartupFailureAnalyzer().analyze(wrapped);
        assertNotNull(analysis);
        assertEquals("SDV-SYS-010 The install's default time zone is not valid. Cause: sdv.tenant-defaults.time-zone is not a time zone Vero knows: Eastern",
                analysis.getDescription());
        assertTrue(analysis.getAction().contains("America/New_York"));
        assertNull(new StartupFailureAnalyzer().analyze(new IllegalStateException("not ours")));
    }

    @Test
    void theStartUpChecksGiveTheirCodes() {
        assertEquals(SdvErrorCode.SYS_008, code(() -> new ApprovalSettings("0", "7", "", "")));
        assertEquals(SdvErrorCode.SYS_008, code(() -> new ApprovalSettings("24", "7", "not-a-tenant=5", "")));
    }
}
