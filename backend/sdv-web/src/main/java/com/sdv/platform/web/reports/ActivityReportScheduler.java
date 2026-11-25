package com.sdv.platform.web.reports;

import com.sdv.platform.application.reports.ActivityReportService;
import com.sdv.platform.web.erase.WorkGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Task 0054: counts each closed day of the audit trail once, in the background, for the Activity report (at most a few
 * days a run, newest first), so a report reads kept counts and only today from the audit trail.
 */
@Component
public class ActivityReportScheduler {
    private static final Logger log = LoggerFactory.getLogger(ActivityReportScheduler.class);

    private final ActivityReportService activity;
    private final WorkGate gate;
    private final int daysPerRun;

    public ActivityReportScheduler(ActivityReportService activity, WorkGate gate,
                                   @org.springframework.beans.factory.annotation.Value(
                                           "${sdv.reports.activity.days-per-run:14}") int daysPerRun) {
        this.activity = activity;
        this.gate = gate;
        this.daysPerRun = Math.max(1, daysPerRun);
    }

    @Scheduled(
            initialDelayString = "${sdv.reports.activity.count-initial-delay-ms:45000}",
            fixedDelayString = "${sdv.reports.activity.count-delay-ms:600000}")
    public void keep() {
        if (!gate.open()) return; // the database doesn't match this version, or the erase log stops all work
        try {
            int days = activity.keepClosedDays(daysPerRun);
            if (days > 0) log.debug("Activity report kept {} days", days);
        } catch (RuntimeException ex) {
            log.warn("Keeping the activity report's days failed safely; a report counts what is missing, and a later run tries again.", ex);
        }
    }
}
