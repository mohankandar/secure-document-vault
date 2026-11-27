package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.Recipient;
import com.documentplatform.notifications.RecipientResolver;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.TenantId;
import com.sdv.platform.application.port.NotificationRecipientRepository;
import com.sdv.platform.application.port.NotificationRecipientRepository.Entry;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0081 (answer 5 of plan 0069): who holds a role, from the lists the admins keep. Asked at send time, so a change
 * to a list reaches the next message. The IT Admin is looked up for the whole customer, also for an entity's event.
 * A person (the requester of a request) is found on any of the customer's lists; when the lists give them two
 * different addresses, nobody is chosen (fail closed: the message fails with NO_RECIPIENTS).
 */
public class ListRecipients implements RecipientResolver {
    private final NotificationRecipientRepository lists;

    public ListRecipients(NotificationRecipientRepository lists) {
        this.lists = lists;
    }

    @Override
    public Set<Recipient> resolve(TenantId tenant, EntityId entity, RoleId role) {
        List<Entry> holders;
        if (VeroCatalogue.IT_ADMIN.equals(role)) {
            holders = lists.holders(tenant.value(), null, role.value());
        } else if (entity == null) {
            return Set.of();
        } else {
            UUID id;
            try {
                id = UUID.fromString(entity.value());
            } catch (IllegalArgumentException ex) {
                return Set.of();
            }
            holders = lists.holders(tenant.value(), id, role.value());
        }
        Set<Recipient> people = new LinkedHashSet<>();
        for (Entry e : holders) people.add(new Recipient(e.userId(), e.email()));
        return Set.copyOf(people);
    }

    @Override
    public Optional<Recipient> person(TenantId tenant, String userId) {
        List<String> addresses = lists.person(tenant.value(), userId).stream().map(Entry::email).distinct().toList();
        return addresses.size() == 1 ? Optional.of(new Recipient(userId, addresses.getFirst())) : Optional.empty();
    }
}
