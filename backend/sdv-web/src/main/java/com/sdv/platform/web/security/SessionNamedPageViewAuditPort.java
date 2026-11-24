package com.sdv.platform.web.security;

import com.sdv.platform.application.port.PageViewAuditPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.persistence.business.JdbcPageViewAuditRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Page events (task 0007) get the signed-in person's name too (task 0011 Part C.2), like every other new event. */
@Primary
@Component
public class SessionNamedPageViewAuditPort implements PageViewAuditPort {
    private final JdbcPageViewAuditRepository store;
    private final SessionNamedAuditEventPort names;

    public SessionNamedPageViewAuditPort(JdbcPageViewAuditRepository store, SessionNamedAuditEventPort names) {
        this.store = store;
        this.names = names;
    }

    @Override
    public boolean isViewing(UUID tenantId, String actorId, UUID documentId, UUID viewingId, Instant notBefore) {
        return store.isViewing(tenantId, actorId, documentId, viewingId, notBefore);
    }

    @Override
    public boolean appendPreviewEventOnce(AuditEvent event, UUID viewingId, Instant notBefore) {
        return store.appendPreviewEventOnce(names.named(event), viewingId, notBefore);
    }

    @Override
    public boolean appendPageEventOnce(AuditEvent pageEvent, UUID viewingId, int pageNumber, Instant notBefore) {
        return store.appendPageEventOnce(names.named(pageEvent), viewingId, pageNumber, notBefore);
    }
}
