package com.sdv.platform.application.source;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.source.IngestionSourceStatus;
import com.sdv.platform.domain.source.SourceLimits;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class IngestionSourceAdminService {
    private static final Pattern SOURCE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private final TenantRepository tenants;
    private final IngestionSourceRepository sources;
    private final AuditEventPort audit;
    private final Clock clock;

    @Autowired
    public IngestionSourceAdminService(TenantRepository tenants,
                                       IngestionSourceRepository sources,
                                       AuditEventPort audit) {
        this(tenants, sources, audit, Clock.systemUTC());
    }

    IngestionSourceAdminService(TenantRepository tenants,
                                IngestionSourceRepository sources,
                                AuditEventPort audit,
                                Clock clock) {
        this.tenants = tenants;
        this.sources = sources;
        this.audit = audit;
        this.clock = clock;
    }

    public List<IngestionSource> list(UUID tenantId) {
        requireActiveTenant(tenantId);
        return sources.findAll(tenantId);
    }

    public IngestionSource get(UUID tenantId, UUID sourceId) {
        requireActiveTenant(tenantId);
        return sources.findById(tenantId, sourceId)
                .orElseThrow(() -> new NoSuchElementException("Ingestion source not found."));
    }

    @Transactional
    public IngestionSource create(UUID tenantId,
                                  CreateIngestionSourceCommand command,
                                  String actorId,
                                  String correlationId) {
        requireActiveTenant(tenantId);
        validateSourceKey(command.sourceKey());
        validateText(command.displayName(), "displayName");
        if (command.authType() == null) throw new IllegalArgumentException("authType is required.");
        validateText(command.credentialReference(), "credentialReference");
        validateLimits(command.limits());
        if (sources.findByKey(tenantId, command.sourceKey()).isPresent()) {
            throw new IllegalArgumentException("sourceKey already exists for this tenant.");
        }

        Instant now = clock.instant();
        IngestionSource source = new IngestionSource(
                UUID.randomUUID(), tenantId, command.sourceKey(), command.displayName().trim(),
                IngestionSourceStatus.ACTIVE, command.authType(), command.credentialReference().trim(),
                command.limits(), now, now);
        sources.insert(source);
        audit.append(auditEvent(tenantId, actorId, "INGESTION_SOURCE_CREATED", source.sourceId().toString(), correlationId));
        return source;
    }

    @Transactional
    public IngestionSource update(UUID tenantId,
                                  UUID sourceId,
                                  UpdateIngestionSourceCommand command,
                                  String actorId,
                                  String correlationId) {
        requireActiveTenant(tenantId);
        IngestionSource existing = get(tenantId, sourceId);
        validateText(command.displayName(), "displayName");
        if (command.status() == null) throw new IllegalArgumentException("status is required.");
        if (command.authType() == null) throw new IllegalArgumentException("authType is required.");
        validateText(command.credentialReference(), "credentialReference");
        validateLimits(command.limits());

        IngestionSource updated = new IngestionSource(
                existing.sourceId(), existing.tenantId(), existing.sourceKey(), command.displayName().trim(),
                command.status(), command.authType(), command.credentialReference().trim(), command.limits(),
                existing.createdAt(), clock.instant());
        sources.update(updated);
        audit.append(auditEvent(tenantId, actorId, "INGESTION_SOURCE_UPDATED", sourceId.toString(), correlationId));
        return updated;
    }

    private void requireActiveTenant(UUID tenantId) {
        var tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new NoSuchElementException("Tenant not found."));
        if (tenant.status() != TenantStatus.ACTIVE) {
            throw new IllegalStateException("Tenant is not active.");
        }
    }

    private static void validateSourceKey(String value) {
        if (value == null || !SOURCE_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException("sourceKey must be 1-100 characters using letters, numbers, '.', '_' or '-'.");
        }
    }

    private static void validateText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required.");
    }

    private static void validateLimits(SourceLimits limits) {
        if (limits == null) return;
        if (limits.maxPdfBytes() != null && limits.maxPdfBytes() <= 0) throw new IllegalArgumentException("maxPdfBytes must be positive.");
        if (limits.maxPagesPerPdf() != null && limits.maxPagesPerPdf() <= 0) throw new IllegalArgumentException("maxPagesPerPdf must be positive.");
        if (limits.maxDocumentsPerSubmission() != null && limits.maxDocumentsPerSubmission() <= 0) throw new IllegalArgumentException("maxDocumentsPerSubmission must be positive.");
        if (limits.maxAggregateSubmissionBytes() != null && limits.maxAggregateSubmissionBytes() <= 0) throw new IllegalArgumentException("maxAggregateSubmissionBytes must be positive.");
        if (limits.incompleteAttentionHours() != null && limits.incompleteAttentionHours() <= 0) throw new IllegalArgumentException("incompleteAttentionHours must be positive.");
    }

    private AuditEvent auditEvent(UUID tenantId, String actorId, String action, String targetId, String correlationId) {
        return new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId, action,
                "INGESTION_SOURCE", targetId, clock.instant(), correlationId, AuditOutcome.SUCCESS, "{}");
    }
}
