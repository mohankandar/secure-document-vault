package com.sdv.platform.web.quarantine;

import com.sdv.platform.application.quarantine.QuarantineService;
import com.sdv.platform.web.schema.DatabaseSchemaCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Task 0051: writes the per-document rows of filter incidents in the background, a bounded number of batches per run.
 * Each batch is its own transaction and the incident keeps its place, so after a restart the job continues where it
 * stopped. The documents are quarantined by the incident's filter all along; the rows are for the review list, the
 * release, the exposure report and the retention cleanup's count.
 */
@Component
public class QuarantinePreparationScheduler {
    private static final Logger log = LoggerFactory.getLogger(QuarantinePreparationScheduler.class);

    private final QuarantineService quarantine;
    private final DatabaseSchemaCheck schema;
    private final int batchesPerRun;

    public QuarantinePreparationScheduler(QuarantineService quarantine, DatabaseSchemaCheck schema,
                                          @org.springframework.beans.factory.annotation.Value(
                                                  "${sdv.quarantine.prepare-batches-per-run:20}") int batchesPerRun) {
        this.quarantine = quarantine;
        this.schema = schema;
        this.batchesPerRun = Math.max(1, batchesPerRun);
    }

    @Scheduled(
            initialDelayString = "${sdv.quarantine.prepare-initial-delay-ms:10000}",
            fixedDelayString = "${sdv.quarantine.prepare-delay-ms:2000}")
    public void prepare() {
        if (!schema.current()) return; // the database doesn't match this version (DatabaseSchemaCheck)
        try {
            int batches = quarantine.prepare(batchesPerRun);
            if (batches > 0) log.debug("Quarantine preparation wrote {} batches", batches);
        } catch (RuntimeException ex) {
            log.warn("Quarantine preparation failed safely; the documents stay quarantined and a later run continues.", ex);
        }
        // Task 0052: approved whole releases, in batches, and the levels of rows written before changeset 039.
        try {
            int batches = quarantine.release(batchesPerRun);
            if (batches > 0) log.debug("Quarantine release released {} batches", batches);
        } catch (RuntimeException ex) {
            log.warn("Quarantine release failed safely; the documents stay quarantined and a later run continues.", ex);
        }
        try {
            quarantine.fillLevels(batchesPerRun);
        } catch (RuntimeException ex) {
            log.warn("Filling in quarantine row levels failed safely; a later run continues.", ex);
        }
    }
}
