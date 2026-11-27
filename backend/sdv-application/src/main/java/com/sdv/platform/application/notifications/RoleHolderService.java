package com.sdv.platform.application.notifications;

import com.sdv.platform.application.json.VeroJson;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.NotificationRecipientRepository;
import com.sdv.platform.application.port.NotificationRecipientRepository.Entry;
import com.sdv.platform.application.port.RoleHolderRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Task 0100 (VSEC-203): what a sign-in shows of a person's list roles. Roles come from the sign-in's groups and are
 * taken away in the customer's login system, which tells Vero nothing; so each sign-in is written down here, and a
 * role that is gone takes the person off every list of that role in the same transaction, audited as
 * {@value RecipientListService#REMOVED_ACTION} with the reason {@value #ROLE_GONE}. The IT Admins' list is the
 * customer's: a person leaves it when no entity of the customer shows them as IT Admin any more.
 */
@Service
public class RoleHolderService {
    public static final String ROLE_GONE = "ROLE_GONE";
    /** The roles that have lists. */
    public static final Set<String> LIST_ROLES;

    static {
        Set<String> roles = new TreeSet<>(RecipientListService.ENTITY_ROLES);
        roles.add(RecipientListService.IT_ADMIN);
        LIST_ROLES = Set.copyOf(roles);
    }

    private final RoleHolderRepository holders;
    private final NotificationRecipientRepository lists;
    private final AuditEventPort audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public RoleHolderService(RoleHolderRepository holders, NotificationRecipientRepository lists, AuditEventPort audit,
                             PlatformTransactionManager transactionManager) {
        this(holders, lists, audit, transactionManager, Clock.systemUTC());
    }

    public RoleHolderService(RoleHolderRepository holders, NotificationRecipientRepository lists, AuditEventPort audit,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.holders = Objects.requireNonNull(holders, "holders");
        this.lists = Objects.requireNonNull(lists, "lists");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * A person signed in to an entity of the customer with these roles (any names; only list roles count). Returns
     * the list entries taken off. A user ID that can't be on a list is left alone.
     */
    public List<Entry> seen(String customerId, UUID entityId, String userId, Set<String> roles) {
        Objects.requireNonNull(entityId, "entityId");
        if (customerId == null || customerId.isBlank() || userId == null || !RecipientListService.USER_ID.matcher(userId).matches()) {
            return List.of();
        }
        Set<String> now = new TreeSet<>(roles == null ? Set.of() : roles);
        now.retainAll(LIST_ROLES);
        return transactions.execute(status -> {
            Set<String> before = holders.roles(entityId, userId);
            Instant at = clock.instant();
            holders.replace(customerId, entityId, userId, now, at);
            Set<String> gone = new LinkedHashSet<>(before);
            gone.removeAll(now);
            List<Entry> removed = new java.util.ArrayList<>();
            for (String role : gone) {
                if (RecipientListService.IT_ADMIN.equals(role)) {
                    if (holders.holders(customerId, null, role).contains(userId)) continue;
                    removed.addAll(lists.deleteRole(customerId, null, userId, role));
                } else {
                    removed.addAll(lists.deleteRole(customerId, entityId, userId, role));
                }
            }
            for (Entry e : removed) append(entityId, e, at);
            return List.copyOf(removed);
        });
    }

    private void append(UUID entityId, Entry e, Instant at) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("role", e.role());
        details.put("userId", e.userId());
        details.put("list", e.entityId() == null ? "CUSTOMER" : "ENTITY");
        details.put("reason", ROLE_GONE);
        String json;
        try {
            json = VeroJson.mapper().writeValueAsString(details);
        } catch (Exception ex) {
            throw new IllegalStateException("audit details", ex);
        }
        audit.append(new AuditEvent(UUID.randomUUID(), entityId, "SYSTEM", "vero", RecipientListService.REMOVED_ACTION,
                "NOTIFICATION_RECIPIENT", e.recipientId().toString(), at, null, AuditOutcome.SUCCESS, json, "Vero"));
    }
}
