package com.sdv.platform.application.retention;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.RetentionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class RetentionPolicyService {
    private final TenantRepository tenants;
    private final RetentionRepository retention;
    private final AuditEventPort audit;
    private final TransactionTemplate transactions;
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.systemUTC();

    public RetentionPolicyService(
            TenantRepository tenants,
            RetentionRepository retention,
            AuditEventPort audit,
            PlatformTransactionManager transactionManager) {
        this.tenants = tenants;
        this.retention = retention;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public RetentionPolicyOverview current(UUID tenantId) {
        tenants.findById(tenantId)
                .orElseThrow(() -> new NoSuchElementException("Tenant not found."));
        return overview(tenantId);
    }

    public RetentionPolicyOverview update(
            UUID tenantId,
            String policyName,
            String description,
            Integer defaultRetentionDays,
            String actorId,
            String correlationId) {
        String normalizedName = policyName == null || policyName.isBlank() ? "Retention policy update" : policyName.trim();
        String normalizedDescription = description == null || description.isBlank() ? null : description.trim();
        if (normalizedName.length() > 200) {
            throw new IllegalArgumentException("policyName must be 200 characters or fewer.");
        }
        if (normalizedDescription != null && normalizedDescription.length() > 1000) {
            throw new IllegalArgumentException("description must be 1000 characters or fewer.");
        }
        if (defaultRetentionDays != null && defaultRetentionDays <= 0) {
            throw new IllegalArgumentException("defaultRetentionDays must be positive or null for indefinite retention.");
        }

        var beforeTenant = tenants.findById(tenantId)
                .orElseThrow(() -> new NoSuchElementException("Tenant not found."));
        Integer previousDays = beforeTenant.guardrails().defaultRetentionDays();
        Instant now = clock.instant();

        transactions.executeWithoutResult(status -> {
            int nextVersion = retention.nextPolicyVersion(tenantId);
            retention.closeCurrentPolicy(tenantId, now);
            retention.insertPolicyVersion(new RetentionRepository.PolicyVersion(
                    UUID.randomUUID(), tenantId, nextVersion, normalizedName, normalizedDescription,
                    defaultRetentionDays, now, null, actorId, now));
            retention.updateDefaultRetentionDays(tenantId, defaultRetentionDays, now);
            audit.append(new AuditEvent(
                    UUID.randomUUID(), tenantId, "USER", actorId,
                    "RETENTION_POLICY_UPDATED", "TENANT", tenantId.toString(),
                    now, correlationId, AuditOutcome.SUCCESS,
                    details(previousDays, defaultRetentionDays, normalizedName, nextVersion)));
        });
        return overview(tenantId);
    }

    private RetentionPolicyOverview overview(UUID tenantId) {
        RetentionRepository.PolicyVersion current = retention.findCurrentPolicy(tenantId)
                .orElseThrow(() -> new IllegalStateException("Current retention policy history is unavailable."));
        List<PolicyVersion> history = retention.findPolicyHistory(tenantId, 50).stream()
                .map(RetentionPolicyService::toView)
                .toList();
        return new RetentionPolicyOverview(
                current.retentionDays(), current.retentionDays() == null,
                current.policyVersionId(), current.version(), current.policyName(), current.description(),
                current.effectiveFrom(), current.createdBy(), history);
    }

    private static PolicyVersion toView(RetentionRepository.PolicyVersion policy) {
        return new PolicyVersion(
                policy.policyVersionId(), policy.version(), policy.policyName(), policy.description(),
                policy.retentionDays(), policy.retentionDays() == null,
                policy.effectiveFrom(), policy.effectiveUntil(), policy.createdBy(), policy.createdAt());
    }

    private String details(Integer previous, Integer current, String policyName, int version) {
        try {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("previousDefaultRetentionDays", previous);
            safe.put("defaultRetentionDays", current);
            safe.put("indefinite", current == null);
            safe.put("policyName", policyName);
            safe.put("policyVersion", version);
            return json.writeValueAsString(safe);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize retention-policy audit details.", ex);
        }
    }

    public record RetentionPolicyOverview(
            Integer defaultRetentionDays,
            boolean indefinite,
            UUID policyVersionId,
            int version,
            String policyName,
            String description,
            Instant effectiveFrom,
            String createdBy,
            List<PolicyVersion> history) { }

    public record PolicyVersion(
            UUID policyVersionId,
            int version,
            String policyName,
            String description,
            Integer defaultRetentionDays,
            boolean indefinite,
            Instant effectiveFrom,
            Instant effectiveUntil,
            String createdBy,
            Instant createdAt) { }
}
