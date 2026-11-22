package com.sdv.platform.application.approvals;

import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.access.SensitiveAccessGrants;
import com.sdv.platform.application.port.AccessRequestRepository;
import com.sdv.platform.application.port.AccessRequestRepository.AccessRequest;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0028: the grants behind {@link SensitiveAccessGrants}, and the expiry of requests nobody decided in time and of
 * grants nobody used in time. A grant is used in one statement together with its audit event, in one transaction: two
 * prints at once can't both use it, and it is never used without the event.
 */
@Component
public class SensitiveAccessGrantStore implements SensitiveAccessGrants {
    public static final String GRANT_USED_ACTION = "SENSITIVE_ACCESS_GRANT_USED";
    /** Task 0043: a used grant given back because nothing left Vero. */
    public static final String GRANT_RETURNED_ACTION = "SENSITIVE_ACCESS_GRANT_RETURNED";
    public static final String EXPIRED_ACTION = "ACCESS_REQUEST_EXPIRED";

    private final AccessRequestRepository requests;
    private final AuditEventPort audit;
    private final TransactionTemplate transactions;
    private final Clock clock;

    @Autowired
    public SensitiveAccessGrantStore(AccessRequestRepository requests, AuditEventPort audit,
                                     PlatformTransactionManager transactionManager) {
        this(requests, audit, transactionManager, Clock.systemUTC());
    }

    public SensitiveAccessGrantStore(AccessRequestRepository requests, AuditEventPort audit,
                                     PlatformTransactionManager transactionManager, Clock clock) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<UUID> grant(UUID tenantId, UUID documentId, String actorId, DocumentAccessGuard.Attempt attempt,
                                Use use, String correlationId) {
        if (tenantId == null || documentId == null || actorId == null || actorId.isBlank()) return Optional.empty();
        AccessRequestKind kind = switch (attempt) {
            case PRINT -> AccessRequestKind.PRINT;
            case DOWNLOAD -> AccessRequestKind.DOWNLOAD;
            default -> null;
        };
        if (kind == null) return Optional.empty();
        Instant now = clock.instant();
        if (use == Use.CHECK) return requests.findValidGrant(tenantId, documentId, actorId, kind, now);
        return transactions.execute(status -> {
            Optional<UUID> used = requests.useGrant(tenantId, documentId, actorId, kind, now);
            used.ifPresent(requestId -> audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId,
                    GRANT_USED_ACTION, "DOCUMENT", documentId.toString(), now, correlationId, AuditOutcome.SUCCESS,
                    "{\"accessRequestId\":\"" + requestId + "\",\"kind\":\"" + kind.name() + "\"}")));
            return used;
        });
    }

    /**
     * Task 0043: the grant is approved and unused again, with its audit event, in one transaction. Only a grant that is
     * USED by this person for this document and kind; anything else changes nothing (false).
     */
    @Override
    public boolean giveBack(UUID tenantId, UUID documentId, String actorId, DocumentAccessGuard.Attempt attempt,
                            UUID grantId, String reason, String correlationId) {
        if (tenantId == null || documentId == null || grantId == null || actorId == null || actorId.isBlank()) return false;
        AccessRequestKind kind = switch (attempt) {
            case PRINT -> AccessRequestKind.PRINT;
            case DOWNLOAD -> AccessRequestKind.DOWNLOAD;
            default -> null;
        };
        if (kind == null) return false;
        String why = reason == null || !reason.matches("[a-z ]{1,60}") ? "failed before anything was sent" : reason;
        Boolean returned = transactions.execute(status -> {
            boolean back = requests.giveBackGrant(tenantId, grantId, documentId, actorId, kind);
            if (back) {
                audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId, GRANT_RETURNED_ACTION,
                        "DOCUMENT", documentId.toString(), clock.instant(), correlationId, AuditOutcome.SUCCESS,
                        "{\"accessRequestId\":\"" + grantId + "\",\"kind\":\"" + kind.name() + "\",\"reason\":\"" + why + "\"}"));
            }
            return back;
        });
        return Boolean.TRUE.equals(returned);
    }

    /**
     * Expires this tenant's pending requests whose time is up and approved grants whose use window has ended, each
     * with an audit event, in one transaction. Returns how many changed.
     */
    public int expireDue(UUID tenantId, String correlationId) {
        Instant now = clock.instant();
        Integer count = transactions.execute(status -> {
            List<AccessRequest> expired = requests.expireDue(tenantId, now);
            for (AccessRequest r : expired) {
                audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "SYSTEM", "vero", EXPIRED_ACTION,
                        AccessRequestService.targetType(r), AccessRequestService.targetId(r), now, correlationId,
                        AuditOutcome.SUCCESS, "{\"accessRequestId\":\"" + r.requestId() + "\",\"kind\":\"" + r.kind().name()
                        + "\",\"was\":\"" + r.status().name() + "\"}"));
            }
            return expired.size();
        });
        return count == null ? 0 : count;
    }
}
