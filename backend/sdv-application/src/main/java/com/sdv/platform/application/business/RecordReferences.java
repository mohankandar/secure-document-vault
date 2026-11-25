package com.sdv.platform.application.business;

import com.sdv.platform.application.port.BusinessRecordRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0028, 0078: a record (contract, patient file) found three ways: from the reference search hands out, by its ID
 * (record_sensitivity_levels, access_requests), or from one of its submissions. Everything is read within the given tenant.
 */
@Component
public class RecordReferences {
    private final BusinessRecordRepository records;
    private final BusinessRecordRefCodec refs = new BusinessRecordRefCodec();

    public RecordReferences(BusinessRecordRepository records) {
        this.records = records;
    }

    /** A record: its sending system, its ID and its submissions (never empty). */
    public record ResolvedRecord(UUID sourceId, UUID recordId, List<UUID> submissionIds) {
        public ResolvedRecord {
            submissionIds = List.copyOf(submissionIds);
        }
    }

    /** The record behind a reference from search; empty when the reference is malformed or names nothing here. */
    public Optional<ResolvedRecord> fromReference(UUID tenantId, String recordRef) {
        BusinessRecordRefCodec.RecordIdentity identity;
        try {
            identity = refs.decode(recordRef);
        } catch (IllegalArgumentException | NoSuchElementException ex) {
            return Optional.empty();
        }
        return ofRecord(tenantId, identity.sourceId(), identity.recordId());
    }

    /** The record with this ID; empty when none of its submissions is left. */
    public Optional<ResolvedRecord> ofRecord(UUID tenantId, UUID sourceId, UUID recordId) {
        List<UUID> members = records.findSubmissionIdsOfRecord(tenantId, sourceId, recordId);
        return members.isEmpty() ? Optional.empty() : Optional.of(new ResolvedRecord(sourceId, recordId, members));
    }

    /** The record this submission belongs to (it is always one of its submissions). */
    public ResolvedRecord ofSubmission(UUID tenantId, UUID sourceId, UUID submissionId) {
        UUID recordId = records.findRecordIds(tenantId, sourceId, List.of(submissionId)).get(submissionId);
        return (recordId == null ? Optional.<ResolvedRecord>empty() : ofRecord(tenantId, sourceId, recordId))
                .orElseThrow(() -> RecordRefusals.notFound("the submission has no record"));
    }
}
