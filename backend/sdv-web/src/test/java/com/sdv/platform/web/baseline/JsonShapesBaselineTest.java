package com.sdv.platform.web.baseline;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0060 (plan 0059, 1.4): the shapes where a JSON library's changed defaults would show: enum texts, primitives a
 * request may leave null, request records with one component (how they are created), and durations.
 */
class JsonShapesBaselineTest {
    @Test
    void everyEnumIsWrittenAsItsName() throws Exception {
        int seen = 0;
        for (Class<?> c : VeroClasses.all()) {
            if (!c.isEnum()) continue;
            for (Object value : c.getEnumConstants()) {
                assertEquals(((Enum<?>) value).name(), value.toString(), c.getName());
                seen++;
            }
        }
        assertTrue(seen > 100, "enum values seen: " + seen);
    }

    @Test
    void theRequestRecordsThatCreationOrNullCouldTouch() throws Exception {
        Set<String> seen = new TreeSet<>();
        for (Class<?> c : HttpTypes.requestTypes()) {
            assertTrue(c.isRecord() || c.isEnum(), "a request body that is neither a record nor an enum: " + c.getName());
            if (!c.isRecord()) continue;
            RecordComponent[] parts = c.getRecordComponents();
            if (parts.length == 1) seen.add("one component: " + c.getSimpleName() + "(" + parts[0].getName() + ")");
            for (RecordComponent rc : parts) {
                if (rc.getType().isPrimitive()) seen.add("primitive: " + c.getSimpleName() + "." + rc.getName() + " " + rc.getType());
            }
        }
        Baseline.matches("request-shapes", String.join("\n", seen));
    }

    @Test
    void noDurationMonthOptionalOrOldDateIsInARequestOrAnAnswer() throws Exception {
        assertEquals(Set.of(), HttpTypes.places(HttpTypes.handlers(), c -> c == java.time.Duration.class || c == java.time.Period.class
                || c == java.time.Month.class || c == java.util.Optional.class || c == java.util.Date.class));
    }
}
