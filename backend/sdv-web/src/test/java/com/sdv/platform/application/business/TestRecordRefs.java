package com.sdv.platform.application.business;

import com.sdv.platform.application.port.BusinessRecordRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/** Test helper: the record reference search hands out, for a record with one identifier value. */
public final class TestRecordRefs {
    private TestRecordRefs() { }

    /** The test's ID of the record with these identifier values (key, value, key, value ...). */
    public static UUID id(String... keysAndValues) {
        List<RecordKey.Part> parts = new java.util.ArrayList<>();
        for (int i = 0; i < keysAndValues.length; i += 2) parts.add(new RecordKey.Part(keysAndValues[i], keysAndValues[i + 1]));
        return idOf(RecordKey.ofIdentifiers(parts));
    }

    private static UUID idOf(String key) {
        return key.startsWith("submission:") ? UUID.fromString(key.substring(11)) : UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String of(UUID sourceId, String fieldKey, String value) {
        return ref(sourceId, id(fieldKey, value));
    }

    public static String ref(UUID sourceId, UUID recordId) {
        return new BusinessRecordRefCodec().encode(new BusinessRecordRefCodec.RecordIdentity(sourceId, recordId));
    }

    public static BusinessRecordRepository grouped() {
        BusinessRecordRepository records = org.mockito.Mockito.mock(BusinessRecordRepository.class);
        group(records);
        return records;
    }

    /** A mocked repository groups submissions as intake does: by the identifier values its mocked snapshots have, else each on its own. */
    public static void group(BusinessRecordRepository records) {
        when(records.findRecordIds(any(), any(), anyCollection())).thenAnswer(call -> {
            Collection<UUID> asked = call.getArgument(2);
            List<UUID> ids = List.copyOf(asked);
            var snapshots = records.findMetadataSnapshots(call.getArgument(0), call.getArgument(1), ids);
            Map<UUID, UUID> out = new HashMap<>();
            for (UUID id : ids) out.put(id, idOf(RecordKey.forSubmission(id, snapshots == null ? List.of() : snapshots)));
            return out;
        });
    }
}
