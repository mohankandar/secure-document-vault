package com.sdv.platform.web.admin;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.store.ActivationOutcome;
import com.documentplatform.identity.store.SlotActivation;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.EntityChoicePrincipal;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.oidc.OidcJwtAuthenticationConverter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Task 0041 (owner answer 11): the Business Admin activates an entity slot that setup loaded (minimal admin API; the
 * screen is a later UI task). {@code POST /api/v1/admin/entities/{slotId}/activate} with {@code {"name": "..."}}.
 * <p>
 * Who may: a person whose login groups give Business Admin on that slot (setup loads grants before activation, so the
 * first entity can be activated too: such a person signs in without an entity, {@link EntityChoicePrincipal}). With the
 * local test sign-in (local profile only), a Business Admin.
 * <p>
 * It calls platform-core's {@link SlotActivation} (the stored licence must list the slot and be in force; read only),
 * then makes Vero's row for the entity (same UUID, derived from the slot), and audits ENTITY_ACTIVATED in the new
 * entity. A refusal is 409 SDV-ENT-001 with platform-core's sentence (it names the slot, never the typed name). A slot
 * activated earlier whose Vero row is missing gets the row now, so a retry completes it.
 */
@RestController
@RequestMapping("/api/v1/admin/entities")
public class EntityActivationController {
    public static final String ACTION = "ENTITY_ACTIVATED";

    private final SlotActivation activation;
    private final TenantRepository tenants;
    private final AuditEventPort audit;
    private final OidcJwtAuthenticationConverter.SignInLookups lookups;
    private final boolean localSignIn;

    public EntityActivationController(SlotActivation activation, TenantRepository tenants, AuditEventPort audit,
                                      OidcJwtAuthenticationConverter.SignInLookups lookups,
                                      @Value("${sdv.auth.mode:disabled}") String mode) {
        this.activation = activation;
        this.tenants = tenants;
        this.audit = audit;
        this.lookups = lookups;
        this.localSignIn = "local-header".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    public record ActivateRequest(String name) { }

    public record Activated(UUID entityId, String slotId, String name, String result) { }

    @PostMapping("/{slotId}/activate")
    public Activated activate(@PathVariable String slotId, @RequestBody(required = false) ActivateRequest body) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        String subject;
        String displayName;
        if (principal instanceof EntityChoicePrincipal choice) {
            if (!choice.activatableSlots().contains(slotId)) throw notAllowed(slotId);
            subject = choice.subject();
            displayName = choice.displayName();
        } else if (principal instanceof SdvPrincipal person) {
            boolean allowed = localSignIn ? person.roles().contains(SdvRole.BUSINESS_ADMIN)
                    : authentication != null && authentication.getDetails() instanceof OidcJwtAuthenticationConverter.VerifiedSignIn signIn
                      && lookups.activatableSlots(signIn.key(), signIn.groups()).contains(slotId);
            if (!allowed) throw notAllowed(slotId);
            subject = person.subject();
            displayName = person.displayName();
        } else {
            throw new CodedRefusal.Unauthorized(SdvErrorCode.AUT_001, "Please sign in again.", "no sign-in");
        }
        String name = body == null ? null : body.name();
        ActivationOutcome outcome = activation.activate(slotId, name, subject);
        boolean repair = outcome.result() == ActivationOutcome.Result.ALREADY_ACTIVATED
                && EntityUuid.isSlotId(slotId) && tenants.findById(EntityUuid.fromSlotId(slotId)).isEmpty()
                && outcome.entity() != null;
        if (outcome.refused() && !repair) {
            throw new CodedRefusal.Conflict(SdvErrorCode.ENT_001, outcome.message(), "activation refused: " + outcome.result());
        }
        UUID entity = EntityUuid.fromSlotId(slotId);
        String stored = outcome.entity() != null && outcome.entity().displayName() != null
                ? outcome.entity().displayName() : SlotActivation.cleanName(name).orElse(slotId);
        tenants.createEntity(entity, slotId.toLowerCase(Locale.ROOT), stored);
        audit.append(new AuditEvent(UUID.randomUUID(), entity, "USER", subject, ACTION, "ENTITY", slotId, Instant.now(),
                MDC.get("correlationId"), AuditOutcome.SUCCESS,
                "{\"slotId\":\"" + slotId + "\",\"completedEarlierActivation\":" + repair + "}", displayName));
        return new Activated(entity, slotId, stored, repair ? "COMPLETED" : outcome.result().name());
    }

    private static CodedRefusal.Forbidden notAllowed(String slotId) {
        String slot = EntityUuid.isSlotId(slotId) ? slotId : "this slot";
        return new CodedRefusal.Forbidden(SdvErrorCode.ENT_002, "Only a Business Admin of " + slot
                + " can activate it.", "no Business Admin grant on " + slot);
    }

}
