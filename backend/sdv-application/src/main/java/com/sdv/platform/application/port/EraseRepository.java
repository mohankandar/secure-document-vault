package com.sdv.platform.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Task 0080: what the erase handlers read and change in Vero's tables. */
public interface EraseRepository {
    boolean audited(UUID auditEventId);

    /** On legal hold, or quarantined. */
    boolean kept(UUID tenantId, UUID documentId);

    /** The file's reference, the data key and a redaction that was under way go; the document's row stays as a tombstone. False: it was one. */
    boolean eraseContent(UUID tenantId, UUID documentId, Instant at, String reason);

    record RecordKey(UUID tenantId, UUID sourceId, UUID recordId) { }

    /** Records with values whose last document was erased. */
    List<RecordKey> findRecordsToErase(int limit);

    /** In a transaction: deliveries to the record wait for its end. True when it still has values and no document. */
    boolean lockRecordToErase(RecordKey record);
}
