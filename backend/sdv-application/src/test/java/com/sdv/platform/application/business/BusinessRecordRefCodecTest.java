package com.sdv.platform.application.business;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BusinessRecordRefCodecTest {
    private final BusinessRecordRefCodec codec = new BusinessRecordRefCodec();

    @Test
    void identifierReferenceRoundTripsCanonically() {
        UUID sourceId = UUID.randomUUID();
        var identity = new BusinessRecordRefCodec.RecordIdentity(
                sourceId,
                List.of(
                        new BusinessRecordRefCodec.IdentifierPart("memberId", "M-2"),
                        new BusinessRecordRefCodec.IdentifierPart("contractNumber", "123456")),
                null);

        String token = codec.encode(identity);
        var decoded = codec.decode(token);

        assertEquals(sourceId, decoded.sourceId());
        assertNull(decoded.fallbackSubmissionId());
        assertEquals("contractNumber\u001f123456\u001ememberId\u001fM-2", decoded.canonicalKey());
        assertTrue(token.matches("[A-Za-z0-9_-]+"));
    }

    @Test
    void fallbackSubmissionReferenceRoundTrips() {
        UUID sourceId = UUID.randomUUID();
        UUID submissionId = UUID.randomUUID();
        var identity = new BusinessRecordRefCodec.RecordIdentity(sourceId, List.of(), submissionId);

        var decoded = codec.decode(codec.encode(identity));
        assertEquals(sourceId, decoded.sourceId());
        assertEquals(submissionId, decoded.fallbackSubmissionId());
        assertTrue(decoded.identifiers().isEmpty());
    }

    @Test
    void malformedReferenceFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("not-valid-base64***"));
    }
}
