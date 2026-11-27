package com.sdv.platform.web.notifications;

import com.documentplatform.crypto.vault.VaultClient;
import com.documentplatform.identity.store.EntityStore;
import com.documentplatform.identity.store.EntityTimeZone;
import com.documentplatform.identity.store.EntityTimeZones;
import com.documentplatform.licensing.spring.notifications.ActivatedSlots;
import com.documentplatform.notifications.EntityZones;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.RecipientResolver;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.notifications.spring.NotificationsProperties;
import com.sdv.platform.application.port.NotificationPort;
import com.sdv.platform.application.port.NotificationRecipientRepository;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0081: Vero's parts of platform-core's notifications ({@code platform.notifications.*}, install guide): the event
 * catalogue, who holds a role (the admins' lists), the secrets (the key service's KV store), the zones (identity), the
 * activated entities (licensing's notices). {@code platform.notifications.enabled=false} switches the module off; Vero
 * then sends nothing ({@link NotificationPort#NONE}) and the settings screens say so (SDV-NTF-007).
 */
@Configuration
public class VeroNotificationsConfiguration {

    @Bean
    EventCatalogue veroEventCatalogue() {
        return VeroCatalogue.build();
    }

    @Bean
    RecipientResolver veroRecipients(NotificationRecipientRepository lists,
                                      com.sdv.platform.application.port.RoleHolderRepository holders) {
        return new ListRecipients(lists, holders);
    }

    @Bean
    SecretResolver veroSecrets(ObjectProvider<VaultClient> vault) {
        return new VaultSecrets(vault::getIfAvailable);
    }

    /** Identity's zones: the one source (the module makes the customer's zones from this bean itself). */
    @Bean
    EntityTimeZones entityTimeZones(EntityStore entities) {
        return new EntityTimeZones(entities);
    }

    /** An entity's own zone; an entity without one follows the customer's (from identity, too). */
    @Bean
    EntityZones veroEntityZones(EntityTimeZones zones) {
        return (tenant, entity) -> {
            UUID id;
            try {
                id = UUID.fromString(entity.value());
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
            return zones.zone(id).flatMap(EntityTimeZone::ownZone);
        };
    }

    /** Licensing's business notices go to each ACTIVE entity: slot ID to Vero's entity ID. */
    @Bean
    ActivatedSlots veroActivatedSlots(PlatformIdentityReads identity) {
        return tenantId -> {
            Map<String, String> out = new LinkedHashMap<>();
            identity.activeSlots(tenantId).forEach((slot, entity) -> out.put(slot, entity.toString()));
            return out;
        };
    }

    @Bean
    NotificationPort notificationPort(ObjectProvider<Notifier> notifier, ObjectProvider<NotificationSettings> settings,
                                      PlatformIdentityReads identity,
                                      @Value("${platform.licensing.tenant-id:}") String installCustomer) {
        Notifier n = notifier.getIfAvailable();
        NotificationSettings s = settings.getIfAvailable();
        if (n == null || s == null) return NotificationPort.NONE;
        return new VeroNotifications(n, s, entity -> identity.slotOf(entity).map(PlatformIdentityReads.EntitySlot::tenantId),
                installCustomer);
    }

    // ---- Task 0082: approvals from messages (switched on by platform.notifications.approvals.link-key-secret)

    /** The kinds decided on the link page; each link lives as long as a request waits, at most the module's 30 days. */
    @Bean
    com.documentplatform.notifications.approval.ApprovalType veroPrintApproval(@Value("${sdv.approvals.pending-days:7}") int pendingDays) {
        return approvalType(VeroApprovalLinks.TYPES.get(com.sdv.platform.application.approvals.AccessRequestKind.PRINT), pendingDays);
    }

    @Bean
    com.documentplatform.notifications.approval.ApprovalType veroDownloadApproval(@Value("${sdv.approvals.pending-days:7}") int pendingDays) {
        return approvalType(VeroApprovalLinks.TYPES.get(com.sdv.platform.application.approvals.AccessRequestKind.DOWNLOAD), pendingDays);
    }

    @Bean
    com.documentplatform.notifications.approval.ApprovalType veroLowerLevelApproval(@Value("${sdv.approvals.pending-days:7}") int pendingDays) {
        return approvalType(VeroApprovalLinks.TYPES.get(com.sdv.platform.application.approvals.AccessRequestKind.LOWER_LEVEL), pendingDays);
    }

    static com.documentplatform.notifications.approval.ApprovalType approvalType(String id, int pendingDays) {
        return new com.documentplatform.notifications.approval.ApprovalType(id,
                com.documentplatform.notifications.EventTypeId.of(com.sdv.platform.application.notifications.VeroEvent.APPROVAL_WAITING.id()),
                com.documentplatform.notifications.EventTypeId.of(com.sdv.platform.application.notifications.VeroEvent.REQUEST_APPROVED.id()),
                com.documentplatform.notifications.EventTypeId.of(com.sdv.platform.application.notifications.VeroEvent.REQUEST_DECLINED.id()),
                VeroCatalogue.BUSINESS_ADMIN, java.time.Duration.ofDays(Math.max(1, Math.min(30, pendingDays))));
    }

    /**
     * Task 0100 (VSEC-201): who may open and decide a link is the signed-in person's role now, never the mailing list
     * (without this bean platform-core would take the list as the approver role).
     */
    @Bean
    com.documentplatform.notifications.approval.ApproverCheck veroApprovers(
            com.sdv.platform.security.auth.CapabilityPolicyService capabilities, PlatformIdentityReads identity) {
        return new SignedInApprover(VeroNotificationsConfiguration::signedInPerson, capabilities,
                entity -> identity.slotOf(entity).map(PlatformIdentityReads.EntitySlot::tenantId));
    }

    private static Optional<com.sdv.platform.security.auth.SdvPrincipal> signedInPerson() {
        var a = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.isAuthenticated() && a.getPrincipal() instanceof com.sdv.platform.security.auth.SdvPrincipal p
                ? Optional.of(p) : Optional.empty();
    }

    /** Every decision and refusal of a link, in Vero's audit: request, type, who, outcome, code; never a token or the reason. */
    @Bean
    com.documentplatform.notifications.approval.ApprovalAudit veroApprovalAudit(com.sdv.platform.application.port.AuditEventPort audit) {
        return new LinkAudit(audit);
    }

    @Bean
    com.sdv.platform.application.port.ApprovalLinkPort approvalLinkPort(
            ObjectProvider<com.documentplatform.notifications.approval.Approvals> approvals,
            com.sdv.platform.application.port.AccessRequestRepository requests, NotificationRecipientRepository lists,
            PlatformIdentityReads identity, NotificationPort notifications) {
        var a = approvals.getIfAvailable();
        if (a == null) return com.sdv.platform.application.port.ApprovalLinkPort.NONE;
        return new VeroApprovalLinks(a, requests, lists, entity -> identity.slotOf(entity).map(PlatformIdentityReads.EntitySlot::tenantId),
                notifications);
    }

    /** Task 0100 (VSEC-204): the app address of every message, from the install configuration only. */
    @Bean
    PublicUrl veroPublicUrl(@Value("${sdv.public-url:}") String value, org.springframework.core.env.Environment environment) {
        return PublicUrl.of(value, environment.acceptsProfiles(org.springframework.core.env.Profiles.of("local")));
    }

    /**
     * Task 0100 (VSEC-204): at start, a customer connected under another address (typed on the old screen) is moved to
     * the install's, so no message links elsewhere.
     */
    @Bean
    org.springframework.boot.ApplicationRunner veroAlignAppAddress(NotificationScreens screens,
                                                                  @Value("${platform.licensing.tenant-id:}") String installCustomer) {
        return args -> {
            if (installCustomer == null || installCustomer.isBlank()) return;
            screens.get().ifPresent(s -> {
                if (s.align(com.documentplatform.notifications.TenantId.of(installCustomer.trim()))) {
                    org.slf4j.LoggerFactory.getLogger(VeroNotificationsConfiguration.class)
                            .warn("The app address of messages was moved to the install's sdv.public-url.");
                }
            });
        };
    }

    /** Null when notifications are off: the screens answer SDV-NTF-007 then. */
    @Bean
    NotificationScreens notificationScreens(ObjectProvider<NotificationSettings> settings,
                                            ObjectProvider<NotificationsProperties> config, PublicUrl publicUrl,
                                            org.springframework.context.ApplicationContext context) {
        NotificationSettings s = settings.getIfAvailable();
        NotificationsProperties c = config.getIfAvailable();
        if (s == null || c == null) return new NotificationScreens(null);
        return new NotificationScreens(new NotificationSettingsService(s, c, () -> destinations(context), publicUrl));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> destinations(org.springframework.context.ApplicationContext context) {
        if (!context.containsBean("notificationsHealthIndicator")) return Map.of();
        Object bean = context.getBean("notificationsHealthIndicator");
        if (!(bean instanceof org.springframework.boot.health.contributor.HealthIndicator h)) return Map.of();
        var health = h.health();
        Object d = health == null ? null : health.getDetails().get("destinations");
        return d instanceof Map<?, ?> m ? (Map<String, Map<String, Object>>) m : Map.of();
    }

    /** The settings screens' service, or none when notifications are off. */
    public record NotificationScreens(NotificationSettingsService service) {
        public Optional<NotificationSettingsService> get() {
            return Optional.ofNullable(service);
        }
    }
}
