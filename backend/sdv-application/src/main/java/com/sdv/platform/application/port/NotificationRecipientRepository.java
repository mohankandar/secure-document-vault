package com.sdv.platform.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0081: the recipient lists the admins keep (table notification_recipients, changeset 049). The only place Vero
 * keeps e-mail addresses; never in a log or the audit.
 */
public interface NotificationRecipientRepository {

    /** entityId null: the customer-wide list (the IT Admin's). */
    record Entry(UUID recipientId, String customerId, UUID entityId, String role, String userId, String email,
                 String addedBy, Instant addedAt) {
        @Override
        public String toString() {
            return "Entry[" + recipientId + ", " + role + ", " + userId + "]";
        }
    }

    /** The entries of one list: an entity's (all roles), or the customer-wide one with {@code entityId} null. */
    List<Entry> list(String customerId, UUID entityId);

    /** The people holding a role in an entity, or customer-wide with {@code entityId} null. */
    List<Entry> holders(String customerId, UUID entityId, String role);

    /** Every entry of one person in the customer's lists. */
    List<Entry> person(String customerId, String userId);

    /** False when the person is on that list for that role already. */
    boolean insert(Entry entry);

    /** The entry removed, if it was on this list. */
    Optional<Entry> delete(String customerId, UUID entityId, UUID recipientId);

    /** Task 0100: the person taken off one role's list (an entity's, or the customer's with {@code entityId} null). */
    default List<Entry> deleteRole(String customerId, UUID entityId, String userId, String role) {
        List<Entry> removed = new java.util.ArrayList<>();
        for (Entry e : holders(customerId, entityId, role)) {
            if (e.userId().equals(userId)) delete(customerId, entityId, e.recipientId()).ifPresent(removed::add);
        }
        return List.copyOf(removed);
    }
}
