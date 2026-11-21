package com.sdv.platform.application.business;

import com.sdv.platform.domain.error.InternalCheckFailed;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0037, item 6: a record with no identifier and no submission used to give 404 "No value present". */
class RecordKeyCheckTest {
    @Test
    void aRecordWithNothingToNameItByIsInternal() {
        var nothing = new BusinessRecordRefCodec.RecordIdentity(UUID.randomUUID(), List.of(), null);
        assertThrows(InternalCheckFailed.class, nothing::canonicalKey);
        var submission = UUID.randomUUID();
        assertEquals("submission:" + submission, new BusinessRecordRefCodec.RecordIdentity(UUID.randomUUID(), List.of(), submission).canonicalKey());
    }
}
