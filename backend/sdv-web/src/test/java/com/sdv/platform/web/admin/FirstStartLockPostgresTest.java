package com.sdv.platform.web.admin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task 0098, on a real PostgreSQL: the first-start lock holds across connections (as across two Veros of one
 * database), so the second activation runs only after the first has finished.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class FirstStartLockPostgresTest {
    @Test
    void twoActivationsAtOnceRunOneAfterTheOther() throws Exception {
        var ds = new DriverManagerDataSource(System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
        FirstStart.Lock lock = FirstStartController.Wiring.postgresLock(ds);
        List<String> steps = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> runs = new ArrayList<>();
        for (String who : List.of("a", "b")) {
            runs.add(pool.submit(() -> {
                go.await();
                return lock.exclusively(() -> {
                    steps.add(who + " in");
                    try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    steps.add(who + " out");
                    return who;
                });
            }));
        }
        go.countDown();
        for (Future<?> f : runs) f.get();
        pool.shutdown();
        assertEquals(4, steps.size());
        assertEquals(steps.get(0).charAt(0), steps.get(1).charAt(0), "the first finished before the second began: " + steps);
        assertEquals(steps.get(2).charAt(0), steps.get(3).charAt(0), steps.toString());
    }
}
