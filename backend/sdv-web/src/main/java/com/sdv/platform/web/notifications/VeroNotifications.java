package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.ConditionKey;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.EventSettings;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.NotificationEvent;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.RecordIdentifier;
import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.TenantId;
import com.documentplatform.notifications.TenantSettings;
import com.sdv.platform.application.notifications.VeroEvent;
import com.sdv.platform.application.notifications.VeroNotice;
import com.sdv.platform.application.port.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Task 0081: Vero's {@link NotificationPort} over platform-core's {@link Notifier}. Nothing here waits on a channel
 * (the notifier only saves to its outbox) or throws: a notification never stops or undoes the work it reports. Inside a
 * transaction it is published after the commit, so a refused or rolled-back change tells nobody.
 * <p>
 * <b>Content (answer 4 of plan 0069).</b> A message carries the event, counts, times and a link, and an e-mail the
 * record's primary identifier only when {@link VeroNotice#mayNameRecord()} (never for a Highly Confidential record, nor
 * an unknown level) and the identifier has the shape the module accepts (else it is left out). Teams and Slack never
 * get the identifier: they get the event's twin type ({@link VeroEvent#channelId()}), published only to the channels
 * the admin picked for it. Should the e-mail type ever name a Teams or Slack destination, its message goes without
 * the identifier (fail closed). The log line of a failure names the event and the exception's class only.
 */
public class VeroNotifications implements NotificationPort {
    private static final Logger log = LoggerFactory.getLogger(VeroNotifications.class);

    private final Notifier notifier;
    private final NotificationSettings settings;
    private final Function<UUID, Optional<String>> customerOf;
    private final String installCustomer;

    /**
     * @param customerOf      an entity's customer (idn_entity.tenant_id)
     * @param installCustomer the install's customer ({@code platform.licensing.tenant-id}), for events without an entity
     */
    public VeroNotifications(Notifier notifier, NotificationSettings settings, Function<UUID, Optional<String>> customerOf,
                             String installCustomer) {
        this.notifier = notifier;
        this.settings = settings;
        this.customerOf = customerOf;
        this.installCustomer = installCustomer == null || installCustomer.isBlank() ? null : installCustomer.trim();
    }

    @Override
    public void publish(VeroNotice notice) {
        afterCommit(notice.event(), () -> send(notice));
    }

    @Override
    public void failing(VeroNotice notice) {
        afterCommit(notice.event(), () -> {
            if (!notice.event().continuous()) throw new IllegalArgumentException("not a condition");
            tenant(notice.entityId()).ifPresent(t -> notifier.conditionFailing(event(notice, notice.event().id(), t, false)));
        });
    }

    @Override
    public void cleared(VeroEvent event, UUID entityId) {
        afterCommit(event, () -> tenant(entityId).ifPresent(t -> notifier.conditionCleared(
                new ConditionKey(EventTypeId.of(event.id()), t, entityId == null ? null : EntityId.of(entityId.toString()), null))));
    }

    private void send(VeroNotice notice) {
        VeroEvent event = notice.event();
        Optional<TenantId> tenant = tenant(notice.entityId());
        if (tenant.isEmpty()) return;
        TenantId t = tenant.get();
        if (event.audience() == VeroEvent.Audience.REQUESTER) {
            notifier.publishToPerson(event(notice, event.id(), t, notice.mayNameRecord()), notice.requester());
            return;
        }
        boolean emailOnly = onlyEmail(t, notice.entityId(), event.id());
        notifier.publish(event(notice, event.id(), t, emailOnly && notice.mayNameRecord()));
        Optional<String> twin = event.channelId();
        if (twin.isPresent() && !destinations(t, notice.entityId(), twin.get()).isEmpty()) {
            notifier.publish(event(notice, twin.get(), t, false));
        }
    }

    /** Whether every destination the e-mail type goes to is e-mail (none chosen means the tenant's e-mail). */
    private boolean onlyEmail(TenantId t, UUID entityId, String typeId) {
        Set<DestinationId> chosen = destinations(t, entityId, typeId);
        if (chosen.isEmpty()) return true;
        Optional<TenantSettings> tenant = settings.tenant(t);
        return tenant.isPresent() && chosen.stream().allMatch(d -> tenant.get().kindOf(d) == ChannelKind.EMAIL);
    }

    private Set<DestinationId> destinations(TenantId t, UUID entityId, String typeId) {
        EventSettings s = settings.effective(t, entityId == null ? null : EntityId.of(entityId.toString()), EventTypeId.of(typeId));
        return s.enabled() ? s.destinations() : Set.of();
    }

    static NotificationEvent event(VeroNotice notice, String typeId, TenantId tenant, boolean withIdentifier) {
        NotificationEvent.Builder b = NotificationEvent.builder(EventTypeId.of(typeId), tenant)
                .link(AppLink.of(notice.event().link()));
        if (notice.entityId() != null) b.entity(EntityId.of(notice.entityId().toString()));
        if (withIdentifier) identifier(notice.recordIdentifier()).ifPresent(b::identifier);
        notice.counts().forEach(b::count);
        notice.times().forEach(b::time);
        return b.build();
    }

    /** The module's shape (at most 64 characters, a fixed set); an identifier that doesn't fit is left out. */
    static Optional<RecordIdentifier> identifier(String value) {
        if (value == null) return Optional.empty();
        try {
            return Optional.of(RecordIdentifier.of(value.trim()));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private Optional<TenantId> tenant(UUID entityId) {
        Optional<String> customer = entityId == null ? Optional.ofNullable(installCustomer) : customerOf.apply(entityId);
        return customer.map(TenantId::of);
    }

    private static void afterCommit(VeroEvent event, Runnable work) {
        Runnable safe = () -> {
            try {
                work.run();
            } catch (RuntimeException ex) {
                log.warn("Notification {} not published ({}). The work it reports is done.", event.id(), ex.getClass().getSimpleName());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }
}
