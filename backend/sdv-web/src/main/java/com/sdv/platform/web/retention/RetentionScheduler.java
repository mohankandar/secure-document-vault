package com.sdv.platform.web.retention;

import com.sdv.platform.application.retention.RetentionDeletionService;
import com.sdv.platform.web.erase.WorkGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Automatic cleanup, once a day at the time in RetentionSchedule (default 02:00 server time). */
@Component
public class RetentionScheduler implements SchedulingConfigurer {
    private static final Logger log = LoggerFactory.getLogger(RetentionScheduler.class);
    private final RetentionDeletionService retention;
    private final RetentionSweepMonitor monitor;
    private final WorkGate gate;
    private final RetentionSchedule schedule;

    public RetentionScheduler(RetentionDeletionService retention, RetentionSweepMonitor monitor, WorkGate gate,
                              RetentionSchedule schedule) {
        this.retention = retention;
        this.monitor = monitor;
        this.gate = gate;
        this.schedule = schedule;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addTriggerTask(this::sweep, schedule.trigger());
        log.info("Retention cleanup scheduled cron=\"{}\" zone={}", schedule.cron(), schedule.zone());
    }

    public void sweep() {
        if (!gate.open()) return; // the database doesn't match this version, or the erase log stops all work
        Instant startedAt = Instant.now();
        monitor.started();
        try {
            RetentionDeletionService.SweepResult result = retention.sweep(100);
            monitor.success(startedAt, Instant.now(), result);
            if (result.failed() > 0) {
                log.warn("Retention sweep completed with failures candidates={} deleted={} failed={} keptOnLegalHold={}", result.candidates(), result.completed(), result.failed(), result.heldSkipped());
            } else if (result.completed() > 0) {
                log.info("Retention sweep completed candidates={} deleted={} failed=0 keptOnLegalHold={}", result.candidates(), result.completed(), result.heldSkipped());
            } else if (result.heldSkipped() > 0) {
                log.info("Retention sweep completed with nothing to delete keptOnLegalHold={}", result.heldSkipped());
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
