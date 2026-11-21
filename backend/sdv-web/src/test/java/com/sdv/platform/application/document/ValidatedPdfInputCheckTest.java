package com.sdv.platform.application.document;

import com.sdv.platform.domain.error.InternalCheckFailed;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** Task 0037, item 6: a missing PDF stream is an internal check (SDV-SYS-016), not the sender's mistake. */
class ValidatedPdfInputCheckTest {
    @Test
    void aMissingStreamIsInternal() {
        assertThrows(InternalCheckFailed.class, () -> new ValidatedPdfInputStream(null, 10));
    }
}
