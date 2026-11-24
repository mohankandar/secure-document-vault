package com.sdv.platform.application.pdf;

import com.sdv.platform.domain.error.BusyFailure;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Task 0063: everything that reads a PDF runs here, so one file or one person can't take the whole server. At most
 * {@code maxConcurrent} at a time in this process and {@code maxPerPerson} for one person (or sending system); the
 * others wait a little and are then told Vero is busy (SDV-SYS-018). A work that takes too long is given up: the caller
 * gets an answer, and its place is free again only when it has really ended, so a file that never ends can't multiply.
 * Out of memory ends that one work, not Vero.
 */
@Component
public class PdfWork implements DisposableBean {
    /** What one work is counted as: a page image at the largest zoom, the PDF's objects and the scratch space in memory. */
    static final long ASSUMED_BYTES = 320L * 1024 * 1024;
    static final int MAX_WAITING = 64;
    static final int MAX_WAITING_PER_PERSON = 16;

    private final Semaphore places;
    private final int maxPerPerson;
    private final Duration wait;
    private final Duration renderLimit;
    private final Path scratchFolder;
    private final long scratchMemory;
    private final long scratchTotal;
    private final ExecutorService threads = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "sdv-pdf-work");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger waiting = new AtomicInteger();
    private final Map<String, Person> people = new HashMap<>();

    private static final class Person {
        final Semaphore places;
        int using;

        Person(int max) {
            places = new Semaphore(max, true);
        }
    }

    /** The PDF took longer than Vero allows. */
    public static final class TookTooLong extends IOException {
        public TookTooLong() {
            super("The PDF took longer than Vero allows.");
        }
    }

    /** The PDF can't be worked on (nested too deep). */
    public static final class NotWorkable extends IOException {
        NotWorkable(Throwable cause) {
            super("The PDF can't be worked on.", cause);
        }
    }

    @Autowired
    public PdfWork(@Value("${sdv.pdf.max-concurrent:0}") int maxConcurrent,
                   @Value("${sdv.pdf.max-per-person:3}") int maxPerPerson,
                   @Value("${sdv.pdf.wait-seconds:8}") int waitSeconds,
                   @Value("${sdv.pdf.limit-seconds:30}") int limitSeconds,
                   @Value("${sdv.pdf.scratch-folder:}") String scratchFolder,
                   @Value("${sdv.pdf.scratch-memory-bytes:16777216}") long scratchMemory,
                   @Value("${sdv.pdf.scratch-total-bytes:2147483648}") long scratchTotal) {
        this(maxConcurrent == 0 ? defaultConcurrent(Runtime.getRuntime().maxMemory(), Runtime.getRuntime().availableProcessors()) : maxConcurrent,
                maxPerPerson, Duration.ofSeconds(checked(waitSeconds, 0, 60, "SDV_PDF_WAIT_SECONDS")),
                Duration.ofSeconds(checked(limitSeconds, 5, 600, "SDV_PDF_LIMIT_SECONDS")), folder(scratchFolder), scratchMemory, scratchTotal);
    }

    public PdfWork(int maxConcurrent, int maxPerPerson, Duration wait, Duration renderLimit, Path scratchFolder, long scratchMemory, long scratchTotal) {
        this.places = new Semaphore(checked(maxConcurrent, 1, 256, "SDV_PDF_MAX_CONCURRENT"), true);
        this.maxPerPerson = checked(maxPerPerson, 1, 64, "SDV_PDF_MAX_PER_PERSON");
        this.wait = wait;
        this.renderLimit = renderLimit;
        this.scratchFolder = scratchFolder;
        this.scratchMemory = scratchMemory;
        this.scratchTotal = scratchTotal;
    }

    /** The default settings, without a Spring context (tests). */
    public static PdfWork defaults() {
        return new PdfWork(4, 3, Duration.ofSeconds(8), Duration.ofSeconds(30), null, 16L * 1024 * 1024, 2L * 1024 * 1024 * 1024);
    }

    static int defaultConcurrent(long heapBytes, int processors) {
        return (int) Math.max(1, Math.min(Math.max(2, processors), heapBytes / ASSUMED_BYTES));
    }

    private static int checked(int value, int min, int max, String setting) {
        if (value < min || value > max) {
            throw new StartupFailure(SdvErrorCode.SYS_005, setting + " must be from " + min + " to " + max + ".");
        }
        return value;
    }

    private static Path folder(String value) {
        if (value == null || value.isBlank()) return null;
        Path path = Path.of(value.trim());
        if (!Files.isDirectory(path)) throw new StartupFailure(SdvErrorCode.SYS_005, "SDV_PDF_SCRATCH_FOLDER is not a folder.");
        return path;
    }

    public EncryptedScratch scratch() {
        return new EncryptedScratch(scratchFolder, scratchMemory, scratchTotal);
    }

    public Duration renderLimit() {
        return renderLimit;
    }

    /** Runs {@code work} in a place of its own, for at most the render limit. */
    public <T> T run(String who, Callable<T> work) throws IOException {
        return run(who, renderLimit, work);
    }

    /**
     * @param who whom the work is for: the person or sending system, with the entity
     * @throws BusyFailure when no place came free in time, or memory ran out
     * @throws TookTooLong when it takes longer than {@code limit}
     */
    public <T> T run(String who, Duration limit, Callable<T> work) throws IOException {
        Person person = enter(who);
        boolean personPlace = false;
        boolean place = false;
        CompletableFuture<T> future = new CompletableFuture<>();
        AtomicReference<Thread> runner = new AtomicReference<>();
        long until = System.nanoTime() + wait.toNanos();
        try {
            if (person.places.getQueueLength() >= MAX_WAITING_PER_PERSON) throw new BusyFailure("too many PDF requests of this person are waiting");
            waiting.incrementAndGet();
            try {
                if (waiting.get() > MAX_WAITING) throw new BusyFailure("too many PDF requests are waiting");
                personPlace = person.places.tryAcquire(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS);
                place = personPlace && places.tryAcquire(Math.max(0, until - System.nanoTime()), TimeUnit.NANOSECONDS);
            } finally {
                waiting.decrementAndGet();
            }
            if (!place) throw new BusyFailure(personPlace ? "every PDF place is taken" : "this person's PDF places are taken");
            threads.execute(() -> {
                runner.set(Thread.currentThread());
                T value = null;
                Throwable failure = null;
                try {
                    value = work.call();
                } catch (OutOfMemoryError error) {
                    failure = new BusyFailure("a PDF needed more memory than there is");
                } catch (StackOverflowError error) {
                    failure = new NotWorkable(error);
                } catch (Throwable error) {
                    failure = error;
                } finally {
                    runner.set(null);
                    places.release();
                    person.places.release();
                    leave(who, person);
                }
                if (failure == null) future.complete(value);
                else future.completeExceptionally(failure);
            });
            // From here the work itself gives its places back, when it has really ended.
        } catch (InterruptedException ex) {
            give(who, person, personPlace, place);
            Thread.currentThread().interrupt();
            throw new BusyFailure("interrupted");
        } catch (RuntimeException | Error ex) {
            give(who, person, personPlace, place);
            throw ex;
        }
        try {
            return future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            interrupt(runner);
            throw new TookTooLong();
        } catch (InterruptedException ex) {
            interrupt(runner);
            Thread.currentThread().interrupt();
            throw new BusyFailure("interrupted");
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IOException(cause);
        }
    }

    /** Asks the work to stop; one that doesn't listen keeps its place until it ends. */
    private static void interrupt(AtomicReference<Thread> runner) {
        Thread thread = runner.get();
        if (thread != null) thread.interrupt();
    }

    private Person enter(String who) {
        synchronized (people) {
            Person person = people.computeIfAbsent(who, w -> new Person(maxPerPerson));
            person.using++;
            return person;
        }
    }

    private void leave(String who, Person person) {
        synchronized (people) {
            if (--person.using == 0) people.remove(who);
        }
    }

    private void give(String who, Person person, boolean personPlace, boolean place) {
        if (place) places.release();
        if (personPlace) person.places.release();
        leave(who, person);
    }

    /** People with work now (tests): nothing is kept for anyone else. */
    int peopleNow() {
        synchronized (people) {
            return people.size();
        }
    }

    @Override
    public void destroy() {
        threads.shutdownNow();
    }
}
