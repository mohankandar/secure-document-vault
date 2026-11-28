package com.sdv.platform.security.auth;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0041 (owner answer 5): a person whose sign-in is verified but who has not chosen an entity yet, because more
 * than one entity is open to them and the request named none ({@code X-SDV-Entity-Id}), or because no entity of theirs
 * is activated yet but they may activate one (Business Admin grant on a slot that is not activated).
 * <p>
 * It carries no roles and works in no entity. {@link EntityChoiceFilter} lets it reach only the entity list and the
 * activation address; every other request is refused with SDV-AUT-010 ("choose an entity").
 *
 * @param entities         the ACTIVE entities this sign-in may choose (none, or more than one)
 * @param activatableSlots the slots (ENT-...) this person may activate as Business Admin
 * @param firstStartSlots  task 0098: while no entity is ACTIVE, the slots this person is IT Admin of; they may activate
 *                         the first entity on the first-start screen
 */
public record EntityChoicePrincipal(String subject, String displayName, String email, Set<UUID> entities,
                                    List<String> activatableSlots, List<String> firstStartSlots) {
    public EntityChoicePrincipal {
        entities = Set.copyOf(entities);
        activatableSlots = List.copyOf(activatableSlots);
        firstStartSlots = List.copyOf(firstStartSlots);
    }

    public EntityChoicePrincipal(String subject, String displayName, String email, Set<UUID> entities, List<String> activatableSlots) {
        this(subject, displayName, email, entities, activatableSlots, List.of());
    }
}
