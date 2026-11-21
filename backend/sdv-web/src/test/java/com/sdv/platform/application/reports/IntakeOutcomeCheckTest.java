package com.sdv.platform.application.reports;

import com.sdv.platform.application.port.IntakeReportRepository.Item;
import com.sdv.platform.domain.error.InternalCheckFailed;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0037, item 6 (IntakeReportService.java:162): a delivery never becomes a document line; it is internal. */
class IntakeOutcomeCheckTest {
    private static Item item(Item.Kind kind) {
        return new Item(kind, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "r", null, null, Instant.now(), null, null);
    }

    @Test
    void aDeliveryLineIsInternal() {
        assertThrows(InternalCheckFailed.class, () -> IntakeReportService.outcome(item(Item.Kind.DELIVERY)));
        assertEquals("REFUSED", IntakeReportService.outcome(item(Item.Kind.REFUSED)));
    }
}
