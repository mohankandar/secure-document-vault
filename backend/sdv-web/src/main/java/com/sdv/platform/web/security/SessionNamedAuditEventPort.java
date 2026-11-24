package com.sdv.platform.web.security;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.web.config.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Task 0011 Part C.2: every new audit event gets the actor's display name from the signed-in session, when the event's
 * actor is the person or sending system signed in for this request (same tenant and subject). A person's name is the
 * one in the account menu (the name claim); a sending system's is its display name. Background work and events about
 * someone else keep no name. Nothing already stored is changed.
 */
@Primary
@Component
public class SessionNamedAuditEventPort implements AuditEventPort {
    private final AuditEventPort store;
    private final IngestionSourceRepository sources;

    @Autowired
    public SessionNamedAuditEventPort(JdbcFoundationRepository store) {
        this(store, store);
    }

    SessionNamedAuditEventPort(AuditEventPort store, IngestionSourceRepository sources) {
        this.store = store;
        this.sources = sources;
    }

    @Override
    public void append(AuditEvent event) {
        store.append(named(event));
    }

    @Override
    public void appendAll(java.util.List<AuditEvent> events) {
        store.appendAll(events.stream().map(this::named).toList());
    }

    /** Task 0062: the reference the caller sent is kept in the details as {@code callerReference}, beside Vero's own. */
    public AuditEvent named(AuditEvent event) {
        return withCallerReference(withName(event), MDC.get(CorrelationIdFilter.CALLER_MDC_KEY));
    }

    /** The filter lets only plain characters through, so the value needs no escaping. */
    static AuditEvent withCallerReference(AuditEvent event, String caller) {
        String details = event.safeDetailsJson();
        if (caller == null || details == null || !details.startsWith("{") || !details.endsWith("}")
                || details.contains("\"callerReference\"")) return event;
        String inner = details.substring(1).stripLeading();
        String field = "{\"callerReference\":\"" + caller + "\"";
        return new AuditEvent(event.auditEventId(), event.tenantId(), event.actorType(), event.actorId(), event.action(),
                event.targetType(), event.targetId(), event.occurredAt(), event.correlationId(), event.outcome(),
                inner.equals("}") ? field + "}" : field + "," + inner, event.actorDisplayName());
    }

    private AuditEvent withName(AuditEvent event) {
        if (event.actorDisplayName() != null) return event;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof SdvPrincipal person && "USER".equals(event.actorType())
                && person.subject().equals(event.actorId()) && person.tenantId().equals(event.tenantId())) {
            return person.displayName() == null ? event : event.withActorDisplayName(person.displayName());
        }
        if (principal instanceof IngestionSourcePrincipal system && "SOURCE".equals(event.actorType())
                && system.subject().equals(event.actorId()) && system.tenantId().equals(event.tenantId())) {
            try {
                return sources.findById(system.tenantId(), system.sourceId())
                        .map(source -> event.withActorDisplayName(source.displayName()))
                        .orElse(event);
            } catch (RuntimeException ex) {
                return event; // The event is still written, without a name.
            }
        }
        return event;
    }
}
