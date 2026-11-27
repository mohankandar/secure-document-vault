package com.sdv.platform.application.notifications;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Task 0081: what Vero tells people about, by e-mail, Teams or Slack. The web layer registers each one in the platform's
 * event catalogue. Names are generic on purpose: no name says what kind of request or document it is about, so a
 * message never tells anyone that a Highly Confidential document exists (a print request is always about one).
 * <p>
 * {@link #identifier()} events carry the record's primary identifier, by e-mail only and never for a Highly
 * Confidential record (answer 4 of plan 0069); they are registered twice: once for e-mail with the identifier, once for
 * Teams and Slack without it.
 */
public enum VeroEvent {
    // Business events: per entity, chosen by the Business Admin.
    APPROVAL_WAITING("vero.approval-waiting", "Approval waiting", Kind.BUSINESS, true, Audience.BUSINESS_ADMIN, "/governance/approvals"),
    REQUEST_APPROVED("vero.request-approved", "Your request was approved", Kind.BUSINESS, true, Audience.REQUESTER, "/home"),
    REQUEST_DECLINED("vero.request-declined", "Your request was declined", Kind.BUSINESS, true, Audience.REQUESTER, "/home"),
    INCIDENT_OPENED("vero.incident-opened", "Quarantine incident opened", Kind.BUSINESS, false, Audience.BUSINESS_ADMIN, "/quarantine"),
    INCIDENT_RELEASED("vero.incident-released", "Quarantine incident released", Kind.BUSINESS, false, Audience.BUSINESS_ADMIN, "/quarantine"),
    HOLD_PLACED("vero.hold-placed", "Legal hold placed", Kind.BUSINESS, true, Audience.BUSINESS_ADMIN, "/governance/holds"),
    HOLD_RELEASE_ASKED("vero.hold-release-asked", "Legal hold release asked", Kind.BUSINESS, true, Audience.BUSINESS_ADMIN, "/governance/holds"),
    HOLD_RELEASED("vero.hold-released", "Legal hold released", Kind.BUSINESS, true, Audience.BUSINESS_ADMIN, "/governance/holds"),
    ROLES_WITHHELD("vero.roles-withheld", "Roles withheld at sign-in", Kind.BUSINESS, false, Audience.BUSINESS_ADMIN, "/governance/access"),
    CLEANUP_FAILURES("vero.cleanup-failures", "Retention cleanup could not delete everything", Kind.BUSINESS, false, Audience.BUSINESS_ADMIN, "/governance/retention"),

    // Technical events: chosen by the IT Admin for the whole customer; they never carry a record identifier.
    DOCUMENTS_NOT_STORED("vero.documents-not-stored", "Documents could not be stored", Kind.TECHNICAL, false, Audience.IT_ADMIN, "/tenant/problems"),
    DELIVERIES_INCOMPLETE("vero.deliveries-incomplete", "Deliveries still missing documents", Kind.TECHNICAL, false, Audience.IT_ADMIN, "/tenant/problems"),
    STORAGE_NEAR_FULL("vero.storage-near-full", "Storage nearly full", Kind.TECHNICAL, false, Audience.IT_ADMIN, "/tenant/storage"),
    STORAGE_FULL("vero.storage-full", "Storage full, intake stopped", Kind.TECHNICAL, false, Audience.IT_ADMIN, "/tenant/storage"),
    UNIDENTIFIED_ATTEMPTS("vero.unidentified-attempts", "Unidentified intake attempts", Kind.TECHNICAL, false, Audience.IT_ADMIN, "/tenant/problems"),
    KEY_SERVICE_AWAY("vero.key-service-away", "Key service not reachable", Kind.TECHNICAL, false, Audience.IT_ADMIN, "/tenant/about");

    public enum Kind { BUSINESS, TECHNICAL }

    /** Who gets it: the entity's Business Admins, the customer's IT Admins, or the one person who asked. */
    public enum Audience { BUSINESS_ADMIN, IT_ADMIN, REQUESTER }

    /** Count names the events carry. */
    public static final String COUNT_DOCUMENTS = "documents";
    public static final String COUNT_DELIVERIES = "deliveries";
    public static final String COUNT_ATTEMPTS = "attempts";
    public static final String COUNT_FAILED = "failed";
    public static final String COUNT_PERCENT_USED = "percentUsed";
    public static final String COUNT_PEOPLE = "people";
    /** Time names the events carry. */
    public static final String TIME_EXPIRES = "expires";

    private final String id;
    private final String label;
    private final Kind kind;
    private final boolean identifier;
    private final Audience audience;
    private final String link;

    VeroEvent(String id, String label, Kind kind, boolean identifier, Audience audience, String link) {
        this.id = id;
        this.label = label;
        this.kind = kind;
        this.identifier = identifier;
        this.audience = audience;
        this.link = link;
    }

    /** The event type's id in the catalogue (the e-mail one, for events with an identifier). */
    public String id() { return id; }

    /** The id of the Teams and Slack twin of an event with an identifier: the same event, never with the identifier. */
    public Optional<String> channelId() {
        return identifier && audience != Audience.REQUESTER ? Optional.of(id + ".channel") : Optional.empty();
    }

    public String label() { return label; }

    public Kind kind() { return kind; }

    /** Whether the e-mail may carry the record's primary identifier (never for a Highly Confidential record). */
    public boolean identifier() { return identifier; }

    public Audience audience() { return audience; }

    /** The path into Vero the message links to (the browser opens it as {@code /#<link>}). */
    public String link() { return link; }

    /** A condition that lasts (alert after a while, repeat, Recovered), rather than a one-off event. */
    public boolean continuous() {
        return CONDITIONS.contains(this);
    }

    /** Something is down: always on, Immediately or Every hour, always to the IT Admin. */
    public boolean critical() {
        return this == KEY_SERVICE_AWAY || this == STORAGE_FULL;
    }

    /** The counts an event of this kind may carry. */
    public List<String> counts() {
        return switch (this) {
            case INCIDENT_OPENED, INCIDENT_RELEASED, DOCUMENTS_NOT_STORED -> List.of(COUNT_DOCUMENTS);
            case DELIVERIES_INCOMPLETE -> List.of(COUNT_DELIVERIES);
            case UNIDENTIFIED_ATTEMPTS -> List.of(COUNT_ATTEMPTS);
            case CLEANUP_FAILURES -> List.of(COUNT_FAILED);
            case STORAGE_NEAR_FULL, STORAGE_FULL -> List.of(COUNT_PERCENT_USED);
            case ROLES_WITHHELD -> List.of(COUNT_PEOPLE);
            default -> List.of();
        };
    }

    /** The times an event of this kind may carry. */
    public List<String> times() {
        return this == APPROVAL_WAITING || this == HOLD_RELEASE_ASKED ? List.of(TIME_EXPIRES) : List.of();
    }

    private static final Set<VeroEvent> CONDITIONS = Set.of(DOCUMENTS_NOT_STORED, DELIVERIES_INCOMPLETE,
            STORAGE_NEAR_FULL, STORAGE_FULL, KEY_SERVICE_AWAY);
}
