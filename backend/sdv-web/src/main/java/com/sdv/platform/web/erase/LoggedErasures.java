package com.sdv.platform.web.erase;

import com.documentplatform.eraselog.EraseLogException;
import com.documentplatform.eraselog.EraseRequest;
import com.documentplatform.eraselog.ErasedBy;
import com.documentplatform.eraselog.Eraser;
import com.documentplatform.eraselog.fields.CaseFieldsEraseHandler;
import com.sdv.platform.application.port.ErasePort;
import com.sdv.platform.domain.identity.VeroProduct;
import com.sdv.platform.persistence.fields.CaseFieldSql;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Task 0080: Vero's erasures through platform-core's {@link Eraser}. Always outside a transaction of Vero's: an erasure that is logged and
 * done must not be undone by what its caller does afterwards. The log names a rule; the audit event it names says who asked.
 */
@Component
public class LoggedErasures implements ErasePort {
    private final Eraser eraser;
    private final TransactionTemplate outside;
    private final ReentrantLock turn = new ReentrantLock(true);
    private final Clock clock = Clock.systemUTC();

    public LoggedErasures(Eraser eraser, PlatformTransactionManager transactions) {
        this.eraser = eraser;
        this.outside = new TransactionTemplate(transactions);
        this.outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }

    @Override
    public void document(UUID tenantId, UUID documentId, UUID auditId) {
        erase(tenantId, EraseIds.DOCUMENT, documentId.toString(), EraseIds.DOCUMENT, "retention", auditId);
    }

    @Override
    public void sealedOriginal(UUID tenantId, UUID documentId, UUID sealedId, UUID auditId) {
        erase(tenantId, EraseIds.SEALED_ORIGINAL, EraseIds.sealed(documentId, sealedId), EraseIds.SEALED_ORIGINAL, "legal-hold-released", auditId);
    }

    @Override
    public void replacedVersion(UUID tenantId, UUID documentId, int version, UUID auditId) {
        erase(tenantId, EraseIds.REPLACED_VERSION, EraseIds.version(documentId, version), EraseIds.REPLACED_VERSION, "corrective-redaction", auditId);
    }

    @Override
    public void caseFields(UUID tenantId, UUID sourceId, UUID recordId, UUID auditId) {
        erase(tenantId, CaseFieldSql.recordType(sourceId), recordId.toString(), CaseFieldsEraseHandler.KIND, "last-document-erased", auditId);
    }

    private void erase(UUID tenantId, String recordType, String recordId, String kind, String rule, UUID auditId) {
        EraseRequest request = new EraseRequest(tenantId, VeroProduct.CODE, recordType, recordId, kind,
                clock.instant().truncatedTo(ChronoUnit.MICROS), ErasedBy.rule(rule), auditId.toString());
        inTurn(() -> {
            try {
                outside.executeWithoutResult(status -> eraser.erase(request));
            } catch (EraseLogException ex) {
                throw new EraseFailed(EraseIds.shown(ex.handlerCode().orElse(ex.code().code())), ex);
            }
            return null;
        });
    }

    @Override
    public <T> T inTurn(Supplier<T> work) {
        turn.lock();
        try {
            return work.get();
        } finally {
            turn.unlock();
        }
    }
}
