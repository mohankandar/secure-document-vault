package com.sdv.platform.web.redaction;

import com.sdv.platform.application.redaction.CorrectiveRedactionService;
import com.sdv.platform.application.redaction.SealedOriginalService;
import com.sdv.platform.web.erase.WorkGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RedactionRecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(RedactionRecoveryScheduler.class);
    private static final int SWEEP_LIMIT = 20;

    private final CorrectiveRedactionService redaction;
    private final WorkGate gate;
    private final SealedOriginalService sealed;

    public RedactionRecoveryScheduler(CorrectiveRedactionService redaction, SealedOriginalService sealed, WorkGate gate) {
        this.redaction = redaction;
        this.sealed = sealed;
        this.gate = gate;
    }

    /** Task 0066: approved redactions of held documents, and originals whose last hold was released. */
    @Scheduled(
            initialDelayString = "${sdv.redaction.held-initial-delay-ms:20000}",
            fixedDelayString = "${sdv.redaction.held-sweep-delay-ms:10000}")
    public void heldDocuments() {
        if (!gate.open()) return;
        try {
            CorrectiveRedactionService.ApplySweepResult result = redaction.applyApproved(SWEEP_LIMIT);
            if (result.taken() > 0) {
                log.info("Approved redactions of held documents taken={} applied={} failed={}", result.taken(), result.applied(), result.failed());
            }
        } catch (RuntimeException ex) {
            log.warn("Approved redactions of held documents could not be applied; a later sweep will retry.", ex);
        }
        try {
            SealedOriginalService.DestroySweepResult result = sealed.destroyDue(SWEEP_LIMIT);
            if (result.destroyed() > 0 || result.failed() > 0) {
                log.info("Sealed originals destroyed={} failed={}", result.destroyed(), result.failed());
            }
        } catch (RuntimeException ex) {
            log.warn("Sealed originals could not be destroyed; a later sweep will retry.", ex);
        }
    }

    @Scheduled(
            initialDelayString = "${sdv.redaction.recovery-initial-delay-ms:30000}",
            fixedDelayString = "${sdv.redaction.recovery-sweep-delay-ms:60000}")
    public void recoverStale() {
        if (!gate.open()) return; // the database doesn't match this version, or the erase log stops all work
        try {
            CorrectiveRedactionService.RecoverySweepResult result = redaction.recoverStale(SWEEP_LIMIT);
            if (result.completed() > 0 || result.failed() > 0) {
                log.info("Corrective-redaction recovery sweep candidates={} completed={} failed={}",
                        result.candidates(), result.completed(), result.failed());
            }
        } catch (RuntimeException ex) {
            log.warn("Corrective-redaction recovery sweep failed safely; a later bounded sweep will retry.", ex);
        }
    }
}
