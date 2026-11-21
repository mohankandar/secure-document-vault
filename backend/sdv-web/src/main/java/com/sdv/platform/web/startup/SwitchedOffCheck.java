package com.sdv.platform.web.startup;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Task 0037: sign-in (SDV_AUTH_MODE) and the key service (SDV_KEY_PROVIDER) default to "disabled", so a forgotten
 * setting used to start Vero silently, and then every sign-in or every document action failed (SDV-SYS-006,
 * SDV-KEY-001).
 * <ul>
 *   <li>Local start (the "local" profile): one WARN line each, naming what will fail.</li>
 *   <li>Packaged start (any start without the local profile, as a customer install runs): Vero refuses to start with
 *   either switched off. One ERROR line each, then the start stops before the web server takes requests.</li>
 * </ul>
 * It runs while the application context is built, so a refused start never opens its port.
 */
@Component
public class SwitchedOffCheck {
    private static final Logger log = LoggerFactory.getLogger(SwitchedOffCheck.class);

    static final String AUTH_OFF = "sdv.auth.mode (SDV_AUTH_MODE) is \"disabled\": nobody can sign in, so every request "
            + "except health and system information is refused (401).";
    static final String KEY_OFF = "sdv.crypto.key-provider (SDV_KEY_PROVIDER) is \"disabled\": uploading, finishing, "
            + "viewing, printing, downloading and redacting documents will fail.";

    @Autowired
    public SwitchedOffCheck(Environment environment) {
        this(environment.acceptsProfiles(Profiles.of("local")),
                environment.getProperty("sdv.auth.mode", "disabled"),
                environment.getProperty("sdv.crypto.key-provider", "disabled"));
    }

    SwitchedOffCheck(boolean local, String authMode, String keyProvider) {
        List<StartupFailure> off = new ArrayList<>();
        if (disabled(authMode)) off.add(new StartupFailure(SdvErrorCode.SYS_006, AUTH_OFF));
        if (disabled(keyProvider)) off.add(new StartupFailure(SdvErrorCode.KEY_001, KEY_OFF));
        for (StartupFailure f : off) {
            if (local) {
                log.warn("{} {} {} Allowed only in a local start.", f.errorCode().code(), f.sentence(), f.detail());
            } else {
                log.error("{} {} {} A packaged start refuses this. {}", f.errorCode().code(), f.sentence(), f.detail(), f.action());
            }
        }
        if (!local && !off.isEmpty()) throw off.get(0);
    }

    private static boolean disabled(String value) {
        return value == null || value.isBlank() || "disabled".equals(value.trim().toLowerCase(Locale.ROOT));
    }
}
