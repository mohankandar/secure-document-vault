package com.sdv.platform.security.auth.enterprise;

import com.sdv.platform.application.roles.RoleMappingWatchService;
import com.sdv.platform.security.SdvRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Task 0025, rule 3: at every start with enterprise sign-in, compares the login groups that give Restricted Steward
 * ({@value EnterpriseJwtConfiguration#RESTRICTED_STEWARD_GROUPS_PROPERTY}) with those recorded last time. A change is
 * audited and noticed for each tenant's Business Admin. If the check fails, the server doesn't start (fail closed).
 */
@Component
@ConditionalOnProperty(prefix = "sdv.auth", name = "mode", havingValue = "enterprise-jwt")
public class RestrictedStewardMappingWatcher implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(RestrictedStewardMappingWatcher.class);

    private final RoleMappingWatchService watch;
    private final String configuredGroups;

    public RestrictedStewardMappingWatcher(
            RoleMappingWatchService watch,
            @Value("${" + EnterpriseJwtConfiguration.RESTRICTED_STEWARD_GROUPS_PROPERTY + ":}") String configuredGroups) {
        this.watch = watch;
        this.configuredGroups = configuredGroups;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean changed = watch.check(SdvRole.RESTRICTED_STEWARD.name(), SdvGroupRoleMapper.split(configuredGroups));
        if (changed) log.info("The login groups for RESTRICTED_STEWARD changed; the change is audited for every tenant.");
    }
}
