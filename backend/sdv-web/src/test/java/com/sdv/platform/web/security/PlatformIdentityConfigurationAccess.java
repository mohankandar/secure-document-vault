package com.sdv.platform.web.security;

import com.documentplatform.licensing.usage.UsageLedger;
import com.sdv.platform.security.auth.oidc.OidcJwtAuthenticationConverter;

import javax.sql.DataSource;

/** Test access to the identity wiring's package-private beans. */
public final class PlatformIdentityConfigurationAccess {
    public OidcJwtAuthenticationConverter.Senders senders(DataSource ds) {
        return new PlatformIdentityConfiguration().platformClientBindings(ds);
    }

    public static UsageLedger noUsage() {
        return PlatformIdentityConfiguration.NO_USAGE;
    }
}
