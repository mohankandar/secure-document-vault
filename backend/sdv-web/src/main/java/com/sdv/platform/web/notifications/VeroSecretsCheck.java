package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.notifications.spring.NotificationsProperties;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Task 0102 (VSEC-205, answer Q-6): every notification secret the install config names must be at Vero's own place in
 * the key service ({@code secret/platform/vero/<path>}). One that isn't stops the start (SDV-SYS-007), naming the setting,
 * never its path or a value, so a secret left at an old place is found at once, not at the first message. When the key
 * service can't be asked at start, the start goes on with a warning (the sends then fail and the health entry says so).
 * Notifications switched off: nothing to check.
 */
@Component
@Order(1)
public class VeroSecretsCheck implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(VeroSecretsCheck.class);

    private final ObjectProvider<NotificationsProperties> config;
    private final ObjectProvider<SecretResolver> secrets;

    public VeroSecretsCheck(ObjectProvider<NotificationsProperties> config, ObjectProvider<SecretResolver> secrets) {
        this.config = config;
        this.secrets = secrets;
    }

    @Override
    public void run(ApplicationArguments args) {
        NotificationsProperties c = config.getIfAvailable();
        SecretResolver r = secrets.getIfAvailable();
        if (c == null || r == null) return;
        check(named(c), r);
    }

    /** The setting's name and the secret's path, for every secret the config names. */
    static Map<String, String> named(NotificationsProperties c) {
        Map<String, String> out = new LinkedHashMap<>();
        put(out, "platform.notifications.mail.password-secret", c.getMail().getPasswordSecret());
        if (c.getMail().getOauth() != null) put(out, "platform.notifications.mail.oauth.credential-secret", c.getMail().getOauth().getCredentialSecret());
        new TreeMap<>(c.getMailServers()).forEach((id, m) -> {
            put(out, "platform.notifications.mail-servers." + id + ".password-secret", m.getPasswordSecret());
            if (m.getOauth() != null) put(out, "platform.notifications.mail-servers." + id + ".oauth.credential-secret", m.getOauth().getCredentialSecret());
        });
        new TreeMap<>(c.getTeams()).forEach((id, path) -> put(out, "platform.notifications.teams." + id, path));
        new TreeMap<>(c.getSlack()).forEach((id, path) -> put(out, "platform.notifications.slack." + id, path));
        put(out, "platform.notifications.approvals.link-key-secret", c.getApprovals().getLinkKeySecret());
        return out;
    }

    private static void put(Map<String, String> out, String setting, String path) {
        if (path != null && !path.isBlank()) out.put(setting, path.trim());
    }

    static void check(Map<String, String> named, SecretResolver r) {
        for (Map.Entry<String, String> e : named.entrySet()) {
            boolean there;
            try {
                there = r.resolve(SecretRef.of(e.getValue())).isPresent();
            } catch (IllegalArgumentException notAPath) {
                throw new StartupFailure(SdvErrorCode.SYS_007, e.getKey() + " is not a path in the key service.");
            } catch (RuntimeException away) {
                log.warn("The notification secrets could not be checked at start: the key service did not answer. Messages that need them fail until it does.");
                return;
            }
            if (!there) {
                throw new StartupFailure(SdvErrorCode.SYS_007, e.getKey() + " names a secret that isn't at Vero's place in the key service"
                        + " (secret/platform/vero/<path>, key \"value\"; runbook, \"Notifications\").");
            }
        }
    }
}
