package com.sdv.platform.web.admin;

import com.documentplatform.identity.admission.ConnectionKey;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.EntityChoicePrincipal;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.oidc.OidcJwtAuthenticationConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 0098: the first-start addresses. Gone answers as nothing at the address; the slots are read fresh. */
class FirstStartControllerTest {
    final FirstStart firstStart = mock(FirstStart.class);
    final OidcJwtAuthenticationConverter.SignInLookups lookups = mock(OidcJwtAuthenticationConverter.SignInLookups.class);
    final FirstStartController controller = new FirstStartController(firstStart, lookups);
    final ConnectionKey key = new ConnectionKey("customer-oidc", URI.create("https://login.example.test"), "sdv-api");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void signedIn(Object principal, boolean verified) {
        var token = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        if (verified) token.setDetails(new OidcJwtAuthenticationConverter.VerifiedSignIn(key, List.of("acme-it")));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @Test
    void someoneInAnEntityFindsNothingHere() {
        signedIn(new SdvPrincipal(UUID.randomUUID(), "it-1", Set.of(SdvRole.IT_ADMIN)), true);
        assertThrows(NoResourceFoundException.class, controller::open);
        assertThrows(NoResourceFoundException.class, () -> controller.activate(new FirstStartController.ActivateRequest("ENT-1", "A", "{}")));
    }

    @Test
    void theSlotsAreReadFreshAndGoneIsNothingHere() throws Exception {
        signedIn(new EntityChoicePrincipal("it-1", "Ivo", null, Set.of(), List.of(), List.of("ENT-OLD")), true);
        when(lookups.firstStartSlots(any(), any())).thenReturn(List.of("ENT-NOW"));
        when(firstStart.open(any())).thenAnswer(c -> ((FirstStart.Person) c.getArgument(0)).itAdminSlots());
        assertEquals(List.of("ENT-NOW"), controller.open().slots(), "the grants now, not what the sign-in saw");
        org.mockito.Mockito.doThrow(new FirstStart.Gone()).when(firstStart).open(any());
        assertThrows(NoResourceFoundException.class, controller::open);
    }

    @Test
    void theActivationGetsThePersonAndTheRequest() throws Exception {
        signedIn(new EntityChoicePrincipal("it-1", "Ivo", null, Set.of(), List.of(), List.of()), true);
        when(lookups.firstStartSlots(any(), any())).thenReturn(List.of("ENT-1"));
        controller.activate(new FirstStartController.ActivateRequest("ENT-1", "Acme", "{}"));
        verify(firstStart).activate(new FirstStart.Person("it-1", "Ivo", List.of("ENT-1")), "ENT-1", "Acme", "{}");
    }
}
