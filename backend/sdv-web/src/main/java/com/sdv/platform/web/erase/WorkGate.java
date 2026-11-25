package com.sdv.platform.web.erase;

import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.eraselog.EraseLogError;
import com.sdv.platform.web.schema.DatabaseSchemaCheck;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Task 0080: may Vero take work? The database matches this version and the erase log doesn't stop work. The scheduled jobs ask here;
 * requests are stopped by {@link EraseGateFilter}. While the log stops work it is asked again at once (in the background, at most every
 * {@link #AGAIN_MS}), not only at platform-core's recheck, so Vero is ready again soon after the cause is gone.
 */
@Component
public class WorkGate {
    static final long AGAIN_MS = 2_000;

    private final DatabaseSchemaCheck schema;
    private final EraseGuard guard;
    private final AtomicBoolean asking = new AtomicBoolean();
    private volatile long asked;
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sdv-erase-log-recheck");
        t.setDaemon(true);
        return t;
    });

    public WorkGate(DatabaseSchemaCheck schema, EraseGuard guard) {
        this.schema = schema;
        this.guard = guard;
    }

    public boolean open() {
        return schema.current() && eraseLogAllows();
    }

    boolean eraseLogAllows() {
        if (guard.mayTakeWork()) return true;
        long now = System.nanoTime() / 1_000_000;
        if ((asked == 0 || now - asked >= AGAIN_MS) && asking.compareAndSet(false, true)) {
            asked = now;
            background.execute(() -> {
                try {
                    guard.refresh();
                } catch (RuntimeException ignored) {
                    // the state stays as it is
                } finally {
                    asking.set(false);
                }
            });
        }
        return false;
    }

    /** The log's code; null when it doesn't stop work. */
    EraseLogError code() {
        EraseGuard.State state = guard.state();
        return state.mayTakeWork() ? null : state.code();
    }

    @PreDestroy
    void stop() {
        background.shutdownNow();
    }
}
