package com.sdv.platform.application.port;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0100 (VSEC-203): who held which list role in which entity at their last sign-in (table role_holders, changeset
 * 051). Roles come from each sign-in's groups and are taken away in the customer's login system, which tells Vero
 * nothing; this is what Vero has seen. It decides who may be on a role's list and who of a list gets messages.
 */
public interface RoleHolderRepository {

    /** The list roles the person held in the entity at their last sign-in there. */
    Set<String> roles(UUID entityId, String userId);

    /** The person holds exactly these list roles in the entity now (others are removed). */
    void replace(String customerId, UUID entityId, String userId, Set<String> roles, Instant seenAt);

    /** The user IDs holding the role in the entity, or in any entity of the customer with {@code entityId} null. */
    Set<String> holders(String customerId, UUID entityId, String role);
}
