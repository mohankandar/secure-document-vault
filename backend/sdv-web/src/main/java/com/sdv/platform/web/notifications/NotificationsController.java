package com.sdv.platform.web.notifications;

import com.documentplatform.identity.store.EntityTimeZone;
import com.documentplatform.identity.store.EntityTimeZones;
import com.documentplatform.identity.store.TimeZoneOutcome;
import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.TenantId;
import com.sdv.platform.application.json.VeroJson;
import com.sdv.platform.application.notifications.RecipientListService;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task 0081: the two notification screens.
 * <ul>
 *   <li>IT Admin ({@code /it}): the channels and their health ({@code GET}), connecting them under the install's app address ({@code PUT}, task 0100: the address is read-only), the
 *       technical events ({@code GET /it/events}, {@code PUT /it/events/{id}}), the customer's time zone
 *       ({@code PUT /it/time-zone}) and the IT Admins' list ({@code /it/recipients}).</li>
 *   <li>Business Admin ({@code /business}): the entity's business events, its own time zone and its lists.</li>
 * </ul>
 * Anyone else gets SDV-NTF-001. Notifications switched off: SDV-NTF-007. Every change is audited (who, what, never an
 * address or a secret); refused changes change nothing.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
public class NotificationsController {
    public static final String SETTING_ACTION = "NOTIFICATION_SETTING_CHANGED";
    public static final String CONNECTED_ACTION = "NOTIFICATION_CHANNELS_CONNECTED";
    public static final String ZONE_ACTION = "TIME_ZONE_CHANGED";

    private final SecurityContextFacade security;
    private final VeroNotificationsConfiguration.NotificationScreens screens;
    private final RecipientListService lists;
    private final PlatformIdentityReads identity;
    private final EntityTimeZones zones;
    private final AuditEventPort audit;
    private final Clock clock = Clock.systemUTC();

    public NotificationsController(SecurityContextFacade security, VeroNotificationsConfiguration.NotificationScreens screens,
                                   RecipientListService lists, PlatformIdentityReads identity, EntityTimeZones zones,
                                   AuditEventPort audit) {
        this.security = security;
        this.screens = screens;
        this.lists = lists;
        this.identity = identity;
        this.zones = zones;
        this.audit = audit;
    }

    public record ZoneRequest(String timeZone) { }
    public record RecipientRequest(String role, String userId, String email) { }

    /** ownZone: the entity's own (business) or the customer's (IT); effectiveZone: the one messages and reports use. */
    public record ItView(NotificationSettingsService.Channels channels, String customerZone) { }
    public record BusinessView(List<NotificationSettingsService.EventRow> events, List<NotificationSettingsService.Destination> channels,
                               boolean channelsConnected, String ownZone, String customerZone) { }

    // ---------------------------------------------------------------------------------------------------- IT

    @GetMapping("/it")
    public ResponseEntity<ItView> it() {
        Scope s = scope(SdvRole.IT_ADMIN);
        return ok(new ItView(service().channels(s.tenant()), customerZone(s)));
    }

    @PutMapping("/it")
    public ResponseEntity<ItView> connect() {
        // Task 0100 (VSEC-204): under the install's address only; an address in the request is not read.
        Scope s = scope(SdvRole.IT_ADMIN);
        var channels = service().connect(s.tenant(), ZoneId.of("UTC"));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("appAddress", channels.appAddress());
        details.put("destinations", channels.destinations().size() + (channels.mailConfigured() ? 1 : 0));
        append(s, CONNECTED_ACTION, "NOTIFICATIONS", s.customer(), details);
        return ok(new ItView(channels, customerZone(s)));
    }

    @GetMapping("/it/events")
    public ResponseEntity<List<NotificationSettingsService.EventRow>> technical() {
        Scope s = scope(SdvRole.IT_ADMIN);
        return ok(service().events(s.tenant(), null));
    }

    @PutMapping("/it/events/{eventId}")
    public ResponseEntity<NotificationSettingsService.EventRow> technical(@PathVariable String eventId,
                                                                          @RequestBody(required = false) NotificationSettingsService.Change change) {
        Scope s = scope(SdvRole.IT_ADMIN);
        return ok(changed(s, service().change(s.tenant(), null, eventId, safe(change))));
    }

    @PutMapping("/it/time-zone")
    public ResponseEntity<ItView> customerZone(@RequestBody(required = false) ZoneRequest request) {
        Scope s = scope(SdvRole.IT_ADMIN);
        String zone = request == null || request.timeZone() == null ? "" : request.timeZone().trim();
        TimeZoneOutcome outcome = zones.setCustomerZone(s.customer(), zone, s.person().subject());
        zoneOutcome(s, "CUSTOMER", s.customer(), zone, outcome);
        return ok(new ItView(service().channels(s.tenant()), customerZone(s)));
    }

    @GetMapping("/it/recipients")
    public ResponseEntity<List<RecipientListService.View>> itRecipients() {
        Scope s = scope(SdvRole.IT_ADMIN);
        return ok(lists.list(s.customer(), null));
    }

    @PostMapping("/it/recipients")
    public ResponseEntity<RecipientListService.View> addItRecipient(@RequestBody(required = false) RecipientRequest request) {
        Scope s = scope(SdvRole.IT_ADMIN);
        RecipientRequest r = request == null ? new RecipientRequest(null, null, null) : request;
        return status(HttpStatus.CREATED, lists.add(actor(s), s.customer(), null, RecipientListService.IT_ADMIN, r.userId(), r.email()));
    }

    @DeleteMapping("/it/recipients/{recipientId}")
    public ResponseEntity<Void> removeItRecipient(@PathVariable String recipientId) {
        Scope s = scope(SdvRole.IT_ADMIN);
        lists.remove(actor(s), s.customer(), null, recipientId);
        return ResponseEntity.noContent().build();
    }

    // ---------------------------------------------------------------------------------------------- business

    @GetMapping("/business")
    public ResponseEntity<BusinessView> business() {
        Scope s = scope(SdvRole.BUSINESS_ADMIN);
        return ok(businessView(s));
    }

    @PutMapping("/business/events/{eventId}")
    public ResponseEntity<NotificationSettingsService.EventRow> business(@PathVariable String eventId,
                                                                         @RequestBody(required = false) NotificationSettingsService.Change change) {
        Scope s = scope(SdvRole.BUSINESS_ADMIN);
        return ok(changed(s, service().change(s.tenant(), s.entity(), eventId, safe(change))));
    }

    @PutMapping("/business/time-zone")
    public ResponseEntity<BusinessView> entityZone(@RequestBody(required = false) ZoneRequest request) {
        Scope s = scope(SdvRole.BUSINESS_ADMIN);
        String zone = request == null || request.timeZone() == null ? "" : request.timeZone().trim();
        TimeZoneOutcome outcome = zones.setEntityZone(s.slot(), zone, s.person().subject());
        zoneOutcome(s, "ENTITY", s.slot(), zone, outcome);
        return ok(businessView(s));
    }

    @GetMapping("/business/recipients")
    public ResponseEntity<List<RecipientListService.View>> recipients() {
        Scope s = scope(SdvRole.BUSINESS_ADMIN);
        return ok(lists.list(s.customer(), s.person().tenantId()));
    }

    @PostMapping("/business/recipients")
    public ResponseEntity<RecipientListService.View> addRecipient(@RequestBody(required = false) RecipientRequest request) {
        Scope s = scope(SdvRole.BUSINESS_ADMIN);
        RecipientRequest r = request == null ? new RecipientRequest(null, null, null) : request;
        return status(HttpStatus.CREATED, lists.add(actor(s), s.customer(), s.person().tenantId(), r.role(), r.userId(), r.email()));
    }

    @DeleteMapping("/business/recipients/{recipientId}")
    public ResponseEntity<Void> removeRecipient(@PathVariable String recipientId) {
        Scope s = scope(SdvRole.BUSINESS_ADMIN);
        lists.remove(actor(s), s.customer(), s.person().tenantId(), recipientId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private BusinessView businessView(Scope s) {
        NotificationSettingsService service = service();
        var channels = service.channels(s.tenant());
        List<NotificationSettingsService.Destination> webhooks = channels.destinations().stream()
                .filter(d -> !"EMAIL".equals(d.channel()))
                // The Business Admin picks channels by name; the secrets' paths and the health details are IT's.
                .map(d -> new NotificationSettingsService.Destination(d.id(), d.channel(), null, d.state(), null, null, null)).toList();
        EntityTimeZone zone = zones.zone(s.person().tenantId()).orElse(null);
        return new BusinessView(service.events(s.tenant(), s.entity()), webhooks, channels.connected(),
                zone == null || zone.own() == null ? null : zone.own().getId(), customerZone(s));
    }

    private String customerZone(Scope s) {
        return zones.customerZone(s.customer()).map(ZoneId::getId).orElse(null);
    }

    private NotificationSettingsService.EventRow changed(Scope s, NotificationSettingsService.EventRow row) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("event", row.id());
        details.put("enabled", row.enabled());
        details.put("frequency", row.frequency());
        if (row.dailyAt() != null) details.put("dailyAt", row.dailyAt());
        details.put("email", row.email());
        details.put("channels", row.channels());
        details.put("level", s.entity() == null ? "CUSTOMER" : "ENTITY");
        append(s, SETTING_ACTION, "NOTIFICATION_EVENT", row.id(), details);
        return row;
    }

    private void zoneOutcome(Scope s, String level, String subject, String zone, TimeZoneOutcome outcome) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("level", level);
        details.put("result", outcome.result().name());
        details.put("timeZone", zone.isEmpty() ? null : zone);
        AuditOutcome result = outcome.refused() ? AuditOutcome.FAILED : AuditOutcome.SUCCESS;
        audit.append(new AuditEvent(UUID.randomUUID(), s.person().tenantId(), "USER", s.person().subject(), ZONE_ACTION,
                level.equals("CUSTOMER") ? "CUSTOMER" : "ENTITY", subject, clock.instant(), MDC.get("correlationId"), result,
                json(details), s.person().displayName()));
        if (outcome.refused()) {
            throw new CodedRefusal.BadRequest(SdvErrorCode.NTF_006, level.equals("ENTITY") && zone.isEmpty()
                    ? "The time zone couldn't be cleared." : "Give a time zone name such as America/New_York or Europe/London.");
        }
    }

    private void append(Scope s, String action, String targetType, String targetId, Map<String, Object> details) {
        audit.append(new AuditEvent(UUID.randomUUID(), s.person().tenantId(), "USER", s.person().subject(), action, targetType,
                targetId, clock.instant(), MDC.get("correlationId"), AuditOutcome.SUCCESS, json(details), s.person().displayName()));
    }

    private static String json(Map<String, Object> details) {
        try {
            return VeroJson.mapper().writeValueAsString(details);
        } catch (Exception ex) {
            throw new IllegalStateException("audit details", ex);
        }
    }

    private static NotificationSettingsService.Change safe(NotificationSettingsService.Change change) {
        return change == null ? new NotificationSettingsService.Change(null, null, null, null, null) : change;
    }

    private RecipientListService.Actor actor(Scope s) {
        return new RecipientListService.Actor(s.person().tenantId(), s.person().subject(), s.person().displayName(), MDC.get("correlationId"));
    }

    private NotificationSettingsService service() {
        return screens.get().orElseThrow(() -> new CodedRefusal.Conflict(SdvErrorCode.NTF_007,
                "Notifications are switched off in this install. IT switches them on in the install configuration."));
    }

    private record Scope(SdvPrincipal person, String customer, String slot) {
        TenantId tenant() { return TenantId.of(customer); }
        EntityId entity() { return EntityId.of(person.tenantId().toString()); }
    }

    private Scope scope(SdvRole role) {
        SdvPrincipal person = security.requirePrincipal();
        if (!person.roles().contains(role)) {
            throw new CodedRefusal.Forbidden(SdvErrorCode.NTF_001, role == SdvRole.IT_ADMIN
                    ? "Only an IT Admin can set up the channels and the technical notifications."
                    : "Only a Business Admin can choose this entity's notifications.");
        }
        PlatformIdentityReads.EntitySlot slot = identity.slotOf(person.tenantId())
                .orElseThrow(() -> new CodedRefusal.Conflict(SdvErrorCode.NTF_007, "This entity is not set up in identity yet."));
        return new Scope(person, slot.tenantId(), slot.slotId());
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return status(HttpStatus.OK, body);
    }

    private static <T> ResponseEntity<T> status(HttpStatus status, T body) {
        return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
