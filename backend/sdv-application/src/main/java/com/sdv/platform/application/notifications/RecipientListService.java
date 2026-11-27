package com.sdv.platform.application.notifications;

import com.sdv.platform.application.json.VeroJson;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.NotificationRecipientRepository;
import com.sdv.platform.application.port.NotificationRecipientRepository.Entry;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Task 0081 (answer 5 of plan 0069): the lists of who gets e-mail, kept by the admins. The Business Admin keeps the
 * entity's lists (one per role); the IT Admin keeps the customer-wide list of IT Admins. Messages go to the people
 * on the list of a role; the requester of a request gets the outcome when they are on one of the customer's lists.
 * <p>
 * Every change is audited (NOTIFICATION_RECIPIENT_ADDED, _REMOVED) with the role and the user ID, never the address:
 * addresses are in this table only, never in the audit or a log.
 */
@Service
public class RecipientListService {
    public static final String ADDED_ACTION = "NOTIFICATION_RECIPIENT_ADDED";
    public static final String REMOVED_ACTION = "NOTIFICATION_RECIPIENT_REMOVED";
    /** The roles of an entity's lists. The IT Admin's list is the customer's. */
    public static final Set<String> ENTITY_ROLES = Set.of("BUSINESS_USER", "DOCUMENT_STEWARD", "RESTRICTED_STEWARD", "BUSINESS_ADMIN");
    public static final String IT_ADMIN = "IT_ADMIN";
    /** As the platform's user ids: what a message's outbox row names, never the address. */
    static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    /** One @, something on both sides, no spaces or control characters; at most 254 characters. */
    static final Pattern EMAIL = Pattern.compile("[^@\\s\\p{Cntrl}]{1,64}@[^@\\s\\p{Cntrl}]{1,189}\\.[^@\\s\\p{Cntrl}.]{1,63}");
    static final int MAX_ENTRIES = 500;

    private final NotificationRecipientRepository lists;
    private final AuditEventPort audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public RecipientListService(NotificationRecipientRepository lists, AuditEventPort audit,
                                PlatformTransactionManager transactionManager) {
        this(lists, audit, transactionManager, Clock.systemUTC());
    }

    public RecipientListService(NotificationRecipientRepository lists, AuditEventPort audit,
                                PlatformTransactionManager transactionManager, Clock clock) {
        this.lists = Objects.requireNonNull(lists, "lists");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Who is asking, in which entity (the audit's tenant). */
    public record Actor(UUID entityId, String actorId, String actorName, String correlationId) { }

    /** A list entry as the screen shows it. The address is shown to the admin who keeps the list, and nowhere else. */
    public record View(UUID recipientId, String role, String userId, String email, String addedBy, Instant addedAt) {
        static View of(Entry e) {
            return new View(e.recipientId(), e.role(), e.userId(), e.email(), e.addedBy(), e.addedAt());
        }
    }

    /** The entity's lists ({@code entityId}), or the customer-wide IT Admin list ({@code entityId} null). */
    public List<View> list(String customerId, UUID entityId) {
        return lists.list(customerId, entityId).stream().map(View::of).toList();
    }

    public View add(Actor actor, String customerId, UUID entityId, String role, String userId, String email) {
        Objects.requireNonNull(actor, "actor");
        String r = role(role, entityId);
        String user = userId == null ? "" : userId.trim();
        if (!USER_ID.matcher(user).matches()) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.NTF_002,
                    "Give the person's user ID as Vero shows it: letters, digits, dots, dashes and underscores, at most 128.");
        }
        String address = email == null ? "" : email.trim();
        if (address.length() > 254 || !EMAIL.matcher(address).matches()) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.NTF_002, "Give one e-mail address, such as name@example.com.");
        }
        Instant now = clock.instant();
        Entry entry = new Entry(UUID.randomUUID(), customerId, entityId, r, user, address.toLowerCase(Locale.ROOT), actor.actorId(), now);
        return transactions.execute(status -> {
            if (lists.list(customerId, entityId).size() >= MAX_ENTRIES) {
                throw new CodedRefusal.Conflict(SdvErrorCode.NTF_003, "This list has " + MAX_ENTRIES + " people, the most it can hold.");
            }
            if (!lists.insert(entry)) {
                throw new CodedRefusal.Conflict(SdvErrorCode.NTF_003, "This person is on the list for this role already.");
            }
            append(actor, ADDED_ACTION, entry, now);
            return View.of(entry);
        });
    }

    public void remove(Actor actor, String customerId, UUID entityId, String recipientReference) {
        Objects.requireNonNull(actor, "actor");
        UUID id;
        try {
            id = UUID.fromString(recipientReference == null ? "" : recipientReference.trim());
        } catch (IllegalArgumentException ex) {
            throw new CodedRefusal.NotFound(SdvErrorCode.NTF_004, "This person is not on the list.");
        }
        transactions.executeWithoutResult(status -> {
            Optional<Entry> removed = lists.delete(customerId, entityId, id);
            Entry e = removed.orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.NTF_004, "This person is not on the list."));
            append(actor, REMOVED_ACTION, e, clock.instant());
        });
    }

    private static String role(String role, UUID entityId) {
        String r = role == null ? "" : role.trim();
        boolean ok = entityId == null ? IT_ADMIN.equals(r) : ENTITY_ROLES.contains(r);
        if (!ok) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.NTF_002, entityId == null
                    ? "The customer's list is for IT Admins only."
                    : "Choose one of the entity's roles: BUSINESS_USER, DOCUMENT_STEWARD, RESTRICTED_STEWARD or BUSINESS_ADMIN.");
        }
        return r;
    }

    private void append(Actor actor, String action, Entry e, Instant at) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("role", e.role());
        details.put("userId", e.userId());
        details.put("list", e.entityId() == null ? "CUSTOMER" : "ENTITY");
        String json;
        try {
            json = VeroJson.mapper().writeValueAsString(details);
        } catch (Exception ex) {
            throw new IllegalStateException("audit details", ex);
        }
        audit.append(new AuditEvent(UUID.randomUUID(), actor.entityId(), "USER", actor.actorId(), action,
                "NOTIFICATION_RECIPIENT", e.recipientId().toString(), at, actor.correlationId(), AuditOutcome.SUCCESS,
                json, actor.actorName()));
    }
}
