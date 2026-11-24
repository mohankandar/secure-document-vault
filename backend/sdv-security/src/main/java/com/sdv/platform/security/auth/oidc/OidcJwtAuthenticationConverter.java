package com.sdv.platform.security.auth.oidc;

import com.documentplatform.identity.AuthenticatedIdentity;
import com.documentplatform.identity.IdentityClaims;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.admission.EntityMembershipResolver;
import com.documentplatform.identity.admission.EntitySelection;
import com.documentplatform.identity.admission.IdentityAdmissionException;
import com.documentplatform.identity.admission.MachineEntityAdmission;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;
import com.documentplatform.identity.store.MachineBinding;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.EntityChoicePrincipal;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import com.sdv.platform.security.auth.RoleConflictReporter;
import com.sdv.platform.security.auth.RoleSeparationGuard;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SignInAnswer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Converts a verified OIDC token into Vero's principals, on platform-core identity (task 0041).
 *
 * <p><b>Sending systems.</b> A token is a sending system's when its client ID ({@link Settings#clientIdClaims}, by
 * default {@code azp}, {@code client_id}, {@code cid}; they must agree) has an ACTIVE client binding
 * ({@code idn_machine_binding}, loaded by setup from the client-binding file). The binding, not a claim, gives the entity
 * and the source: platform-core's {@code MachineEntityBindings} must admit exactly that entity (its entity and the
 * customer's login connection ACTIVE), else SDV-AUT-011; the source must be one of the entity's sending systems in
 * Vero, else SDV-AUT-013. The optional claims {@code sdv_entity_id}, {@code sdv_source_id} and {@code sdv_source_key}
 * must match the binding when present, else SDV-AUT-012. The old {@code sdv_tenant_id} claim is ignored (owner answer
 * 13). A bound client whose token also carries groups is refused (SDV-AUT-005), as before. A token with neither a
 * binding nor groups on an intake address is an unknown sending system (SDV-AUT-011).
 *
 * <p><b>People.</b> Their entities and roles come from the group grants of the customer's ACTIVE login connection
 * ({@code idn_group_grant}, through platform-core's {@link EntityMembershipResolver}). The entity is the one the
 * request names in {@value #ENTITY_HEADER}, checked with {@link EntitySelection} (a choice is a request, never proof),
 * or the only one. With more than one and none named, or none activated yet but one the person may activate, the
 * principal is an {@link EntityChoicePrincipal}, which can only list entities or activate one (SDV-AUT-010 elsewhere).
 * A named entity that is not theirs, or not an entity ID, is refused with SDV-AUT-010. Roles that can never be held
 * together are withheld and reported ({@link RoleSeparationGuard}, task 0025), per entity.
 *
 * <p><b>Platform roles</b> (task 0057, LV-16 item 3). A platform role ({@code PLATFORM_OPERATIONS_ADMIN}) is never in an
 * entity's grants; setup's platform-level grants ({@code idn_platform_grant}, the role mapping's {@code *} rows) give it
 * for the whole customer. Through {@link PlatformGrants} the person holds it in every ACTIVE entity of the connection,
 * beside the roles their entity grants give there. The separation rules then run on all of them together, so a platform
 * role with Restricted Steward in an entity is withheld and reported like any other conflict.
 */
public final class OidcJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    /** The entity a person's request works in (owner answer 5). */
    public static final String ENTITY_HEADER = "X-SDV-Entity-Id";
    public static final String INTAKE_PREFIX = "/api/v1/ingestion/";
    public static final List<String> DEFAULT_CLIENT_ID_CLAIMS = List.of("azp", "client_id", "cid");
    /** The authority of a person who must choose (or activate) an entity first. */
    public static final String ENTITY_CHOICE_AUTHORITY = "SDV_ENTITY_CHOICE";

    /**
     * @param providerId     the login system's provider ID, as in the sign-in file (part of the connection key)
     * @param audience       Vero's API audience (part of the connection key)
     * @param groupsClaim    the claim with the person's groups
     * @param clientIdClaims the claims that may hold the client ID, first match wins; all present must agree
     */
    public record Settings(String providerId, String audience, String groupsClaim, List<String> clientIdClaims,
                           String entityClaim, String sourceIdClaim, String sourceKeyClaim) {
        public Settings {
            providerId = name(providerId, "provider id");
            audience = name(audience, "audience");
            groupsClaim = name(groupsClaim, "groups claim");
            entityClaim = name(entityClaim, "entity claim");
            sourceIdClaim = name(sourceIdClaim, "source-id claim");
            sourceKeyClaim = name(sourceKeyClaim, "source-key claim");
            if (clientIdClaims == null || clientIdClaims.isEmpty()) {
                throw new StartupFailure(SdvErrorCode.SYS_005, "The OIDC client-ID claims must be configured.");
            }
            clientIdClaims = clientIdClaims.stream().map(c -> name(c, "client-ID claim")).toList();
            Set<String> claims = new LinkedHashSet<>(List.of(groupsClaim, entityClaim, sourceIdClaim, sourceKeyClaim));
            if (claims.size() != 4 || clientIdClaims.stream().anyMatch(claims::contains)) {
                throw new StartupFailure(SdvErrorCode.SYS_005, "The OIDC claim names must be distinct.");
            }
        }

        private static String name(String value, String label) {
            if (value == null || value.isBlank() || value.length() > 100) {
                throw new StartupFailure(SdvErrorCode.SYS_005, "The OIDC " + label + " must be configured.");
            }
            return value.trim();
        }
    }

    /** The client bindings ({@code idn_machine_binding}). */
    public interface Senders {
        /** The client's ACTIVE binding for Vero, whatever the state of its entity; empty when it has none. */
        Optional<MachineBinding> activeBinding(String clientId);

        /** platform-core's {@code MachineEntityBindings}: the entities the client may deliver to under the connection. */
        Set<EntityId> admissible(ConnectionKey key, String clientId);
    }

    /** Vero's sending systems: the source ID of a source key in an entity. */
    public interface Sources {
        Optional<UUID> sourceId(UUID entityId, String sourceKey);
    }

    /** What the sign-in needs beyond the grants of ACTIVE entities. */
    public interface SignInLookups {
        /** Whether the customer has an ACTIVE login connection with this key (else SDV-AUT-006). */
        boolean connectionActive(ConnectionKey key);

        /** The slots (ENT-...) not activated yet where these groups give Business Admin. */
        List<String> activatableSlots(ConnectionKey key, Object groups);
    }

    /**
     * Task 0057: platform-level grants. For these groups under the connection: each ACTIVE entity with the platform roles
     * (canonical names) the groups hold there through platform-wide grants; empty when they hold none. Throws
     * platform-core's {@link IdentityAdmissionException} when a grant names a role that is not a platform role, and
     * {@link IllegalStateException} when the grants or the entities cannot be read.
     */
    public interface PlatformGrants {
        Map<EntityId, Set<String>> platformRoles(ConnectionKey key, Object groups);
    }

    /** The request being signed in: the named entity and whether it is an intake address. */
    public interface RequestView {
        String requestedEntity();

        boolean intake();

        /** The servlet request on this thread (Spring's request context), or nothing outside a request. */
        RequestView CURRENT = new RequestView() {
            @Override
            public String requestedEntity() {
                HttpServletRequest r = current();
                return r == null ? null : r.getHeader(ENTITY_HEADER);
            }

            @Override
            public boolean intake() {
                HttpServletRequest r = current();
                String path = r == null ? null : r.getRequestURI();
                String context = r == null ? null : r.getContextPath();
                if (path != null && context != null && !context.isEmpty() && path.startsWith(context)) {
                    path = path.substring(context.length());
                }
                return path != null && path.startsWith(INTAKE_PREFIX);
            }

            private static HttpServletRequest current() {
                return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
            }
        };
    }

    /** Left on the authentication as its details: what the activation address re-checks (no token, no secret). */
    public record VerifiedSignIn(ConnectionKey key, List<String> groups) {
        public VerifiedSignIn {
            groups = List.copyOf(groups);
        }
    }

    private final Settings settings;
    private final OidcJwtIdentityNormalizer identityNormalizer;
    private final EntityMembershipResolver people;
    private final PlatformGrants platformGrants;
    private final SignInLookups lookups;
    private final Senders senders;
    private final Sources sources;
    private final RequestView request;
    private final RoleSeparationGuard separationGuard;
    private final EntitySelection selection = new EntitySelection();
    private final MachineEntityAdmission machineAdmission = new MachineEntityAdmission();

    public OidcJwtAuthenticationConverter(Settings settings, OidcJwtIdentityNormalizer identityNormalizer,
                                          EntityMembershipResolver people, PlatformGrants platformGrants,
                                          SignInLookups lookups, Senders senders, Sources sources, RequestView request,
                                          RoleConflictReporter conflictReporter) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.identityNormalizer = Objects.requireNonNull(identityNormalizer, "identityNormalizer");
        this.people = Objects.requireNonNull(people, "people");
        this.platformGrants = Objects.requireNonNull(platformGrants, "platformGrants");
        this.lookups = Objects.requireNonNull(lookups, "lookups");
        this.senders = Objects.requireNonNull(senders, "senders");
        this.sources = Objects.requireNonNull(sources, "sources");
        this.request = Objects.requireNonNull(request, "request");
        // Task 0025: without a reporter a conflicted sign-in is refused (fail closed).
        this.separationGuard = new RoleSeparationGuard(conflictReporter);
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        try {
            String subject = requiredSubject(jwt);
            if (jwt.getIssuer() == null) throw claims("claim=iss is missing");
            URI issuer = URI.create(jwt.getIssuer().toString());
            ConnectionKey key = new ConnectionKey(settings.providerId(), issuer, settings.audience());
            Object groups = jwt.getClaim(settings.groupsClaim());
            boolean hasGroups = !IdentityClaims.values(groups).isEmpty();
            String clientId = clientId(jwt);

            Optional<MachineBinding> binding = clientId == null ? Optional.empty() : senders.activeBinding(clientId);
            if (binding.isPresent()) {
                if (hasGroups) {
                    throw claims("the client is bound as a sending system and claim=" + settings.groupsClaim()
                            + " is present; a token is a person or a sending system, not both");
                }
                return sendingSystem(jwt, subject, key, clientId, binding.get());
            }
            if (!hasGroups && request.intake()) {
                throw new SignInRefusedException(SdvErrorCode.AUT_011, SignInAnswer.NOT_A_SENDING_SYSTEM,
                        "no ACTIVE client binding for client=" + safe(clientId));
            }
            return person(jwt, subject, key, groups);
        } catch (OAuth2AuthenticationException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            // A claim could not be read; its message can hold a claim's value, so only the class is logged.
            throw claims("the identity claims could not be read (" + ex.getClass().getSimpleName() + ")");
        }
    }

    private AbstractAuthenticationToken sendingSystem(Jwt jwt, String subject, ConnectionKey key, String clientId,
                                                      MachineBinding binding) {
        Set<EntityId> admissible = senders.admissible(key, clientId);
        if (admissible == null) throw new IllegalStateException("The client bindings could not be read.");
        EntityId entity;
        try {
            entity = machineAdmission.requireUnique(admissible);
        } catch (IdentityAdmissionException notAdmitted) {
            throw new SignInRefusedException(SdvErrorCode.AUT_011, SignInAnswer.NOT_A_SENDING_SYSTEM,
                    "client=" + safe(clientId) + " is bound to slot " + binding.slotId()
                            + ", but that entity or the login connection for issuer host=" + key.issuer().getHost()
                            + " is not ACTIVE");
        }
        if (!entity.value().equals(binding.entityUuid())) {
            throw new SignInRefusedException(SdvErrorCode.AUT_011, SignInAnswer.NOT_A_SENDING_SYSTEM,
                    "client=" + safe(clientId) + " admitted to another entity than its binding");
        }
        UUID entityId = entity.value();
        String entityClaim = optionalStringClaim(jwt, settings.entityClaim(), 100);
        if (entityClaim != null && !entityId.equals(uuidOrNull(entityClaim))) {
            throw mismatch(clientId, settings.entityClaim());
        }
        UUID sourceId = sources.sourceId(entityId, binding.sourceKey()).orElseThrow(() ->
                new SignInRefusedException(SdvErrorCode.AUT_013, SignInAnswer.SOURCE_NOT_SET_UP,
                        "client=" + safe(clientId) + " is bound to source key " + binding.sourceKey()
                                + ", which entity " + entityId + " does not have"));
        String sourceClaim = optionalStringClaim(jwt, settings.sourceIdClaim(), 100);
        if (sourceClaim != null && !sourceId.equals(uuidOrNull(sourceClaim))) {
            throw mismatch(clientId, settings.sourceIdClaim());
        }
        String sourceKeyClaim = optionalStringClaim(jwt, settings.sourceKeyClaim(), 100);
        if (sourceKeyClaim != null && !sourceKeyClaim.equals(binding.sourceKey())) {
            throw mismatch(clientId, settings.sourceKeyClaim());
        }
        IngestionSourcePrincipal principal = new IngestionSourcePrincipal(entityId, sourceId, subject);
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("SDV_INGESTION_SOURCE")));
    }

    private AbstractAuthenticationToken person(Jwt jwt, String subject, ConnectionKey key, Object groups) {
        AuthenticatedIdentity identity = identityNormalizer.normalize(jwt);
        Map<EntityId, Set<String>> memberships;
        try {
            memberships = people.authorizedEntities(key.issuer(), groups);
        } catch (IdentityAdmissionException refused) {
            throw switch (refused.reason()) {
                case GROUPS_NOT_A_COLLECTION -> claims("claim=" + settings.groupsClaim() + " is not a list");
                case CONTEXT_UNAVAILABLE, POLICY_UNAVAILABLE ->
                        new IllegalStateException("The group grants could not be read (" + refused.reason() + ").");
                default -> withSubject(SignInAnswer.of(401, SdvErrorCode.AUT_007, SignInAnswer.NO_ROLE,
                        "the group grants refused this sign-in (" + refused.reason() + ")"), null, identity.subject());
            };
        }
        memberships = withPlatformRoles(memberships, key, groups, identity);
        VerifiedSignIn details = new VerifiedSignIn(key, List.copyOf(IdentityClaims.values(groups)));
        if (memberships.isEmpty()) {
            if (!lookups.connectionActive(key)) {
                // The issuer was already checked against Vero's own issuer setting, so its host is Vero's setting too.
                throw new SignInRefusedException(SdvErrorCode.AUT_006, SignInAnswer.NO_CONNECTION,
                        "no ACTIVE login connection for issuer host=" + key.issuer().getHost() + " provider="
                                + key.providerId() + " audience=" + key.audience());
            }
            List<String> activatable = lookups.activatableSlots(key, groups);
            if (activatable.isEmpty()) {
                // Task 0039: AUT-007 logs who was refused: the subject, never the token or the groups.
                throw withSubject(SignInAnswer.of(401, SdvErrorCode.AUT_007, SignInAnswer.NO_ROLE,
                        "no group grant gives a Vero role in an ACTIVE entity"), null, identity.subject());
            }
            return choice(identity, Set.of(), activatable, details);
        }
        String requested = request.requestedEntity();
        if ((requested == null || requested.isBlank()) && memberships.size() > 1) {
            return choice(identity, ids(memberships), lookups.activatableSlots(key, groups), details);
        }
        EntityId entity;
        try {
            entity = selection.select(requested, memberships);
        } catch (IdentityAdmissionException refused) {
            throw new SignInRefusedException(SdvErrorCode.AUT_010, SignInAnswer.CHOOSE_ENTITY,
                    "entity choice refused (" + refused.reason() + ")");
        }
        UUID entityId = entity.value();
        EnumSet<SdvRole> named = EnumSet.noneOf(SdvRole.class);
        for (String role : memberships.get(entity)) named.add(SdvRoleCatalog.role(role));
        // Task 0025, rule 1: roles that can never be held together are withheld and reported, in this entity.
        EnumSet<SdvRole> roles;
        try {
            roles = separationGuard.apply(entityId, identity.subject(), identity.displayName(),
                    RoleConflictReporter.Source.LOGIN_GROUPS, named);
        } catch (RoleSeparationGuard.RefusedException ex) {
            var answer = ex.answer();
            if (answer.code() == SdvErrorCode.AUT_007) throw withSubject(answer, entityId, identity.subject());
            throw new SignInRefusedException(answer.code(), answer.message(), "tenant=" + entityId + " " + answer.logDetail());
        }
        // Name and email are display-only and never influence roles or entity.
        SdvPrincipal principal = new SdvPrincipal(entityId, identity.subject(), roles, identity.displayName(),
                identity.email(), ids(memberships));
        List<SimpleGrantedAuthority> authorities = roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role.name()))
                .toList();
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        token.setDetails(details);
        return token;
    }

    /**
     * Task 0057: the entity grants with the platform roles added in each ACTIVE entity. Platform roles never come from
     * an entity grant (platform-core refuses that), so the two never overlap by accident; the separation rules run on
     * the union afterwards.
     */
    private Map<EntityId, Set<String>> withPlatformRoles(Map<EntityId, Set<String>> memberships, ConnectionKey key,
                                                         Object groups, AuthenticatedIdentity identity) {
        // No groups claim: no grant of any kind applies (the entity grants above read it the same way). A claim that is
        // there but not a list was already refused by the entity grants.
        if (groups == null) return memberships;
        Map<EntityId, Set<String>> platform;
        try {
            platform = platformGrants.platformRoles(key, groups);
        } catch (IdentityAdmissionException refused) {
            throw switch (refused.reason()) {
                case GROUPS_NOT_A_COLLECTION -> claims("claim=" + settings.groupsClaim() + " is not a list");
                case NO_ADMISSION_POLICY, CONTEXT_UNAVAILABLE, POLICY_UNAVAILABLE ->
                        new IllegalStateException("The platform grants could not be read (" + refused.reason() + ").");
                default -> withSubject(SignInAnswer.of(401, SdvErrorCode.AUT_007, SignInAnswer.NO_ROLE,
                        "the platform grants refused this sign-in (" + refused.reason() + ")"), null, identity.subject());
            };
        }
        if (platform == null) throw new IllegalStateException("The platform grants could not be read.");
        if (platform.isEmpty()) return memberships;
        Map<EntityId, Set<String>> all = new LinkedHashMap<>();
        memberships.forEach((entity, roles) -> all.put(entity, new LinkedHashSet<>(roles)));
        platform.forEach((entity, roles) -> all.computeIfAbsent(entity, e -> new LinkedHashSet<>()).addAll(roles));
        return all;
    }

    private static AbstractAuthenticationToken choice(AuthenticatedIdentity identity, Set<UUID> entities,
                                                      List<String> activatable, VerifiedSignIn details) {
        EntityChoicePrincipal principal = new EntityChoicePrincipal(identity.subject(), identity.displayName(),
                identity.email(), entities, activatable);
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority(ENTITY_CHOICE_AUTHORITY)));
        token.setDetails(details);
        return token;
    }

    private static Set<UUID> ids(Map<EntityId, Set<String>> memberships) {
        Set<UUID> out = new LinkedHashSet<>();
        memberships.keySet().forEach(e -> out.add(e.value()));
        return out;
    }

    /** The client ID from the first configured claim that holds one; every claim that holds one must agree. */
    private String clientId(Jwt jwt) {
        String found = null;
        for (String claim : settings.clientIdClaims()) {
            String value = optionalStringClaim(jwt, claim, 255);
            if (value == null) continue;
            if (found != null && !found.equals(value)) {
                throw claims("the client-ID claims " + settings.clientIdClaims() + " disagree");
            }
            found = value;
        }
        return found;
    }

    private static SignInRefusedException mismatch(String clientId, String claim) {
        return new SignInRefusedException(SdvErrorCode.AUT_012, SignInAnswer.CLAIM_NOT_BINDING,
                "claim=" + claim + " does not match the client binding of client=" + safe(clientId));
    }

    private static UUID uuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String requiredSubject(Jwt jwt) {
        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) throw claims("claim=sub is missing");
        if (subject.length() > 300) throw claims("claim=sub is longer than 300 characters");
        return subject.trim();
    }

    private static String optionalStringClaim(Jwt jwt, String name, int maxLength) {
        Object raw = jwt.getClaim(name);
        if (raw == null) return null;
        if (!(raw instanceof String text)) throw claims("claim=" + name + " is not a string");
        String value = text.trim();
        if (value.isEmpty()) return null;
        if (value.length() > maxLength) throw claims("claim=" + name + " is longer than " + maxLength + " characters");
        return value;
    }

    /** A client ID for the log: client IDs are not secrets; control characters are dropped and the length is cut. */
    static String safe(String value) {
        if (value == null) return "-";
        String v = value.codePoints().filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return v.length() > 255 ? v.substring(0, 255) : v;
    }

    /**
     * Task 0039 (SDV-AUT-007): the refusal's log line names the subject (the user or client ID), so IT knows who was
     * refused. Only the subject: never the token, the groups or another claim. Control characters are dropped.
     */
    static SignInRefusedException withSubject(SignInAnswer answer, UUID tenantId, String subject) {
        String who = subject == null ? "-" : subject.codePoints().filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        if (who.length() > 300) who = who.substring(0, 300);
        return new SignInRefusedException(answer.code(), answer.message(),
                "tenant=" + (tenantId == null ? "-" : tenantId) + " subject=" + who + " " + answer.logDetail());
    }

    /** Task 0038 (SDV-AUT-005): the log detail names the claim, never its value. */
    private static SignInRefusedException claims(String detail) {
        return new SignInRefusedException(SdvErrorCode.AUT_005, SignInAnswer.CLAIMS_NOT_USABLE, detail);
    }
}
