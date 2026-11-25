package com.sdv.platform.web.reports;

import com.sdv.platform.application.reports.StorageReportService;
import com.sdv.platform.web.erase.WorkGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Task 0053: works out the Storage and retention report's counts in the background, every 15 minutes by default, so
 * no screen load reads the documents. A failed run keeps the last counts; the next run tries again.
 */
@Component
public class StorageReportScheduler {
    private static final Logger log = LoggerFactory.getLogger(StorageReportScheduler.class);

    private final StorageReportService storage;
    private final WorkGate gate;

    public StorageReportScheduler(StorageReportService storage, WorkGate gate) {
        this.storage = storage;
        this.gate = gate;
    }

    @Scheduled(
            initialDelayString = "${sdv.reports.storage.count-initial-delay-ms:30000}",
            fixedDelayString = "${sdv.reports.storage.count-delay-ms:900000}")
    public void count() {
        if (!gate.open()) return; // the database doesn't match this version, or the erase log stops all work
        try {
            int counted = storage.countAll();
            log.debug("Storage report counted for {} entities", counted);
        } catch (RuntimeException ex) {
            log.warn("The storage report count failed safely; the last counts are kept and a later run tries again.", ex);
        }
    }
}
