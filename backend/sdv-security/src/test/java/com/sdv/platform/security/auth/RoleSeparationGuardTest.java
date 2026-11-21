package com.sdv.platform.security.auth;

import com.sdv.platform.security.SdvRole;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0025, rule 1: what every sign-in path does with the roles it resolved. */
class RoleSeparationGuardTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final List<RoleConflictReporter.RoleConflict> reported = new ArrayList<>();
    private final RoleSeparationGuard guard = new RoleSeparationGuard(reported::add);

    @Test
    void conflictingRolesAreWithheldAndReported() {
        EnumSet<SdvRole> roles = guard.apply(TENANT, "u1", "Una", RoleConflictReporter.Source.LOGIN_GROUPS,
                EnumSet.of(SdvRole.RESTRICTED_STEWARD, SdvRole.BUSINESS_ADMIN, SdvRole.BUSINESS_USER));
        assertEquals(EnumSet.of(SdvRole.BUSINESS_USER), roles);
        assertEquals(1, reported.size());
        var conflict = reported.get(0);
        assertEquals(TENANT, conflict.tenantId());
        assertEquals("u1", conflict.subject());
        assertEquals("Una", conflict.displayName());
        assertEquals(RoleConflictReporter.Source.LOGIN_GROUPS, conflict.source());
        assertEquals(Set.of(SdvRole.RESTRICTED_STEWARD, SdvRole.BUSINESS_ADMIN, SdvRole.BUSINESS_USER), conflict.held());
        assertEquals(Set.of(SdvRole.RESTRICTED_STEWARD, SdvRole.BUSINESS_ADMIN), conflict.withheld());
        assertEquals(List.of("RESTRICTED_STEWARD_NOT_ADMIN"), conflict.brokenRules());
    }

    @Test
    void aSignInLeftWithNoRoleIsRefusedWithItsCode() {
        var refused = assertThrows(RoleSeparationGuard.RefusedException.class, () -> guard.apply(TENANT, "u1", null,
                RoleConflictReporter.Source.LOCAL_SIGN_IN, EnumSet.of(SdvRole.RESTRICTED_STEWARD, SdvRole.TENANT_IT_ADMIN)));
        assertTrue(refused.getMessage().startsWith("SDV-ROL-004:"), refused.getMessage());
        assertEquals(1, reported.size(), "the conflict is recorded before the refusal");
    }

    @Test
    void aConflictThatCannotBeRecordedRefusesTheSignIn() {
        RoleSeparationGuard failing = new RoleSeparationGuard(c -> { throw new IllegalStateException("db down"); });
        var refused = assertThrows(RoleSeparationGuard.RefusedException.class, () -> failing.apply(TENANT, "u1", null,
                RoleConflictReporter.Source.LOGIN_GROUPS,
                EnumSet.of(SdvRole.RESTRICTED_STEWARD, SdvRole.BUSINESS_ADMIN, SdvRole.BUSINESS_USER)));
        assertTrue(refused.getMessage().startsWith("SDV-ROL-005:"));

        RoleSeparationGuard none = new RoleSeparationGuard(null);
        assertThrows(RoleSeparationGuard.RefusedException.class, () -> none.apply(TENANT, "u1", null,
                RoleConflictReporter.Source.LOGIN_GROUPS,
                EnumSet.of(SdvRole.RESTRICTED_STEWARD, SdvRole.PLATFORM_OPERATIONS_ADMIN, SdvRole.BUSINESS_USER)));
    }

    @Test
    void noConflictMeansNoReportAndTheSameRoles() {
        RoleSeparationGuard none = new RoleSeparationGuard(null);
        assertEquals(EnumSet.of(SdvRole.RESTRICTED_STEWARD, SdvRole.DOCUMENT_STEWARD), none.apply(TENANT, "u1", null,
                RoleConflictReporter.Source.LOGIN_GROUPS, EnumSet.of(SdvRole.RESTRICTED_STEWARD, SdvRole.DOCUMENT_STEWARD)));
        assertEquals(EnumSet.of(SdvRole.BUSINESS_ADMIN, SdvRole.TENANT_IT_ADMIN), guard.apply(TENANT, "u1", null,
                RoleConflictReporter.Source.LOGIN_GROUPS, EnumSet.of(SdvRole.BUSINESS_ADMIN, SdvRole.TENANT_IT_ADMIN)));
        assertTrue(reported.isEmpty());
    }

    /** Task 0026, carry-over 3: the reporter hears when a sign-in has no conflict, so an earlier conflict can end. */
    private static final class Recorder implements RoleConflictReporter {
        final List<RoleConflict> conflicts = new ArrayList<>();
        final List<String> clear = new ArrayList<>();
        boolean failOnClear;

        @Override public void report(RoleConflict conflict) { conflicts.add(conflict); }

        @Override public void noConflict(UUID tenantId, String subject, String displayName, Source source) {
            if (failOnClear) throw new IllegalStateException("db down");
            clear.add(tenantId + "/" + subject + "/" + displayName + "/" + source);
        }
    }

    @Test
    void aSignInWithoutAConflictTellsTheReporterSoAnEarlierConflictCanEnd() {
        Recorder recorder = new Recorder();
        RoleSeparationGuard withRecorder = new RoleSeparationGuard(recorder);
        withRecorder.apply(TENANT, "u1", "Una", RoleConflictReporter.Source.LOGIN_GROUPS,
                EnumSet.of(SdvRole.RESTRICTED_STEWARD, SdvRole.BUSINESS_ADMIN, SdvRole.BUSINESS_USER));
        assertEquals(1, recorder.conflicts.size());
        assertTrue(recorder.clear.isEmpty(), "a conflicted sign-in doesn't end anything");

        withRecorder.apply(TENANT, "u1", "Una", RoleConflictReporter.Source.LOGIN_GROUPS,
                EnumSet.of(SdvRole.RESTRICTED_STEWARD, SdvRole.BUSINESS_USER));
        assertEquals(List.of(TENANT + "/u1/Una/LOGIN_GROUPS"), recorder.clear);
    }

    @Test
    void notRecordingAnEndNeverRefusesTheSignIn() {
        Recorder recorder = new Recorder();
        recorder.failOnClear = true;
        assertEquals(EnumSet.of(SdvRole.DOCUMENT_STEWARD), new RoleSeparationGuard(recorder).apply(TENANT, "u1", null,
                RoleConflictReporter.Source.LOCAL_SIGN_IN, EnumSet.of(SdvRole.DOCUMENT_STEWARD)));
    }
}
