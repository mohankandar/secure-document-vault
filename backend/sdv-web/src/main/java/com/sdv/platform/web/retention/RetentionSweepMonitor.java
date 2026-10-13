package com.sdv.platform.web.retention;

import com.sdv.platform.application.retention.RetentionDeletionService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class RetentionSweepMonitor {
    private final AtomicReference<Status> last = new AtomicReference<>(Status.never());

    public void success(Instant startedAt, Instant completedAt, RetentionDeletionService.SweepResult result) {
        last.set(new Status("SUCCESS", startedAt, completedAt, result.candidates(), result.completed(), result.failed(), null));
    }

    public void failure(Instant startedAt, Instant completedAt) {
        last.set(new Status("FAILED", startedAt, completedAt, 0, 0, 1, "RETENTION_SWEEP_FAILED"));
    }

    public Status status() { return last.get(); }

    public record Status(String outcome, Instant startedAt, Instant completedAt, int candidates, int deleted, int failed, String reasonCode) {
        static Status never() { return new Status("NEVER_RUN", null, null, 0, 0, 0, null); }
    }
}
