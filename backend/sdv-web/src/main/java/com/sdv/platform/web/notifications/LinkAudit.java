package com.sdv.platform.web.notifications;

import com.documentplatform.notifications.approval.ApprovalAudit;
import com.sdv.platform.application.json.VeroJson;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Task 0082: platform-core's audit of approval links, in Vero's audit. One event per decision or refusal of a link
 * (action {@value #ACTION}): the link's request, its type, who, the action, the outcome and the refusal code. Never the
 * token, the link or the reason (Vero's own decision event, {@code ACCESS_REQUEST_APPROVED} or {@code _DECLINED}, is
 * written by Vero's decision). An entry without an entity (a link that names no request Vero knows) is in the log only,
 * with its code.
 */
final class LinkAudit implements ApprovalAudit {
    static final String ACTION = "APPROVAL_LINK_USED";
    private static final Logger log = LoggerFactory.getLogger(LinkAudit.class);

    private final AuditEventPort audit;

    LinkAudit(AuditEventPort audit) {
        this.audit = audit;
    }

    @Override
    public void record(Entry entry) {
        recordElsewhere(entry, "link");
    }

    @Override
    public void recordElsewhere(Entry e, String where) {
        UUID entity = entity(e);
        if (entity == null) {
            log.info("Approval link refused, no request of this entity: {}", e.refusal() == null ? e.outcome() : e.refusal().name());
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("linkRequestId", e.request() == null ? null : e.request().toString());
        details.put("type", e.type());
        details.put("action", e.action() == null ? null : e.action().name());
        details.put("outcome", e.outcome().name());
        if (e.refusal() != null) details.put("refusal", e.refusal().name());
        details.put("where", where);
        String json;
        try {
            json = VeroJson.mapper().writeValueAsString(details);
        } catch (Exception ex) {
            throw new IllegalStateException("audit details", ex);
        }
        audit.append(new AuditEvent(UUID.randomUUID(), entity, "USER", e.userId(), ACTION, "APPROVAL_LINK",
                e.request() == null ? "unknown" : e.request().toString(), e.at(), null,
                e.outcome() == Outcome.REFUSED ? AuditOutcome.DENIED : AuditOutcome.SUCCESS, json));
    }

    private static UUID entity(Entry e) {
        if (e.entity() == null) return null;
        try {
            return UUID.fromString(e.entity().value());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
