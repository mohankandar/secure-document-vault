package com.sdv.platform.web.system;

import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.web.schema.DatabaseSchemaCheck;
import com.sdv.platform.web.schema.SchemaVersion;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Task 0071: how it runs and the database's state only for who looks after it. (Signed in only: SignInBaselineTest.) */
class SystemInfoControllerTest {
    private static SystemInfoController.SystemInfo as(SdvRole role) {
        DatabaseSchemaCheck check = mock(DatabaseSchemaCheck.class, RETURNS_DEEP_STUBS);
        when(check.status().current()).thenReturn(true);
        when(check.status().message()).thenReturn("m");
        when(check.status().state()).thenReturn(SchemaVersion.State.values()[0]);
        var security = mock(com.sdv.platform.security.auth.SecurityContextFacade.class);
        when(security.requirePrincipal()).thenReturn(new SdvPrincipal(UUID.randomUUID(), "p", Set.of(role), "Pat", null));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("x");
        return new SystemInfoController(environment, check, security).systemInfo();
    }

    @Test
    void onlyItAndPlatformOperationsSeeProfilesAndSchema() {
        for (SdvRole role : SdvRole.values()) {
            boolean technical = role == SdvRole.IT_ADMIN || role == SdvRole.PLATFORM_OPERATIONS_ADMIN;
            assertEquals(technical, as(role).activeProfiles() != null, role.name());
            assertEquals(technical, as(role).databaseSchema() != null, role.name());
            assertNotNull(as(role).version());
        }
    }
}
