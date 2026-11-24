package com.sdv.platform.web.security;

import com.documentplatform.licensing.LicenceBuild;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;

import java.util.Optional;

/**
 * Task 0067 (platform-core 0.7.0): the licence keys Vero's own build trusts, from inside its jar. A build without them
 * (Vero has no release keys yet) trusts no licence: activation is refused, Vero still starts. Files that are there but
 * wrong, or there twice, stop Vero.
 */
public final class VeroLicenceBuild {
    private VeroLicenceBuild() { }

    public static Optional<LicenceBuild> load() {
        ClassLoader loader = LicenceBuild.class.getClassLoader();
        if (loader.getResource(LicenceBuild.KEYS) == null && loader.getResource(LicenceBuild.BUILD) == null) return Optional.empty();
        try {
            return Optional.of(LicenceBuild.load());
        } catch (RuntimeException e) {
            throw new StartupFailure(SdvErrorCode.SYS_005, "The licence keys of this Vero build can't be used: " + e.getMessage());
        }
    }
}
