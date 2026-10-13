package com.sdv.platform.web.retention;

import com.sdv.platform.application.retention.RetentionDeletionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class RetentionScheduler {
    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);
    private final RetentionDeletionService retention;
    private final RetentionSweepMonitor monitor;

    public RetentionScheduler(RetentionDeletionService retention, RetentionSweepMonitor monitor) {
        this.retention = retention;
        this.monitor = monitor;
    }

    @Scheduled(
            initialDelayString = "${sdv.retention.initial-delay-ms:15000}",
            fixedDelayString = "${sdv.retention.sweep-delay-ms:60000}")
    public void sweep() {
        Instant startedAt = Instant.now();
        try {
            RetentionDeletionService.SweepResult result = retention.sweep(100);
            monitor.success(startedAt, Instant.now(), result);
            if (result.failed() > 0) {
                log.warn("Retention sweep completed with failures candidates={} deleted={} failed={}", result.candidates(), result.completed(), result.failed());
            } else if (result.completed() > 0) {
                log.info("Retention sweep completed candidates={} deleted={} failed=0", result.candidates(), result.completed());
            } else {
                log.debug("Retention sweep completed with no due documents");
            }
        } catch (RuntimeException ex) {
            monitor.failure(startedAt, Instant.now());
            log.warn("Retention sweep failed reasonCode=RETENTION_SWEEP_FAILED");
            throw ex;
        }
    }
}
