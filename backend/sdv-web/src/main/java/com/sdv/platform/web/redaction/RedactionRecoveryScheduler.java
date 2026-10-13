package com.sdv.platform.web.redaction;

import com.sdv.platform.application.redaction.CorrectiveRedactionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RedactionRecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(RedactionRecoveryScheduler.class);
    private static final int SWEEP_LIMIT = 20;

    private final CorrectiveRedactionService redaction;

    public RedactionRecoveryScheduler(CorrectiveRedactionService redaction) {
        this.redaction = redaction;
    }

    @Scheduled(
            initialDelayString = "${sdv.redaction.recovery-initial-delay-ms:30000}",
            fixedDelayString = "${sdv.redaction.recovery-sweep-delay-ms:60000}")
    public void recoverStale() {
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
