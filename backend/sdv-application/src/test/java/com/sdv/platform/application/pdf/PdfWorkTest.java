package com.sdv.platform.application.pdf;

import com.sdv.platform.domain.error.BusyFailure;
import com.sdv.platform.domain.error.StartupFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0063: places for PDF work, and its encrypted working space. */
class PdfWorkTest {
    @TempDir Path folder;

    private static PdfWork work(int all, int each, Duration wait, Duration limit) {
        return new PdfWork(all, each, wait, limit, null, 1024, 1024 * 1024);
    }

    private static Thread holding(PdfWork work, String who, CountDownLatch started, CountDownLatch release) {
        Thread t = new Thread(() -> {
            try {
                work.run(who, () -> {
                    started.countDown();
                    release.await();
                    return 1;
                });
            } catch (Exception ignored) {
                // the test looks at the others
            }
        });
        t.start();
        return t;
    }

    @Test
    void onePersonCantTakeEveryPlaceAndTheOthersAreToldBusyWhenThereIsNone() throws Exception {
        PdfWork work = work(2, 1, Duration.ofMillis(200), Duration.ofSeconds(30));
        CountDownLatch first = new CountDownLatch(1), second = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread a = holding(work, "a", first, release);
        assertTrue(first.await(5, TimeUnit.SECONDS));
        assertThrows(BusyFailure.class, () -> work.run("a", () -> 1), "a's own place is taken");
        assertEquals(1, work.run("b", () -> 1), "another person still works");
        Thread b = holding(work, "b", second, release);
        assertTrue(second.await(5, TimeUnit.SECONDS));
        assertThrows(BusyFailure.class, () -> work.run("c", () -> 1), "every place is taken");
        release.countDown();
        a.join(5000);
        b.join(5000);
        assertEquals(1, work.run("c", () -> 1));
        assertEquals(0, work.peopleNow(), "nothing is kept for anyone who has no work");
    }

    @Test
    void aPlaceIsWaitedForAShortTime() throws Exception {
        PdfWork work = work(1, 1, Duration.ofSeconds(10), Duration.ofSeconds(30));
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        Thread a = holding(work, "a", started, release);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) { }
            release.countDown();
        }).start();
        assertEquals(7, work.run("b", () -> 7));
        a.join(5000);
    }

    @Test
    void aWorkThatTakesTooLongIsGivenUpAndKeepsItsPlaceUntilItEnds() throws Exception {
        PdfWork work = work(1, 1, Duration.ofMillis(100), Duration.ofMillis(200));
        CountDownLatch release = new CountDownLatch(1);
        assertThrows(PdfWork.TookTooLong.class, () -> work.run("a", () -> {
            while (release.getCount() > 0) Thread.onSpinWait(); // never listens
            return 1;
        }));
        assertThrows(BusyFailure.class, () -> work.run("b", () -> 1), "it still runs, so its place isn't free");
        release.countDown();
        for (int i = 0; i < 100 && work.peopleNow() > 0; i++) Thread.sleep(20);
        assertEquals(1, work.run("b", () -> 1));
    }

    @Test
    void outOfMemoryOrTooDeepEndsOnlyThatWork() throws Exception {
        PdfWork work = work(1, 1, Duration.ofSeconds(1), Duration.ofSeconds(10));
        assertThrows(BusyFailure.class, () -> work.run("a", () -> {
            throw new OutOfMemoryError("test");
        }));
        assertThrows(PdfWork.NotWorkable.class, () -> work.run("a", () -> deep(0)));
        assertThrows(IllegalStateException.class, () -> work.run("a", () -> {
            throw new IllegalStateException("as it is");
        }));
        assertEquals(1, work.run("a", () -> 1), "the place came back each time");
    }

    private static int deep(int n) {
        return deep(n + 1) + 1;
    }

    @Test
    void theDefaultFollowsMemoryAndProcessorsAndABadSettingStopsTheStart() {
        assertEquals(1, PdfWork.defaultConcurrent(256L << 20, 8));
        assertEquals(6, PdfWork.defaultConcurrent(2L << 30, 16));
        assertEquals(8, PdfWork.defaultConcurrent(32L << 30, 8));
        assertThrows(StartupFailure.class, () -> work(0, 1, Duration.ZERO, Duration.ZERO));
        assertThrows(StartupFailure.class, () -> work(1, 0, Duration.ZERO, Duration.ZERO));
    }

    @Test
    void theWorkingSpaceGivesBackWhatWasWrittenAndItsFileHoldsNoPlaintext() throws Exception {
        byte[] marker = "SECRET-CONTENT-".repeat(8).getBytes(StandardCharsets.US_ASCII);
        byte[] data = new byte[300_000];
        for (int i = 0; i < data.length; i++) data[i] = marker[i % marker.length];
        Path file;
        try (EncryptedScratch scratch = new EncryptedScratch(folder, 2L * EncryptedScratch.PAGE, 10_000_000)) {
            EncryptedScratch.Buffer a = scratch.createBuffer();
            EncryptedScratch.Buffer b = scratch.createBuffer();
            a.write(data);
            b.write(data, 1000, 50_000);
            byte[] back = new byte[data.length];
            a.seek(0);
            a.readFully(back);
            assertArrayEquals(data, back);
            assertTrue(scratch.slotsUsed() > 10, "most of it is in the file");
            file = scratch.file();
            String onDisk = new String(scratch.fileBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(onDisk.length() > 100_000);
            assertFalse(onDisk.contains("SECRET"), "only ciphertext");
            // Random reads and writes agree with a plain array.
            Random random = new Random(63);
            for (int i = 0; i < 500; i++) {
                int at = random.nextInt(data.length - 5000), n = 1 + random.nextInt(4000);
                byte[] part = new byte[n];
                if (random.nextBoolean()) {
                    random.nextBytes(part);
                    System.arraycopy(part, 0, data, at, n);
                    a.seek(at);
                    a.write(part);
                } else {
                    a.seek(at);
                    a.readFully(part);
                    assertArrayEquals(Arrays.copyOfRange(data, at, at + n), part);
                }
            }
            assertEquals(data.length, a.length());
            a.seek(a.length());
            assertEquals(-1, a.read());
            assertTrue(a.isEOF());
            b.close();
            assertThrows(java.io.IOException.class, b::read);
        }
        assertFalse(Files.exists(file), "the file is gone with the working space");
    }

    @Test
    void writingAPageAgainNeverUsesTheSameKeyStream() throws Exception {
        try (EncryptedScratch scratch = new EncryptedScratch(folder, 0, 10_000_000)) {
            EncryptedScratch.Buffer a = scratch.createBuffer();
            a.write(new byte[EncryptedScratch.PAGE]);
            a.write(new byte[EncryptedScratch.PAGE]); // puts the first page in the file
            byte[] first = Arrays.copyOf(scratch.fileBytes(), EncryptedScratch.PAGE);
            a.seek(0);
            a.write(0);
            a.seek(EncryptedScratch.PAGE);
            a.read(); // puts the same content of the first page there again
            byte[] again = Arrays.copyOf(scratch.fileBytes(), EncryptedScratch.PAGE);
            assertFalse(Arrays.equals(first, again), "the same content, another key stream");
        }
    }

    @Test
    void theWorkingSpaceHasALimit() throws Exception {
        try (EncryptedScratch scratch = new EncryptedScratch(folder, 0, 4L * EncryptedScratch.PAGE)) {
            EncryptedScratch.Buffer a = scratch.createBuffer();
            assertThrows(java.io.IOException.class, () -> a.write(new byte[10 * EncryptedScratch.PAGE]));
        }
    }
}
