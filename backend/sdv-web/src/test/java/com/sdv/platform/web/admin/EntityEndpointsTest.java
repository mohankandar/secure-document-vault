package com.sdv.platform.web.admin;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.store.ActivationOutcome;
import com.documentplatform.identity.store.EntityRecord;
import com.documentplatform.identity.store.EntityStatus;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.LoginConnectionStore;
import com.documentplatform.identity.store.SlotActivation;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantGuardrails;
import com.sdv.platform.domain.tenant.TenantStatus;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.EntityChoicePrincipal;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.security.auth.oidc.OidcJwtAuthenticationConverter;
import com.sdv.platform.web.security.SessionEntitiesController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 0041: the entity list and switch (owner answer 5), the Business Admin's activation (answer 11) and the IT
 * Admin's read-only login connection (answer 6).
 */
class EntityEndpointsTest {
    static final String SLOT_A = "ENT-ACME-0001";
    static final String SLOT_B = "ENT-ACME-0002";
    static final UUID A = EntityUuid.fromSlotId(SLOT_A);
    static final UUID B = EntityUuid.fromSlotId(SLOT_B);
    static final ConnectionKey KEY = new ConnectionKey("customer-oidc", URI.create("https://login.example.test"), "sdv-api");

    final TenantRepository tenants = mock(TenantRepository.class);
    final AuditEventPort audit = mock(AuditEventPort.class);
    final OidcJwtAuthenticationConverter.SignInLookups lookups = mock(OidcJwtAuthenticationConverter.SignInLookups.class);
    final SlotActivation activation = mock(SlotActivation.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    static Tenant tenant(UUID id, String name) {
        return new Tenant(id, name.toLowerCase(), name, TenantStatus.ACTIVE, TenantGuardrails.defaults(), Instant.EPOCH, Instant.EPOCH);
    }

    static void signIn(Object principal, boolean oidc) {
        var auth = new UsernamePasswordAuthenticationToken(principal, null, List.of());
        if (oidc) auth.setDetails(new OidcJwtAuthenticationConverter.VerifiedSignIn(KEY, List.of("acme-admins")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    SessionEntitiesController session(String mode) {
        when(tenants.findById(A)).thenReturn(Optional.of(tenant(A, "Acme Mortgage")));
        when(tenants.findById(B)).thenReturn(Optional.of(tenant(B, "Acme Bank")));
        return new SessionEntitiesController(tenants, audit, new SecurityContextFacade(), lookups, mode);
    }

    // ------------------------------------------------------------------ the entity list and the switch

    @Test
    void aPersonWhoMustChooseGetsTheirEntitiesAndNoCurrentOne() {
        signIn(new EntityChoicePrincipal("pat", "Pat Lee", null, Set.of(A, B), List.of()), true);
        var answer = session("enterprise-jwt").entities();
        assertNull(answer.current());
        assertEquals(List.of("Acme Bank", "Acme Mortgage"), answer.entities().stream().map(e -> e.name()).toList());
    }

    @Test
    void aPersonInAnEntityGetsTheirEntitiesAndTheSlotsTheyMayActivate() {
        when(lookups.activatableSlots(eq(KEY), any())).thenReturn(List.of(SLOT_B));
        signIn(new SdvPrincipal(A, "pat", Set.of(SdvRole.BUSINESS_ADMIN), "Pat Lee", null, Set.of(A)), true);
        var answer = session("enterprise-jwt").entities();
        assertEquals(A, answer.current());
        assertEquals(1, answer.entities().size());
        assertEquals(List.of(SLOT_B), answer.activatableSlots());
    }

    @Test
    void theLocalSignInListsEveryActiveEntity() {
        when(tenants.findActive()).thenReturn(List.of(tenant(A, "Acme Mortgage"), tenant(B, "Acme Bank")));
        signIn(new SdvPrincipal(A, "local-business-user", Set.of(SdvRole.BUSINESS_USER)), false);
        assertEquals(2, session("local-header").entities().entities().size());
    }

    @Test
    void everySwitchIsAuditedInTheEntitySwitchedTo() {
        var controller = session("enterprise-jwt");
        signIn(new SdvPrincipal(B, "pat", Set.of(SdvRole.BUSINESS_USER), "Pat Lee", null, Set.of(A, B)), true);
        controller.switched(new SessionEntitiesController.SwitchRequest(A));
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        assertEquals("ENTITY_SWITCHED", event.getValue().action());
        assertEquals(B, event.getValue().tenantId());
        assertTrue(event.getValue().safeDetailsJson().contains(A.toString()));
    }

    @Test
    void aSwitchFromAnEntityThatIsNotTheSignInsIsRefused() {
        var controller = session("enterprise-jwt");
        signIn(new SdvPrincipal(A, "pat", Set.of(SdvRole.BUSINESS_USER), "Pat Lee", null, Set.of(A)), true);
        var refused = assertThrows(CodedRefusal.BadRequest.class, () -> controller.switched(new SessionEntitiesController.SwitchRequest(B)));
        assertEquals(SdvErrorCode.AUT_010, refused.errorCode());
        verify(audit, never()).append(any());
    }

    // ------------------------------------------------------------------ activation

    EntityActivationController activation(String mode) {
        return new EntityActivationController(activation, tenants, audit, lookups, mode);
    }

    static EntityRecord record(String slot, String name) {
        return new EntityRecord(slot, EntityUuid.fromSlotId(slot), "TEN-ACME-0001", EntityStatus.ACTIVE, name, "pat", Instant.EPOCH);
    }

    @Test
    void aBusinessAdminOfANewSlotActivatesItBeforeAnyEntityIsActive() {
        when(activation.activate(SLOT_A, "Acme Mortgage", "pat"))
                .thenReturn(new ActivationOutcome(ActivationOutcome.Result.ACTIVATED, SLOT_A, record(SLOT_A, "Acme Mortgage")));
        when(tenants.findById(A)).thenReturn(Optional.empty());
        signIn(new EntityChoicePrincipal("pat", "Pat Lee", null, Set.of(), List.of(SLOT_A)), true);
        var done = activation("enterprise-jwt").activate(SLOT_A, new EntityActivationController.ActivateRequest("Acme Mortgage"));
        assertEquals(A, done.entityId());
        assertEquals("ACTIVATED", done.result());
        verify(tenants).createEntity(A, "ent-acme-0001", "Acme Mortgage");
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        assertEquals("ENTITY_ACTIVATED", event.getValue().action());
        assertEquals(A, event.getValue().tenantId());
    }

    @Test
    void someoneWithoutABusinessAdminGrantOnTheSlotIsRefused() {
        when(lookups.activatableSlots(eq(KEY), any())).thenReturn(List.of());
        signIn(new SdvPrincipal(A, "pat", Set.of(SdvRole.BUSINESS_ADMIN), "Pat Lee", null, Set.of(A)), true);
        var refused = assertThrows(CodedRefusal.Forbidden.class, () -> activation("enterprise-jwt").activate(SLOT_B,
                new EntityActivationController.ActivateRequest("Acme Bank")));
        assertEquals(SdvErrorCode.ENT_002, refused.errorCode());
        verify(activation, never()).activate(anyString(), anyString(), anyString());
    }

    @Test
    void platformCoresRefusalIsEnt001WithItsSentence() {
        when(lookups.activatableSlots(eq(KEY), any())).thenReturn(List.of(SLOT_B));
        when(activation.activate(SLOT_B, "Acme Bank", "pat"))
                .thenReturn(new ActivationOutcome(ActivationOutcome.Result.NOT_IN_LICENCE, SLOT_B, null));
        signIn(new SdvPrincipal(A, "pat", Set.of(SdvRole.BUSINESS_ADMIN), "Pat Lee", null, Set.of(A)), true);
        var refused = assertThrows(CodedRefusal.Conflict.class, () -> activation("enterprise-jwt").activate(SLOT_B,
                new EntityActivationController.ActivateRequest("Acme Bank")));
        assertEquals(SdvErrorCode.ENT_001, refused.errorCode());
        assertEquals("The licence in force does not include slot ENT-ACME-0002.", refused.getMessage());
        verify(tenants, never()).createEntity(any(), any(), any());
    }

    @Test
    void anEarlierActivationWithoutVerosRowIsCompleted() {
        when(lookups.activatableSlots(eq(KEY), any())).thenReturn(List.of(SLOT_B));
        when(activation.activate(SLOT_B, "Acme Bank", "pat"))
                .thenReturn(new ActivationOutcome(ActivationOutcome.Result.ALREADY_ACTIVATED, SLOT_B, record(SLOT_B, "Acme Bank")));
        when(tenants.findById(B)).thenReturn(Optional.empty());
        signIn(new SdvPrincipal(A, "pat", Set.of(SdvRole.BUSINESS_ADMIN), "Pat Lee", null, Set.of(A)), true);
        assertEquals("COMPLETED", activation("enterprise-jwt").activate(SLOT_B, new EntityActivationController.ActivateRequest("Acme Bank")).result());
        verify(tenants).createEntity(B, "ent-acme-0002", "Acme Bank");
    }

    // ------------------------------------------------------------------ the login connection, read only

    @Test
    void theItAdminReadsTheConnectionInUseAndNobodyElseDoes() {
        RoleCapabilityPolicyPort overrides = mock(RoleCapabilityPolicyPort.class);
        when(overrides.findOverrides(any(), anyString())).thenReturn(Map.of());
        AuthorizationService authorization = new AuthorizationService(audit, new CapabilityPolicyService(overrides));
        PlatformIdentityReads reads = mock(PlatformIdentityReads.class);
        when(reads.slotOf(A)).thenReturn(Optional.of(new PlatformIdentityReads.EntitySlot("TEN-ACME-0001", SLOT_A, "ACTIVE", "Acme Mortgage")));
        LoginConnectionStore store = mock(LoginConnectionStore.class);
        LoginConnection active = new LoginConnection(UUID.randomUUID(), "TEN-ACME-0001", "SDV", "okta", IdentityProviderType.OIDC,
                URI.create("https://login.example.test/oauth2/default"), "vero-web", "api://vero", null,
                IdentityConnectionStatus.ACTIVE, 3);
        when(store.active("TEN-ACME-0001", "SDV")).thenReturn(Optional.of(active));
        var controller = new LoginConnectionAdminController(new SecurityContextFacade(), authorization, reads, store,
                mock(com.sdv.platform.web.security.ActiveConnectionCheck.class), "enterprise-jwt");

        signIn(new SdvPrincipal(A, "it", Set.of(SdvRole.IT_ADMIN)), true);
        var overview = controller.overview();
        assertEquals("okta", overview.active().providerId());
        assertEquals(3, overview.active().configurationVersion());
        assertTrue(overview.changedBySetupOnly());

        signIn(new SdvPrincipal(A, "pat", Set.of(SdvRole.BUSINESS_ADMIN)), true);
        assertThrows(AccessDeniedException.class, controller::overview);
    }

    /** Task 0057 (LV-16 item 2): "Test sign-in" checks the connection in use, with the IT Admin's own token; read only. */
    @Test
    void theItAdminTestsTheConnectionInUseWithTheirOwnTokenAndNothingChanges() {
        RoleCapabilityPolicyPort overrides = mock(RoleCapabilityPolicyPort.class);
        when(overrides.findOverrides(any(), anyString())).thenReturn(Map.of());
        AuthorizationService authorization = new AuthorizationService(audit, new CapabilityPolicyService(overrides));
        PlatformIdentityReads reads = mock(PlatformIdentityReads.class);
        when(reads.slotOf(A)).thenReturn(Optional.of(new PlatformIdentityReads.EntitySlot("TEN-ACME-0001", SLOT_A, "ACTIVE", "Acme Mortgage")));
        LoginConnectionStore store = mock(LoginConnectionStore.class);
        LoginConnection active = new LoginConnection(UUID.randomUUID(), "TEN-ACME-0001", "SDV", "okta", IdentityProviderType.OIDC,
                URI.create("https://login.example.test/oauth2/default"), "vero-web", "api://vero", null,
                IdentityConnectionStatus.ACTIVE, 3);
        when(store.active("TEN-ACME-0001", "SDV")).thenReturn(Optional.of(active));
        var check = mock(com.sdv.platform.web.security.ActiveConnectionCheck.class);
        when(check.test(active, "own-token")).thenReturn(new com.sdv.platform.web.security.ActiveConnectionCheck.Result(
                false, "IDN_TEST_KEYS_MISSING", "No usable signing key.", null, false));
        when(check.test(active, null)).thenReturn(new com.sdv.platform.web.security.ActiveConnectionCheck.Result(
                true, null, "The login system answered as expected.", null, false));
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer own-token");

        signIn(new SdvPrincipal(A, "it", Set.of(SdvRole.IT_ADMIN)), true);
        var answer = new LoginConnectionAdminController(new SecurityContextFacade(), authorization, reads, store, check,
                "enterprise-jwt").test(request);
        assertEquals("FAILED", answer.outcome());
        assertEquals("IDN_TEST_KEYS_MISSING", answer.code());
        assertEquals(active.connectionId(), answer.connectionId());
        assertEquals(3, answer.configurationVersion());
        verify(check).test(active, "own-token");
        verify(store, org.mockito.Mockito.never()).update(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any());
        verify(store, org.mockito.Mockito.never()).insert(any(), any(), any());

        // The local test sign-in's headers are no login-system token: nothing is handed on.
        new LoginConnectionAdminController(new SecurityContextFacade(), authorization, reads, store, check, "local-header").test(request);
        verify(check).test(active, null);

        when(store.active("TEN-ACME-0001", "SDV")).thenReturn(Optional.empty());
        var none = new LoginConnectionAdminController(new SecurityContextFacade(), authorization, reads, store, check,
                "enterprise-jwt").test(request);
        assertEquals("NO_CONNECTION", none.outcome());

        signIn(new SdvPrincipal(A, "pat", Set.of(SdvRole.BUSINESS_ADMIN)), true);
        assertThrows(AccessDeniedException.class, () -> new LoginConnectionAdminController(new SecurityContextFacade(),
                authorization, reads, store, check, "enterprise-jwt").test(request));
    }
}
