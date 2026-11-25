package com.sdv.platform.application.retention;

import com.sdv.platform.application.json.VeroJson;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.ErasePort;
import com.sdv.platform.application.port.EraseRepository;
import com.sdv.platform.application.port.RetentionRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The daily cleanup. Task 0080: it claims what is due; the erasing itself is the erase log's ({@link ErasePort}), which also writes the
 * audit event. When the log can't go on, the rest of the run is left for a later one.
 */
@Service
public class RetentionDeletionService {
    public static final String DELETION_REASON = "RETENTION_EXPIRED";

    private final RetentionRepository retention;
    private final DocumentIntakeRepository documents;
    private final ErasePort erase;
    private final EraseRepository erased;
    private final AuditEventPort audit;
    private final TransactionTemplate transactions;
    private final ObjectMapper json = VeroJson.mapper();
    private final Clock clock = Clock.systemUTC();

    public RetentionDeletionService(
            RetentionRepository retention,
            DocumentIntakeRepository documents,
            ErasePort erase,
            EraseRepository erased,
            AuditEventPort audit,
            PlatformTransactionManager transactionManager) {
        this.retention = retention;
        this.documents = documents;
        this.erase = erase;
        this.erased = erased;
        this.audit = audit;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public SweepResult sweep(int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        List<RetentionRepository.DueDocument> due = retention.findDueDocuments(bounded);
        // Task 0027: documents on legal hold are never candidates; the run summary says how many were kept (a count).
        long held = retention.countHeldDueDocuments();
        int completed = 0;
        int failed = 0;
        boolean stopped = false;
        for (RetentionRepository.DueDocument candidate : due) {
            try {
                if (expire(candidate.tenantId(), candidate.documentId())) completed++;
            } catch (ErasePort.EraseFailed ex) {
                failed++;
                if (ex.refused()) continue;
                stopped = true; // the log goes in order: nothing after it would be erased either
                break;
            } catch (RuntimeException ex) {
                failed++;
            }
        }
        if (!stopped) failed += eraseCaseFields(bounded);
        return new SweepResult(due.size(), completed, failed, held);
    }

    /** The values of records whose last document is gone. Returns the failures. */
    private int eraseCaseFields(int limit) {
        int failed = 0;
        for (EraseRepository.RecordKey record : erased.findRecordsToErase(limit)) {
            try {
                erase.inTurn(() -> transactions.execute(status -> {
                    if (erased.lockRecordToErase(record)) erase.caseFields(record.tenantId(), record.sourceId(), record.recordId(), UUID.randomUUID());
                    return null;
                }));
            } catch (ErasePort.EraseFailed ex) {
                failed++;
                if (!ex.refused()) break;
            }
        }
        return failed;
    }

    boolean expire(UUID tenantId, UUID documentId) {
        Instant startedAt = clock.instant();
        // Task 0027: one transaction, because the claim locks the document row before it checks for a legal hold.
        Boolean claimed = transactions.execute(status -> retention.claimDueDocument(tenantId, documentId, startedAt));
        if (!Boolean.TRUE.equals(claimed)) return false;

        Document document = documents.findDocumentByIdForTenant(tenantId, documentId)
                .orElseThrow(() -> new NoSuchElementException("Retention candidate document not found."));
        if (document.status() != DocumentStatus.RETENTION_DELETING) {
            return false;
        }
        if (document.expiresAt() == null) {
            throw new IllegalStateException("Retention deletion cannot proceed without a snapshotted expiration.");
        }
        try {
            erase.document(tenantId, documentId, UUID.randomUUID());
            return true;
        } catch (RuntimeException ex) {
            safeFailureAudit(document, ex);
            throw ex;
        }
    }

    private void safeFailureAudit(Document document, Exception failure) {
        try {
            Map<String, Object> safe = new LinkedHashMap<>();
            safe.put("deletionReason", DELETION_REASON);
            safe.put("errorCode", failure instanceof ErasePort.EraseFailed f ? f.code() : failure.getClass().getSimpleName());
            audit.append(new AuditEvent(
                    UUID.randomUUID(), document.tenantId(), "SYSTEM", "retention-scheduler",
                    "DOCUMENT_RETENTION_DELETE_FAILED", "DOCUMENT", document.documentId().toString(),
                    clock.instant(), null, AuditOutcome.FAILED,
                    json.writeValueAsString(safe)));
        } catch (Exception ignored) {
        }
    }

    /** heldSkipped (task 0027): documents due for deletion that were kept because they are on legal hold. */
    public record SweepResult(int candidates, int completed, int failed, long heldSkipped) {
        public SweepResult(int candidates, int completed, int failed) {
            this(candidates, completed, failed, 0L);
        }
    }
}
