package com.sdv.platform.web.security;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.security.auth.EntityChoicePrincipal;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.security.auth.SignInAnswer;
import com.sdv.platform.security.auth.oidc.OidcJwtAuthenticationConverter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0041 (owner answer 5): the entity switcher.
 * <ul>
 *   <li>{@code GET /api/v1/session/entities}: the entities this sign-in may work in, the current one (when chosen), and
 *       the slots the person may activate. Also for a person who has not chosen yet ({@link EntityChoicePrincipal}).
 *       With the local test sign-in (local profile only), every ACTIVE entity.</li>
 *   <li>{@code POST /api/v1/session/entity-switch}: the browser says it switched to the request's entity (header
 *       {@code X-SDV-Entity-Id}, or the local sign-in's entity) from {@code from}. Audited in the new entity as
 *       ENTITY_SWITCHED. Every switch is audited; the last entity is kept in the browser.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/session")
public class SessionEntitiesController {
    public static final String SWITCH_ACTION = "ENTITY_SWITCHED";

    private final TenantRepository tenants;
    private final AuditEventPort audit;
    private final SecurityContextFacade security;
    private final OidcJwtAuthenticationConverter.SignInLookups lookups;
    private final boolean localSignIn;

    public SessionEntitiesController(TenantRepository tenants, AuditEventPort audit, SecurityContextFacade security,
                                     OidcJwtAuthenticationConverter.SignInLookups lookups,
                                     @Value("${sdv.auth.mode:disabled}") String mode) {
        this.tenants = tenants;
        this.audit = audit;
        this.security = security;
        this.lookups = lookups;
        this.localSignIn = "local-header".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    public record EntityOption(UUID entityId, String name) { }

    /** {@code current} is null while the person has not chosen. {@code activatableSlots}: slot IDs (ENT-...). */
    public record EntitiesResponse(UUID current, List<EntityOption> entities, List<String> activatableSlots) {
        public EntitiesResponse {
            entities = List.copyOf(entities);
            activatableSlots = List.copyOf(activatableSlots);
        }
    }

    @GetMapping("/entities")
    public EntitiesResponse entities() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof EntityChoicePrincipal choice) {
            return new EntitiesResponse(null, options(choice.entities()), choice.activatableSlots());
        }
        SdvPrincipal person = security.requirePrincipal();
        if (localSignIn) {
            List<EntityOption> all = tenants.findActive().stream()
                    .map(t -> new EntityOption(t.tenantId(), name(t))).toList();
            return new EntitiesResponse(person.tenantId(), all, List.of());
        }
        List<String> activatable = authentication != null
                && authentication.getDetails() instanceof OidcJwtAuthenticationConverter.VerifiedSignIn signIn
                ? lookups.activatableSlots(signIn.key(), signIn.groups()) : List.of();
        return new EntitiesResponse(person.tenantId(), options(person.entities()), activatable);
    }

    public record SwitchRequest(UUID from) { }

    @PostMapping("/entity-switch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void switched(@RequestBody(required = false) SwitchRequest body) {
        SdvPrincipal person = security.requirePrincipal();
        UUID from = body == null ? null : body.from();
        if (from != null && !localSignIn && !person.entities().contains(from)) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.AUT_010, SignInAnswer.CHOOSE_ENTITY,
                    "switch from an entity that is not the sign-in's");
        }
        if (from != null && localSignIn && tenants.findById(from).isEmpty()) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.AUT_010, SignInAnswer.CHOOSE_ENTITY, "switch from an unknown entity");
        }
        String details = "{\"from\":" + (from == null ? "null" : "\"" + from + "\"") + "}";
        audit.append(new AuditEvent(UUID.randomUUID(), person.tenantId(), "USER", person.subject(), SWITCH_ACTION,
                "ENTITY", person.tenantId().toString(), Instant.now(), MDC.get("correlationId"), AuditOutcome.SUCCESS,
                details, person.displayName()));
    }

    private List<EntityOption> options(Set<UUID> ids) {
        List<EntityOption> out = new ArrayList<>();
        for (UUID id : ids) {
            tenants.findById(id).ifPresent(t -> out.add(new EntityOption(id, name(t))));
        }
        out.sort(Comparator.comparing(EntityOption::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private static String name(Tenant t) {
        return t.displayName() == null || t.displayName().isBlank() ? t.tenantKey() : t.displayName();
    }
}
