package com.sdv.platform.security.auth.oidc;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.admission.AdmissionSettings;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.admission.EntityMembershipResolver;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;
import com.documentplatform.identity.store.MachineBinding;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.EntityChoicePrincipal;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import com.sdv.platform.security.auth.RoleConflictReporter;
import com.sdv.platform.security.auth.SdvPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0041: the sign-in on platform-core identity, with in-memory lookups in place of the idn_ tables: people's
 * entities and roles from group grants, the entity choice (owner answer 5), and sending systems from client bindings.
 */
class OidcJwtAuthenticationConverterTest {
    static final String ISSUER = "https://login.example.test/oauth2/default";
    static final String SLOT_A = "ENT-TEST-0001";
    static final String SLOT_B = "ENT-TEST-0002";
    static final UUID ENTITY_A = EntityUuid.fromSlotId(SLOT_A);
    static final UUID ENTITY_B = EntityUuid.fromSlotId(SLOT_B);
    static final UUID SOURCE = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    static final String CLIENT = "0oaTESTclient01";

    /** Group grants of ACTIVE entities, per entity: group -> roles. */
    final Map<EntityId, Map<String, Set<String>>> grants = new LinkedHashMap<>();
    boolean connectionActive = true;
    List<String> activatable = List.of();
    List<String> firstStart = List.of();
    final Map<String, MachineBinding> bindings = new HashMap<>();
    Set<EntityId> admissible = Set.of(EntityId.of(ENTITY_A));
    final Map<String, UUID> sources = new HashMap<>(Map.of(ENTITY_A + "|redaction-service", SOURCE));
    String requestedEntity;
    boolean intake;
    final List<RoleConflictReporter.RoleConflict> conflicts = new ArrayList<>();
    boolean failReports;
    /** Task 0057: platform-level grants (group -> platform roles) and ACTIVE entities that have no entity grant for these groups. */
    final Map<String, Set<String>> platformGrants = new LinkedHashMap<>();
    final Set<EntityId> otherActive = new java.util.LinkedHashSet<>();

    OidcJwtAuthenticationConverter converter() {
        return converterWith(new PlatformLevelGrants(key -> platformGrants, key -> active(), SdvRoleCatalog.shipped()));
    }

    OidcJwtAuthenticationConverter converterWith(OidcJwtAuthenticationConverter.PlatformGrants platform) {
        var settings = new OidcJwtAuthenticationConverter.Settings("customer-oidc", "sdv-api", "groups",
                OidcJwtAuthenticationConverter.DEFAULT_CLIENT_ID_CLAIMS, "sdv_entity_id", "sdv_source_id", "sdv_source_key");
        var people = new EntityMembershipResolver(key -> grants, key -> grants.keySet(), SdvRoleCatalog.shipped(),
                new AdmissionSettings(false, true, "customer-oidc", "sdv-api"));
        var lookups = new OidcJwtAuthenticationConverter.SignInLookups() {
            @Override public boolean connectionActive(ConnectionKey key) { return connectionActive; }
            @Override public List<String> activatableSlots(ConnectionKey key, Object groups) { return activatable; }
            @Override public List<String> firstStartSlots(ConnectionKey key, Object groups) { return firstStart; }
        };
        var senders = new OidcJwtAuthenticationConverter.Senders() {
            @Override public Optional<MachineBinding> activeBinding(String clientId) { return Optional.ofNullable(bindings.get(clientId)); }
            @Override public Set<EntityId> admissible(ConnectionKey key, String clientId) { return admissible; }
        };
        OidcJwtAuthenticationConverter.Sources sourceLookup = (entity, key) -> Optional.ofNullable(sources.get(entity + "|" + key));
        var request = new OidcJwtAuthenticationConverter.RequestView() {
            @Override public String requestedEntity() { return requestedEntity; }
            @Override public boolean intake() { return intake; }
        };
        var normalizer = new OidcJwtIdentityNormalizer("customer-oidc",
                new IdentityClaimConfig("preferred_username", "email", "name", "groups"));
        return new OidcJwtAuthenticationConverter(settings, normalizer, people, platform, lookups, senders, sourceLookup, request,
                c -> { if (failReports) throw new IllegalStateException("db down"); conflicts.add(c); });
    }

    void grant(UUID entity, String group, String... roles) {
        grants.computeIfAbsent(EntityId.of(entity), e -> new LinkedHashMap<>()).put(group, Set.of(roles));
    }

    // ---------------------------------------------------------------- people

    @Test
    void aPersonGetsTheRolesTheirGroupsAreGrantedInTheirOnlyEntity() {
        grant(ENTITY_A, "claims-users", "BUSINESS_USER");
        grant(ENTITY_A, "records-stewards", "DOCUMENT_STEWARD");
        Authentication auth = converter().convert(person(List.of("claims-users", "records-stewards", "unrelated"),
                Map.of("name", "  Maya Okafor ", "email", "maya.okafor@example.com")));

        SdvPrincipal p = assertInstanceOf(SdvPrincipal.class, auth.getPrincipal());
        assertEquals(ENTITY_A, p.tenantId());
        assertEquals(Set.of(SdvRole.BUSINESS_USER, SdvRole.DOCUMENT_STEWARD), p.roles());
        assertEquals("Maya Okafor", p.displayName());
        assertEquals("maya.okafor@example.com", p.email());
        assertEquals(Set.of(ENTITY_A), p.entities());
        assertTrue(auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_BUSINESS_USER")));
        var details = assertInstanceOf(OidcJwtAuthenticationConverter.VerifiedSignIn.class, auth.getDetails());
        assertEquals("customer-oidc", details.key().providerId());
    }

    @Test
    void blankNameAndEmailAreDropped() {
        grant(ENTITY_A, "claims-users", "BUSINESS_USER");
        SdvPrincipal p = (SdvPrincipal) converter().convert(person(List.of("claims-users"), Map.of("name", "  ", "email", ""))).getPrincipal();
        assertNull(p.displayName());
        assertNull(p.email());
    }

    @Test
    void groupsWithNoGrantAreRefusedWithAut007() {
        grant(ENTITY_A, "claims-users", "BUSINESS_USER");
        assertCode(SdvErrorCode.AUT_007, 401, () -> converter().convert(person(List.of("unrelated"), Map.of())));
    }

    @Test
    void noActiveLoginConnectionIsAut006() {
        connectionActive = false;
        assertCode(SdvErrorCode.AUT_006, 401, () -> converter().convert(person(List.of("claims-users"), Map.of())));
    }

    @Test
    void aGrantOfThePlatformRoleIsNeverHonoured() {
        grant(ENTITY_A, "ops", "PLATFORM_OPERATIONS_ADMIN");
        assertCode(SdvErrorCode.AUT_007, 401, () -> converter().convert(person(List.of("ops"), Map.of())));
    }

    @Test
    void twoEntitiesAndNoChoiceGiveAnEntityChoice() {
        grant(ENTITY_A, "claims-users", "BUSINESS_USER");
        grant(ENTITY_B, "legal-stewards", "DOCUMENT_STEWARD");
        Authentication auth = converter().convert(person(List.of("claims-users", "legal-stewards"), Map.of()));
        EntityChoicePrincipal choice = assertInstanceOf(EntityChoicePrincipal.class, auth.getPrincipal());
        assertEquals(Set.of(ENTITY_A, ENTITY_B), choice.entities());
        assertEquals(List.of(OidcJwtAuthenticationConverter.ENTITY_CHOICE_AUTHORITY),
                auth.getAuthorities().stream().map(a -> a.getAuthority()).toList());
    }

    @Test
    void theChosenEntityGivesThatEntitysRolesOnly() {
        grant(ENTITY_A, "claims-users", "BUSINESS_USER");
        grant(ENTITY_B, "legal-stewards", "DOCUMENT_STEWARD");
        requestedEntity = ENTITY_B.toString();
        SdvPrincipal p = (SdvPrincipal) converter().convert(person(List.of("claims-users", "legal-stewards"), Map.of())).getPrincipal();
        assertEquals(ENTITY_B, p.tenantId());
        assertEquals(Set.of(SdvRole.DOCUMENT_STEWARD), p.roles());
        assertEquals(Set.of(ENTITY_A, ENTITY_B), p.entities());
    }

    @Test
    void aChoiceOfAnotherEntityOrNoEntityIdIsAut010With409() {
        grant(ENTITY_A, "claims-users", "BUSINESS_USER");
        requestedEntity = ENTITY_B.toString();
        assertCode(SdvErrorCode.AUT_010, 409, () -> converter().convert(person(List.of("claims-users"), Map.of())));
        requestedEntity = "not-an-entity";
        assertCode(SdvErrorCode.AUT_010, 409, () -> converter().convert(person(List.of("claims-users"), Map.of())));
    }

    @Test
    void anItAdminOfASlotMayActivateTheFirstEntityWhileNoneIsActive() {
        // Task 0098: before, an IT Admin alone was refused (AUT-007) with no entity active.
        firstStart = List.of(SLOT_A);
        Authentication auth = converter().convert(person(List.of("acme-it"), Map.of()));
        EntityChoicePrincipal choice = assertInstanceOf(EntityChoicePrincipal.class, auth.getPrincipal());
        assertEquals(List.of(), choice.activatableSlots());
        assertEquals(List.of(SLOT_A), choice.firstStartSlots());
        firstStart = List.of();
        assertCode(SdvErrorCode.AUT_007, 401, () -> converter().convert(person(List.of("acme-it"), Map.of())));
    }

    @Test
    void aBusinessAdminOfASlotNotActivatedYetMayChooseToActivateIt() {
        activatable = List.of(SLOT_A);
        Authentication auth = converter().convert(person(List.of("acme-admins"), Map.of()));
        EntityChoicePrincipal choice = assertInstanceOf(EntityChoicePrincipal.class, auth.getPrincipal());
        assertEquals(Set.of(), choice.entities());
        assertEquals(List.of(SLOT_A), choice.activatableSlots());
    }

    @Test
    void rolesThatCanNeverBeHeldTogetherAreWithheldAndReportedInThatEntity() {
        grant(ENTITY_A, "hc-reviewers", "RESTRICTED_STEWARD");
        grant(ENTITY_A, "claims-users", "BUSINESS_USER");
        grant(ENTITY_A, "acme-admins", "BUSINESS_ADMIN");
        SdvPrincipal p = (SdvPrincipal) converter().convert(person(List.of("hc-reviewers", "claims-users", "acme-admins"), Map.of())).getPrincipal();
        assertEquals(Set.of(SdvRole.BUSINESS_USER), p.roles());
        assertEquals(1, conflicts.size());
        assertEquals(ENTITY_A, conflicts.get(0).tenantId());
        assertEquals(Set.of(SdvRole.RESTRICTED_STEWARD, SdvRole.BUSINESS_ADMIN), conflicts.get(0).withheld());
    }

    @Test
    void onlyConflictingRolesLeaveNoRoleAndRefuseWithRol004() {
        grant(ENTITY_A, "hc-reviewers", "RESTRICTED_STEWARD");
        grant(ENTITY_A, "it", "IT_ADMIN");
        assertCode(SdvErrorCode.ROL_004, 401, () -> converter().convert(person(List.of("hc-reviewers", "it"), Map.of())));
    }

    // ---------------------------------------------------------------- platform-level grants (task 0057)

    Set<EntityId> active() {
        Set<EntityId> all = new java.util.LinkedHashSet<>(grants.keySet());
        all.addAll(otherActive);
        return all;
    }

    @Test
    void aPlatformLevelGrantGivesThePlatformRoleInEveryActiveEntity() {
        platformGrants.put("PlatformOperations", Set.of("PLATFORM_OPERATIONS_ADMIN"));
        otherActive.add(EntityId.of(ENTITY_A));
        otherActive.add(EntityId.of(ENTITY_B));
        EntityChoicePrincipal choice = assertInstanceOf(EntityChoicePrincipal.class,
                converter().convert(person(List.of("PlatformOperations"), Map.of())).getPrincipal());
        assertEquals(Set.of(ENTITY_A, ENTITY_B), choice.entities());

        requestedEntity = ENTITY_B.toString();
        SdvPrincipal p = assertInstanceOf(SdvPrincipal.class, converter().convert(person(List.of("PlatformOperations"), Map.of())).getPrincipal());
        assertEquals(ENTITY_B, p.tenantId());
        assertEquals(Set.of(SdvRole.PLATFORM_OPERATIONS_ADMIN), p.roles());
        assertTrue(conflicts.isEmpty());
    }

    @Test
    void aPlatformRoleIsAddedToTheEntityRolesOfTheSameSignIn() {
        grant(ENTITY_A, "it", "IT_ADMIN");
        platformGrants.put("PlatformOperations", Set.of("PLATFORM_OPERATIONS_ADMIN"));
        SdvPrincipal p = assertInstanceOf(SdvPrincipal.class,
                converter().convert(person(List.of("it", "PlatformOperations"), Map.of())).getPrincipal());
        assertEquals(ENTITY_A, p.tenantId());
        assertEquals(Set.of(SdvRole.IT_ADMIN, SdvRole.PLATFORM_OPERATIONS_ADMIN), p.roles());
    }

    @Test
    void aPlatformLevelGrantNeverGoesWithRestrictedSteward() {
        grant(ENTITY_A, "claims-users", "BUSINESS_USER");
        grant(ENTITY_A, "hc-reviewers", "RESTRICTED_STEWARD");
        platformGrants.put("PlatformOperations", Set.of("PLATFORM_OPERATIONS_ADMIN"));
        SdvPrincipal p = assertInstanceOf(SdvPrincipal.class, converter().convert(
                person(List.of("claims-users", "hc-reviewers", "PlatformOperations"), Map.of())).getPrincipal());
        assertEquals(Set.of(SdvRole.BUSINESS_USER), p.roles(), "Restricted Steward and the platform role are both withheld");
        assertEquals(1, conflicts.size());
        assertEquals(ENTITY_A, conflicts.get(0).tenantId());
        assertEquals(Set.of(SdvRole.RESTRICTED_STEWARD, SdvRole.PLATFORM_OPERATIONS_ADMIN), conflicts.get(0).withheld());

        conflicts.clear();
        assertCode(SdvErrorCode.ROL_004, 401, () -> converter().convert(person(List.of("hc-reviewers", "PlatformOperations"), Map.of())));
        assertEquals(1, conflicts.size(), "the conflict is still recorded when no role is left");
    }

    @Test
    void aPlatformLevelGrantOfAnEntityRoleIsRefused() {
        otherActive.add(EntityId.of(ENTITY_A));
        platformGrants.put("everyone", Set.of("BUSINESS_ADMIN"));
        assertCode(SdvErrorCode.AUT_007, 401, () -> converter().convert(person(List.of("everyone"), Map.of())));
    }

    @Test
    void platformGrantsThatCannotBeReadRefuseTheSignInAsTheDatabaseBeingAway() {
        grant(ENTITY_A, "claims-users", "BUSINESS_USER");
        assertThrows(IllegalStateException.class, () -> converterWith(new PlatformLevelGrants(key -> null, key -> active(),
                SdvRoleCatalog.shipped())).convert(person(List.of("claims-users"), Map.of())));
        platformGrants.put("PlatformOperations", Set.of("PLATFORM_OPERATIONS_ADMIN"));
        assertThrows(IllegalStateException.class, () -> converterWith(new PlatformLevelGrants(key -> platformGrants, key -> null,
                SdvRoleCatalog.shipped())).convert(person(List.of("PlatformOperations"), Map.of())));
    }

    // ---------------------------------------------------------------- sending systems

    @Test
    void aBoundClientDeliversForItsBindingsEntityAndSourceWithoutCustomClaims() {
        bind();
        IngestionSourcePrincipal p = assertInstanceOf(IngestionSourcePrincipal.class,
                converter().convert(sender(Map.of())).getPrincipal());
        assertEquals(ENTITY_A, p.tenantId());
        assertEquals(SOURCE, p.sourceId());
    }

    @Test
    void matchingClaimsAreAcceptedAndTheOldTenantClaimIsIgnored() {
        bind();
        var p = (IngestionSourcePrincipal) converter().convert(sender(Map.of("sdv_entity_id", ENTITY_A.toString(),
                "sdv_source_id", SOURCE.toString(), "sdv_source_key", "redaction-service",
                "sdv_tenant_id", UUID.randomUUID().toString()))).getPrincipal();
        assertEquals(ENTITY_A, p.tenantId());
    }

    @Test
    void anEntityOrSourceClaimThatDoesNotMatchTheBindingIsAut012() {
        bind();
        assertCode(SdvErrorCode.AUT_012, 401, () -> converter().convert(sender(Map.of("sdv_entity_id", ENTITY_B.toString()))));
        assertCode(SdvErrorCode.AUT_012, 401, () -> converter().convert(sender(Map.of("sdv_entity_id", "not-a-uuid"))));
        assertCode(SdvErrorCode.AUT_012, 401, () -> converter().convert(sender(Map.of("sdv_source_id", UUID.randomUUID().toString()))));
        assertCode(SdvErrorCode.AUT_012, 401, () -> converter().convert(sender(Map.of("sdv_source_key", "loan-origination"))));
    }

    @Test
    void aBindingWhoseEntityOrConnectionIsNotActiveIsAut011() {
        bind();
        admissible = Set.of();
        assertCode(SdvErrorCode.AUT_011, 401, () -> converter().convert(sender(Map.of())));
    }

    @Test
    void aBindingToASourceTheEntityDoesNotHaveIsAut013() {
        bind();
        sources.clear();
        assertCode(SdvErrorCode.AUT_013, 401, () -> converter().convert(sender(Map.of())));
    }

    @Test
    void aBoundClientWhoseTokenAlsoCarriesGroupsIsAut005() {
        bind();
        assertCode(SdvErrorCode.AUT_005, 401, () -> converter().convert(sender(Map.of("groups", List.of("claims-users")))));
    }

    @Test
    void anUnboundClientWithoutGroupsIsAut011OnAnIntakeAddressAndAut007Elsewhere() {
        intake = true;
        assertCode(SdvErrorCode.AUT_011, 401, () -> converter().convert(sender(Map.of())));
        intake = false;
        assertCode(SdvErrorCode.AUT_007, 401, () -> converter().convert(sender(Map.of())));
    }

    @Test
    void clientIdClaimsThatDisagreeAreAut005() {
        bind();
        assertCode(SdvErrorCode.AUT_005, 401, () -> converter().convert(sender(Map.of("client_id", "someone-else"))));
    }

    // ---------------------------------------------------------------- helpers

    void bind() {
        bindings.put(CLIENT, MachineBinding.create("TEN-TEST-0001", "SDV", CLIENT, SLOT_A, "redaction-service", null));
    }

    static void assertCode(SdvErrorCode code, int status, org.junit.jupiter.api.function.Executable call) {
        SignInRefusedException refused = assertThrows(SignInRefusedException.class, call);
        assertEquals(code, refused.answer().code());
        assertEquals(status, refused.answer().status());
    }

    static Jwt person(List<String> groups, Map<String, Object> claims) {
        Jwt.Builder b = base("person-1").claim("groups", groups).claim("azp", "sdv-web");
        claims.forEach(b::claim);
        return b.build();
    }

    static Jwt sender(Map<String, Object> claims) {
        Jwt.Builder b = base("service-account-" + CLIENT).claim("azp", CLIENT);
        claims.forEach(b::claim);
        return b.build();
    }

    static Jwt.Builder base(String subject) {
        return Jwt.withTokenValue("test-token").header("alg", "RS256").subject(subject).issuer(ISSUER)
                .audience(List.of("sdv-api")).issuedAt(Instant.now().minusSeconds(5)).expiresAt(Instant.now().plusSeconds(300));
    }
}
