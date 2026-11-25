package com.sdv.platform.application.business;

import com.sdv.platform.domain.error.InternalCheckFailed;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0037, item 6, as task 0078 left it: a submission without identifier values is named by itself; a reference without a record is refused. */
class RecordKeyCheckTest {
    @Test
    void aRecordWithNothingToNameItByIsRefused() {
        assertThrows(InternalCheckFailed.class, () -> new BusinessRecordRefCodec().encode(new BusinessRecordRefCodec.RecordIdentity(UUID.randomUUID(), null)));
    }
}
