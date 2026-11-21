package com.sdv.platform.application.business;

import java.util.List;
import java.util.UUID;

/** Test helper: the record reference search hands out, for a record with one identifier value. */
public final class TestRecordRefs {
    private TestRecordRefs() { }

    public static String of(UUID sourceId, String fieldKey, String normalizedValue) {
        return new BusinessRecordRefCodec().encode(new BusinessRecordRefCodec.RecordIdentity(sourceId,
                List.of(new BusinessRecordRefCodec.IdentifierPart(fieldKey, normalizedValue)), null));
    }

    public static String key(String fieldKey, String normalizedValue) {
        return RecordKey.ofIdentifiers(List.of(new RecordKey.Part(fieldKey, normalizedValue)));
    }
}
