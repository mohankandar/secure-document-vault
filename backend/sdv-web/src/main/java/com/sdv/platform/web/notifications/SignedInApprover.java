package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.TenantId;
import com.documentplatform.notifications.approval.ApproverCheck;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Task 0100 (VSEC-201): who may decide a link's request is the person's role in identity now, never a mailing list.
 * platform-core asks this when a link is opened or decided, on the request of the signed-in person: they hold the
 * approver role only when they are that person, signed in to that entity of that customer, and their roles there (from
 * the group grants of this sign-in, with the role permissions) give {@link Capability#APPROVE_SENSITIVE_ACCESS}.
 * Anything else, or anything that can't be read, is "no" (fail closed).
 */
public final class SignedInApprover implements ApproverCheck {
    private final Supplier<Optional<SdvPrincipal>> signedIn;
    private final CapabilityPolicyService capabilities;
    private final Function<UUID, Optional<String>> customerOf;

    public SignedInApprover(Supplier<Optional<SdvPrincipal>> signedIn, CapabilityPolicyService capabilities,
                            Function<UUID, Optional<String>> customerOf) {
        this.signedIn = Objects.requireNonNull(signedIn, "signedIn");
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.customerOf = Objects.requireNonNull(customerOf, "customerOf");
    }

    @Override
    public boolean holdsRole(TenantId tenant, EntityId entity, RoleId role, String userId) {
        if (tenant == null || entity == null || role == null || userId == null) return false;
        if (!VeroCatalogue.BUSINESS_ADMIN.equals(role)) return false;
        SdvPrincipal p = signedIn.get().orElse(null);
        if (p == null || !userId.equals(p.subject())) return false;
        if (!entity.value().equals(p.tenantId().toString())) return false;
        if (!customerOf.apply(p.tenantId()).map(tenant.value()::equals).orElse(false)) return false;
        return capabilities.has(p, Capability.APPROVE_SENSITIVE_ACCESS);
    }
}
