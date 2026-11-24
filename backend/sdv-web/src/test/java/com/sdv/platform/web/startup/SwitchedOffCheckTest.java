package com.sdv.platform.web.startup;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0037, item 4: sign-in or the key service switched off (SDV-SYS-006, SDV-KEY-001). */
class SwitchedOffCheckTest {
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(SwitchedOffCheck.class)).addAppender(logs);
    }

    @AfterEach
    void stopCapturing() {
        ((Logger) LoggerFactory.getLogger(SwitchedOffCheck.class)).detachAppender(logs);
    }

    private List<String> lines(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void aLocalStartWarnsAndNamesWhatWillFail() {
        assertDoesNotThrow(() -> new SwitchedOffCheck(true, "disabled", "disabled"));
        List<String> warnings = lines(Level.WARN);
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(0).startsWith("SDV-SYS-006 Sign-in is switched off (SDV_AUTH_MODE=disabled)."), warnings.get(0));
        assertTrue(warnings.get(0).contains("every request except health and system information is refused (401)"));
        assertTrue(warnings.get(1).startsWith("SDV-KEY-001 Encryption is not set up (SDV_KEY_PROVIDER=disabled)."), warnings.get(1));
        assertTrue(warnings.get(1).contains("uploading, finishing, viewing, printing, downloading and redacting documents will fail"));
        assertTrue(lines(Level.ERROR).isEmpty());
    }

    @Test
    void aPackagedStartRefusesEither() {
        StartupFailure auth = assertThrows(StartupFailure.class, () -> new SwitchedOffCheck(false, "disabled", "vault"));
        assertEquals(SdvErrorCode.SYS_006, auth.errorCode());
        StartupFailure key = assertThrows(StartupFailure.class, () -> new SwitchedOffCheck(false, "enterprise-jwt", " DISABLED "));
        assertEquals(SdvErrorCode.KEY_001, key.errorCode());
        assertTrue(key.action().contains("SDV_KEY_PROVIDER=vault"));
    }

    @Test
    void withBothOffAPackagedStartLogsBothThenRefuses() {
        StartupFailure f = assertThrows(StartupFailure.class, () -> new SwitchedOffCheck(false, "disabled", "disabled"));
        assertEquals(SdvErrorCode.SYS_006, f.errorCode());
        List<String> errors = lines(Level.ERROR);
        assertEquals(2, errors.size());
        assertTrue(errors.get(0).startsWith("SDV-SYS-006 ") && errors.get(0).contains("A packaged start refuses this."));
        assertTrue(errors.get(1).startsWith("SDV-KEY-001 "));
    }

    @Test
    void nothingIsSaidWhenBothAreSet() {
        assertDoesNotThrow(() -> new SwitchedOffCheck(false, "enterprise-jwt", "vault"));
        assertDoesNotThrow(() -> new SwitchedOffCheck(true, "local-header", "vault"));
        assertTrue(logs.list.isEmpty());
    }

    /** Task 0064. */
    @Test
    void aReleaseRefusesTheLocalProfile() {
        StartupFailure f = assertThrows(StartupFailure.class, () -> SwitchedOffCheck.local(true, false));
        assertEquals(SdvErrorCode.SYS_005, f.errorCode());
        assertTrue(SwitchedOffCheck.local(true, true));
        assertFalse(SwitchedOffCheck.local(false, false));
    }

    @Test
    void theDefaultsAreDisabledAndOnlyTheLocalProfileIsLocal() {
        MockEnvironment packaged = new MockEnvironment();
        assertEquals(SdvErrorCode.SYS_006, assertThrows(StartupFailure.class, () -> new SwitchedOffCheck(packaged)).errorCode());
        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertDoesNotThrow(() -> new SwitchedOffCheck(local));
    }
}
