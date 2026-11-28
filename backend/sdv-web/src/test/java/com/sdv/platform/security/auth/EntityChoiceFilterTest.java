package com.sdv.platform.security.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Task 0041: a person who has not chosen an entity reaches only the entity list and activation (else 409 AUT-010). */
class EntityChoiceFilterTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletResponse call(Object principal, String method, String path) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new EntityChoiceFilter().doFilter(request, response, chain);
        if (response.getStatus() == 409) assertNotNull(SignInAnswer.on(request));
        else assertNull(SignInAnswer.on(request));
        return response;
    }

    private static EntityChoicePrincipal choice() {
        return new EntityChoicePrincipal("person-1", "Pat Lee", null, Set.of(UUID.randomUUID(), UUID.randomUUID()), List.of());
    }

    @Test
    void theEntityListAndActivationAreOpen() throws Exception {
        assertEquals(200, call(choice(), "GET", "/api/v1/session/entities").getStatus());
        assertEquals(200, call(choice(), "POST", "/api/v1/admin/entities/ENT-ACME-0001/activate").getStatus());
        // Task 0098: the first-start screen.
        assertEquals(200, call(choice(), "GET", "/api/v1/first-start").getStatus());
        assertEquals(200, call(choice(), "POST", "/api/v1/first-start/activate").getStatus());
        assertEquals(409, call(choice(), "POST", "/api/v1/first-start").getStatus());
        assertEquals(409, call(choice(), "GET", "/api/v1/first-start/activate").getStatus());
    }

    @Test
    void everythingElseIsChooseAnEntity() throws Exception {
        for (String[] r : List.of(new String[]{"GET", "/api/v1/session"}, new String[]{"GET", "/api/v1/business/home"},
                new String[]{"POST", "/api/v1/session/entities"}, new String[]{"GET", "/api/v1/admin/entities/ENT-A/activate"},
                new String[]{"POST", "/api/v1/admin/entities/ENT-A/activate/x"})) {
            MockHttpServletResponse response = call(choice(), r[0], r[1]);
            assertEquals(409, response.getStatus(), r[0] + " " + r[1]);
            assertEquals("SDV-AUT-010", response.getErrorMessage());
        }
    }

    @Test
    void aChosenEntityIsNeverStopped() throws Exception {
        assertEquals(200, call(new SdvPrincipal(UUID.randomUUID(), "p", Set.of()), "GET", "/api/v1/session").getStatus());
    }
}
