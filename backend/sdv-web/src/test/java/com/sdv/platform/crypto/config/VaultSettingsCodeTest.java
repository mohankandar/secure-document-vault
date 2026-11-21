package com.sdv.platform.crypto.config;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0037, item 5: a key service setting that isn't valid stops the start with SDV-SYS-007, never with the token. */
class VaultSettingsCodeTest {
    private static StartupFailure refused(MockEnvironment env) {
        return assertThrows(StartupFailure.class, () -> new VaultCryptoConfiguration().vaultTransitKeyProtectionProvider(env));
    }

    @Test
    void eachVaultSettingProblemIsSys007() {
        assertEquals(SdvErrorCode.SYS_007, refused(new MockEnvironment()).errorCode(), "no address");
        MockEnvironment both = new MockEnvironment().withProperty("sdv.crypto.vault.base-url", "http://127.0.0.1:8200")
                .withProperty("sdv.crypto.vault.token", "not-a-real-token").withProperty("sdv.crypto.vault.token-file", "x");
        StartupFailure f = refused(both);
        assertEquals(SdvErrorCode.SYS_007, f.errorCode(), "a token and a token file");
        assertFalse(f.getMessage().contains("not-a-real-token"), "never the token");
        MockEnvironment badMount = new MockEnvironment().withProperty("sdv.crypto.vault.base-url", "http://127.0.0.1:8200")
                .withProperty("sdv.crypto.vault.token", "not-a-real-token").withProperty("sdv.crypto.vault.mount", "../sys");
        StartupFailure mount = refused(badMount);
        assertEquals(SdvErrorCode.SYS_007, mount.errorCode(), "the provider's own check");
        assertEquals("Vault Transit mount name is invalid.", mount.detail());
        MockEnvironment missingFile = new MockEnvironment().withProperty("sdv.crypto.vault.base-url", "http://127.0.0.1:8200")
                .withProperty("sdv.crypto.vault.token-file", "C:/no/such/vero-token-file");
        assertEquals(SdvErrorCode.SYS_007, refused(missingFile).errorCode(), "a token file that can't be read");
    }
}
