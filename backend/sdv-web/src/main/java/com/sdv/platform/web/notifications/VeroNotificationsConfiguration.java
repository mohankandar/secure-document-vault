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
    RecipientResolver veroRecipients(NotificationRecipientRepository lists) {
        return new ListRecipients(lists);
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

    /** Null when notifications are off: the screens answer SDV-NTF-007 then. */
    @Bean
    NotificationScreens notificationScreens(ObjectProvider<NotificationSettings> settings,
                                            ObjectProvider<NotificationsProperties> config,
                                            org.springframework.context.ApplicationContext context) {
        NotificationSettings s = settings.getIfAvailable();
        NotificationsProperties c = config.getIfAvailable();
        if (s == null || c == null) return new NotificationScreens(null);
        return new NotificationScreens(new NotificationSettingsService(s, c, () -> destinations(context)));
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
