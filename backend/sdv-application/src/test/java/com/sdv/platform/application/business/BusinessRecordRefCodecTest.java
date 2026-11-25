package com.sdv.platform.application.business;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BusinessRecordRefCodecTest {
    private final BusinessRecordRefCodec codec = new BusinessRecordRefCodec();

    @Test
    void aReferenceIsTheSendingSystemAndTheRecordsId() {
        var identity = new BusinessRecordRefCodec.RecordIdentity(UUID.randomUUID(), UUID.randomUUID());
        String token = codec.encode(identity);
        assertEquals(identity, codec.decode(token));
        assertTrue(token.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void aReferenceOfBeforeIsNotFoundAndNothingIsGuessed() {
        for (String json : List.of("{\"s\":\"" + UUID.randomUUID() + "\",\"u\":\"" + UUID.randomUUID() + "\"}",
                "{\"s\":\"" + UUID.randomUUID() + "\",\"i\":[{\"k\":\"contractNumber\",\"v\":\"1\"}]}")) {
            String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(RecordRefusals.MESSAGE, assertThrows(java.util.NoSuchElementException.class, () -> codec.decode(token)).getMessage());
        }
    }

    @Test
    void malformedReferenceFailsClosed() {
        var ex = assertThrows(IllegalArgumentException.class, () -> codec.decode("not-valid-base64***"));
        // Task 0043: SDV-SCH-002, with the same sentence as a record that is not found.
        assertEquals("SDV-SCH-002", ((com.sdv.platform.domain.error.CodedRefusal) ex).errorCode().code());
        assertEquals(RecordRefusals.MESSAGE, ex.getMessage());
    }
}
