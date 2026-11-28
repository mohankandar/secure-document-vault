package com.sdv.platform.web.admin;

import com.documentplatform.identity.store.ActivationOutcome;
import com.documentplatform.identity.store.SlotActivation;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceException;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.documentplatform.identity.EntityUuid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Task 0098: the first entity, activated on the first-start screen. Only while no entity of the install is ACTIVE; then
 * it is gone for good ({@link Gone}: the address answers as if nothing were there). Who: a person whose groups give
 * IT Admin on that slot (from the grants setup loaded). What: the entity's name and the licence file, which must be this
 * install's licence as stored (verified with this build's keys, the same licence ID, a version not older) and list the
 * slot (answer Q-7 A: proof of possession; nothing is stored from it). Of two at once only one wins: the work runs under
 * one lock for the whole install, and the second then finds an ACTIVE entity.
 * <p>
 * Audit: the activation as ENTITY_ACTIVATED ({@code firstStart: true}) in the new entity. A refused attempt can't be
 * audited yet (an audit row belongs to an entity, and there is none): it is one WARN line (who, slot, code; never the
 * file) and a row of {@code first_start_attempts} (answer Q-7, changeset 052), copied into the new entity's audit as
 * {@value #REFUSED_ACTION} when the activation succeeds; the table is emptied in that same transaction.
 */
public final class FirstStart {
    private static final Logger log = LoggerFactory.getLogger(FirstStart.class);
    public static final String REFUSED_ACTION = "FIRST_START_REFUSED";
    static final int MAX_LICENCE_CHARS = 200_000;

    /** The address answers "nothing is here": no entity may be activated on the first-start screen any more. */
    public static final class Gone extends RuntimeException {
        Gone() {
            super("no first start");
        }
    }

    /** One lock for the whole install, across every Vero of the database. */
    public interface Lock {
        <T> T exclusively(Supplier<T> work);
    }

    /** The person asking: subject, name, and the slots they are IT Admin of (fresh from the grants). */
    public record Person(String subject, String displayName, List<String> itAdminSlots) { }

    public record Activated(UUID entityId, String slotId, String name) { }

    /** A refused attempt, as kept until the first activation. */
    public record Refused(String subject, String displayName, String slotId, String code, Instant at) { }

    /** Where refused attempts wait (table first_start_attempts). */
    public interface Attempts {
        void add(Refused refused);

        /** Every attempt, oldest first, and none left after (in the caller's transaction). */
        List<Refused> takeAll();
    }

    private final BooleanSupplier anyEntityActive;
    private final Supplier<Licence> stored;
    private final BiFunction<byte[], String, Licence> verify;
    private final SlotActivation activation;
    private final TenantRepository tenants;
    private final AuditEventPort audit;
    private final Lock lock;
    private final Clock clock;
    private final Attempts attempts;
    private final org.springframework.transaction.support.TransactionOperations transactions;

    /**
     * @param stored the install's licence as stored (null when there is none)
     * @param verify the build's licence check: the file's bytes and the customer ID; throws {@link LicenceException}
     */
    public FirstStart(BooleanSupplier anyEntityActive, Supplier<Licence> stored, BiFunction<byte[], String, Licence> verify,
                      SlotActivation activation, TenantRepository tenants, AuditEventPort audit, Lock lock, Clock clock,
                      Attempts attempts, org.springframework.transaction.support.TransactionOperations transactions) {
        this.anyEntityActive = Objects.requireNonNull(anyEntityActive);
        this.stored = Objects.requireNonNull(stored);
        this.verify = Objects.requireNonNull(verify);
        this.activation = Objects.requireNonNull(activation);
        this.tenants = Objects.requireNonNull(tenants);
        this.audit = Objects.requireNonNull(audit);
        this.lock = Objects.requireNonNull(lock);
        this.clock = Objects.requireNonNull(clock);
        this.attempts = Objects.requireNonNull(attempts);
        this.transactions = Objects.requireNonNull(transactions);
    }

    /** The slots this person may activate first, or {@link Gone} once an entity is ACTIVE. */
    public List<String> open(Person person) {
        if (anyEntityActive.getAsBoolean()) throw new Gone();
        return List.copyOf(person.itAdminSlots());
    }

    public Activated activate(Person person, String slotId, String name, String licenceText) {
        return lock.exclusively(() -> {
            if (anyEntityActive.getAsBoolean()) throw new Gone();
            String slot = slotId == null ? "" : slotId.trim();
            if (!EntityUuid.isSlotId(slot) || !person.itAdminSlots().contains(slot)) {
                throw refuse(person, EntityUuid.isSlotId(slot) ? slot : "-", "NOT_IT_ADMIN",
                        new CodedRefusal.Forbidden(SdvErrorCode.ENT_002, "Only an IT Admin of " + (EntityUuid.isSlotId(slot) ? slot : "this slot")
                                + " can activate the first entity.", "no IT Admin grant on the slot"));
            }
            String problem = licenceProblem(slot, licenceText);
            if (problem != null) {
                throw refuse(person, slot, problem, new CodedRefusal.Conflict(SdvErrorCode.ENT_001,
                        "This licence file can't be used here (" + problem + "). Choose this install's licence file, as setup stored it.",
                        "first start: licence " + problem));
            }
            ActivationOutcome outcome = activation.activate(slot, name, person.subject());
            if (outcome.refused()) {
                throw refuse(person, slot, outcome.result().name(), new CodedRefusal.Conflict(SdvErrorCode.ENT_001, outcome.message(),
                        "first start refused: " + outcome.result()));
            }
            UUID entity = EntityUuid.fromSlotId(slot);
            String stored = outcome.entity() != null && outcome.entity().displayName() != null
                    ? outcome.entity().displayName() : SlotActivation.cleanName(name).orElse(slot);
            tenants.createEntity(entity, slot.toLowerCase(Locale.ROOT), stored);
            Instant now = clock.instant();
            transactions.executeWithoutResult(status -> {
            for (Refused r : attempts.takeAll()) {
                audit.append(new AuditEvent(UUID.randomUUID(), entity, "USER", r.subject(), REFUSED_ACTION, "ENTITY", r.slotId(), r.at(),
                        null, AuditOutcome.DENIED, "{\"slotId\":\"" + r.slotId() + "\",\"code\":\"" + r.code() + "\"}", r.displayName()));
            }
            audit.append(new AuditEvent(UUID.randomUUID(), entity, "USER", person.subject(), EntityActivationController.ACTION, "ENTITY", slot,
                    now, org.slf4j.MDC.get("correlationId"), AuditOutcome.SUCCESS,
                    "{\"slotId\":\"" + slot + "\",\"firstStart\":true}", person.displayName()));
            });
            return new Activated(entity, slot, stored);
        });
    }

    /** Null when the file is this install's licence and lists the slot; else the reason's code. */
    private String licenceProblem(String slot, String text) {
        if (text == null || text.isBlank() || text.length() > MAX_LICENCE_CHARS) return "LICENCE_MALFORMED";
        Licence current = stored.get();
        if (current == null) return "LICENCE_MISSING";
        Licence file;
        try {
            file = verify.apply(text.getBytes(StandardCharsets.UTF_8), current.tenantId());
        } catch (LicenceException ex) {
            return ex.code().name();
        } catch (RuntimeException ex) {
            return "LICENCE_MALFORMED";
        }
        if (!Objects.equals(file.licenceId(), current.licenceId()) || file.version() < current.version()) return "LICENCE_NOT_THIS_INSTALL";
        if (file.slot(slot).isEmpty()) return "LICENCE_SLOT_NOT_LISTED";
        return null;
    }

    private RuntimeException refuse(Person person, String slot, String code, RuntimeException answer) {
        log.warn("First start refused: who={} slot={} code={}", person.subject(), slot, code);
        try {
            attempts.add(new Refused(person.subject(), person.displayName(), slot, code, clock.instant()));
        } catch (RuntimeException ex) {
            log.error("A refused first start could not be kept for the audit ({}).", ex.getClass().getSimpleName());
        }
        return answer;
    }

}
