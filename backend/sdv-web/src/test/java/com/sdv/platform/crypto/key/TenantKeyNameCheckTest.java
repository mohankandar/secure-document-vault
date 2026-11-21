package com.sdv.platform.crypto.key;

import com.sdv.platform.domain.error.InternalCheckFailed;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Task 0037, item 6: a key name without a tenant is an internal check (SDV-SYS-016). */
class TenantKeyNameCheckTest {
    @Test
    void aKeyWithoutATenantIsInternal() {
        VaultTransitKeyProtectionProvider vault = new VaultTransitKeyProtectionProvider(URI.create("http://127.0.0.1:1"),
                "not-a-real-token", "transit", "sdv-tenant-");
        assertThrows(InternalCheckFailed.class, () -> vault.tenantKeyName(null));
    }
}
