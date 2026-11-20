package com.sdv.platform.application.business;

import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.IdentifierCriterion;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0028: a record (contract, patient file) found three ways, always with the same grouping as search and record
 * detail: from the reference search hands out, from a stored record key (record_sensitivity_levels, access_requests),
 * or from one of its submissions. Everything is read within the given tenant.
 */
@Component
public class RecordReferences {
    private final BusinessRecordRepository records;
    private final BusinessRecordRefCodec refs = new BusinessRecordRefCodec();

    public RecordReferences(BusinessRecordRepository records) {
        this.records = records;
    }

    /** A record: its sending system, its key and its submissions (never empty). */
    public record ResolvedRecord(UUID sourceId, String recordKey, List<UUID> submissionIds) {
        public ResolvedRecord {
            submissionIds = List.copyOf(submissionIds);
        }
    }

    /** The record behind a reference from search; empty when the reference is malformed or names nothing here. */
    public Optional<ResolvedRecord> fromReference(UUID tenantId, String recordRef) {
        BusinessRecordRefCodec.RecordIdentity identity;
        try {
            identity = refs.decode(recordRef);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        List<UUID> members = members(tenantId, identity.sourceId(), identity.fallbackSubmissionId(),
                identity.identifiers().stream().map(p -> new RecordKey.Part(p.fieldKey(), p.normalizedValue())).toList());
        return members.isEmpty() ? Optional.empty()
                : Optional.of(new ResolvedRecord(identity.sourceId(), identity.recordKey(), members));
    }

    /** The record with this stored key; empty when none of its submissions is left. */
    public Optional<ResolvedRecord> fromKey(UUID tenantId, UUID sourceId, String recordKey) {
        RecordKey.Parsed parsed = RecordKey.parse(recordKey);
        List<UUID> members = members(tenantId, sourceId, parsed.submissionId(), parsed.identifiers());
        return members.isEmpty() ? Optional.empty() : Optional.of(new ResolvedRecord(sourceId, recordKey, members));
    }

    /** The record this submission belongs to (it is always one of its submissions). */
    public ResolvedRecord ofSubmission(UUID tenantId, UUID sourceId, UUID submissionId) {
        String key = RecordKey.forSubmission(submissionId,
                records.findMetadataSnapshots(tenantId, sourceId, List.of(submissionId)));
        RecordKey.Parsed parsed = RecordKey.parse(key);
        List<UUID> members = parsed.submissionId() != null ? List.of(submissionId)
                : records.findSubmissionIdsByRecordIdentity(tenantId, sourceId, criteria(parsed.identifiers()));
        if (!members.contains(submissionId)) {
            members = new java.util.ArrayList<>(members);
            members.add(submissionId);
        }
        return new ResolvedRecord(sourceId, key, members);
    }

    private List<UUID> members(UUID tenantId, UUID sourceId, UUID fallbackSubmission, List<RecordKey.Part> identifiers) {
        if (fallbackSubmission != null) {
            return records.findSubmissionsByIds(tenantId, sourceId, List.of(fallbackSubmission)).isEmpty()
                    ? List.of() : List.of(fallbackSubmission);
        }
        return records.findSubmissionIdsByRecordIdentity(tenantId, sourceId, criteria(identifiers));
    }

    private static List<IdentifierCriterion> criteria(List<RecordKey.Part> parts) {
        return parts.stream().map(p -> new IdentifierCriterion(p.fieldKey(), p.normalizedValue())).toList();
    }
}
