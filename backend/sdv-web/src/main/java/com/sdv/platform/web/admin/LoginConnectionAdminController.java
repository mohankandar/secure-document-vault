package com.sdv.platform.web.admin;

import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.LoginConnectionStore;
import com.sdv.platform.domain.identity.VeroProduct;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Task 0041 (owner answer 6): the IT Admin reads the login connection in use, read only. Setup loads and changes it
 * ("Sign-in is set during installation by your installer"); Vero offers no draft, test sign-in or activation until
 * platform-core moves the test sign-in fetch into identity. It holds no secret (a secret reference at most, not shown).
 */
@RestController
@RequestMapping("/api/v1/admin/login-connection")
public class LoginConnectionAdminController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final PlatformIdentityReads reads;
    private final LoginConnectionStore connections;

    public LoginConnectionAdminController(SecurityContextFacade security, AuthorizationService authorization,
                                          PlatformIdentityReads reads, LoginConnectionStore connections) {
        this.security = security;
        this.authorization = authorization;
        this.reads = reads;
        this.connections = connections;
    }

    public record ConnectionView(UUID connectionId, String providerId, String providerType, String issuerUri,
                                 String browserClientId, String apiAudience, String status, long configurationVersion) {
        static ConnectionView of(LoginConnection c) {
            return new ConnectionView(c.connectionId(), c.providerId(), c.providerType().name(), c.issuer().toString(),
                    c.browserClientId(), c.apiAudience(), c.status().name(), c.configurationVersion());
        }
    }

    /** {@code active} is null when the customer has no ACTIVE connection for Vero (or the entity is not set up). */
    public record Overview(ConnectionView active, boolean changedBySetupOnly) { }

    @GetMapping
    public Overview overview() {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, Capability.CONFIGURE_TENANT_AUTH);
        ConnectionView active = reads.slotOf(principal.tenantId())
                .flatMap(slot -> connections.active(slot.tenantId(), VeroProduct.CODE))
                .map(ConnectionView::of)
                .orElse(null);
        return new Overview(active, true);
    }
}
