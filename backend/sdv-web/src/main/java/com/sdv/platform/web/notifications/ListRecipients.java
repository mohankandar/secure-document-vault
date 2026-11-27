package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.Recipient;
import com.documentplatform.notifications.RecipientResolver;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.TenantId;
import com.sdv.platform.application.port.NotificationRecipientRepository;
import com.sdv.platform.application.port.NotificationRecipientRepository.Entry;
import com.sdv.platform.application.port.RoleHolderRepository;

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
 * <p>
 * Task 0100 (VSEC-203): of a role's list, only the people who held that role at their last sign-in get messages (and
 * approval links); an entry whose person never signed in with the role, or no longer holds it, gets nothing.
 */
public class ListRecipients implements RecipientResolver {
    private final NotificationRecipientRepository lists;
    private final RoleHolderRepository holders;

    public ListRecipients(NotificationRecipientRepository lists, RoleHolderRepository holders) {
        this.lists = lists;
        this.holders = holders;
    }

    @Override
    public Set<Recipient> resolve(TenantId tenant, EntityId entity, RoleId role) {
        List<Entry> entries;
        UUID scope = null;
        if (VeroCatalogue.IT_ADMIN.equals(role)) {
            entries = lists.holders(tenant.value(), null, role.value());
        } else if (entity == null) {
            return Set.of();
        } else {
            UUID id;
            try {
                id = UUID.fromString(entity.value());
            } catch (IllegalArgumentException ex) {
                return Set.of();
            }
            entries = lists.holders(tenant.value(), id, role.value());
            scope = id;
        }
        if (entries.isEmpty()) return Set.of();
        Set<String> held = holders.holders(tenant.value(), scope, role.value());
        Set<Recipient> people = new LinkedHashSet<>();
        for (Entry e : entries) if (held.contains(e.userId())) people.add(new Recipient(e.userId(), e.email()));
        return Set.copyOf(people);
    }

    @Override
    public Optional<Recipient> person(TenantId tenant, String userId) {
        List<String> addresses = lists.person(tenant.value(), userId).stream().map(Entry::email).distinct().toList();
        return addresses.size() == 1 ? Optional.of(new Recipient(userId, addresses.getFirst())) : Optional.empty();
    }
}
