package com.sdv.platform.application.access;

import com.sdv.platform.application.business.RecordKey;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.MetadataSnapshot;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Task 0026: the one server-side check every document path runs before it returns, streams, prints, changes or
 * describes a document. It compares the document's effective level (the higher of its own level and its record's
 * level, {@link SensitivityLevel#effective}) with the person's {@link Clearance}.
 * <p>
 * A refusal says nothing about the document (code and a fixed message only) and is audited as
 * {@link #REFUSED_ACTION} with who, when, the document ID, its effective level and what was tried.
 */
@Service
public class DocumentAccessGuard {
    public static final String REFUSED_ACTION = "DOCUMENT_ACCESS_REFUSED";
    public static final String RESTRICTED_MESSAGE = "This document is restricted.";
    public static final String APPROVAL_NEEDED_MESSAGE =
            "Printing or downloading a Highly Confidential document needs an approval, which isn't available yet.";

    /** What the person tried; written in the refusal's audit event. */
    public enum Attempt { OPEN, PAGE, PREVIEWS, TEXT_SEARCH, TAGS, ADD_TAG, COMMENTS, ADD_COMMENT, ACTIVITY, PRINT, DOWNLOAD, REDACT }

    private final BusinessRecordRepository records;
    private final AuditEventPort audit;
    private final Clock clock;

    @Autowired
    public DocumentAccessGuard(BusinessRecordRepository records, AuditEventPort audit) {
        this(records, audit, Clock.systemUTC());
    }

    public DocumentAccessGuard(BusinessRecordRepository records, AuditEventPort audit, Clock clock) {
        this.records = records;
        this.audit = audit;
        this.clock = clock;
    }

    /** The document's effective level: its own, or its record's when that is higher. */
    public SensitivityLevel effectiveLevel(Document document) {
        List<MetadataSnapshot> metadata = records.findMetadataSnapshots(
                document.tenantId(), document.sourceId(), List.of(document.submissionId()));
        String key = RecordKey.forSubmission(document.submissionId(), metadata);
        SensitivityLevel recordLevel = records.findRecordSensitivityLevel(
                document.tenantId(), document.sourceId(), key).orElse(null);
        return SensitivityLevel.effective(document.sensitivityLevel(), recordLevel);
    }

    /**
     * Refuses (403, SDV-SEN-008) and audits when the person can't open the document at its effective level.
     * Returns that level otherwise.
     */
    public SensitivityLevel requireOpenable(Document document, Clearance clearance, Attempt attempt,
                                            String actorId, String correlationId) {
        Objects.requireNonNull(clearance, "clearance");
        SensitivityLevel level = effectiveLevel(document);
        if (!clearance.canOpen(level)) {
            refuse(document, level, attempt, SdvErrorCode.SEN_008, actorId, correlationId);
            throw new CodedRefusal.Forbidden(SdvErrorCode.SEN_008, RESTRICTED_MESSAGE);
        }
        return level;
    }

    /**
     * Print and download: the document must be openable, and in this version a Highly Confidential one is refused
     * for everyone (403, SDV-SEN-009) until approvals exist (task 0028). Returns the effective level, which the
     * caller writes in its own audit event.
     */
    public SensitivityLevel requirePrintOrDownload(Document document, Clearance clearance, Attempt attempt,
                                                   String actorId, String correlationId) {
        if (attempt != Attempt.PRINT && attempt != Attempt.DOWNLOAD) {
            throw new IllegalArgumentException("Only print and download need this check.");
        }
        SensitivityLevel level = requireOpenable(document, clearance, attempt, actorId, correlationId);
        if (level == SensitivityLevel.HIGHLY_CONFIDENTIAL) {
            refuse(document, level, attempt, SdvErrorCode.SEN_009, actorId, correlationId);
            throw new CodedRefusal.Forbidden(SdvErrorCode.SEN_009, APPROVAL_NEEDED_MESSAGE);
        }
        return level;
    }

    /** A document described in a list (audit and problem views): enough to work out its effective level. */
    public record DocumentLevelRef(UUID documentId, UUID sourceId, UUID submissionId, SensitivityLevel ownLevel) { }

    /**
     * Effective levels for many documents of one tenant: one metadata query and one record-level query per sending
     * system. A document whose own level is missing is treated as Highly Confidential (never shown by mistake).
     */
    public Map<UUID, SensitivityLevel> effectiveLevels(UUID tenantId, List<DocumentLevelRef> documents) {
        Map<UUID, SensitivityLevel> result = new HashMap<>();
        Map<UUID, List<DocumentLevelRef>> bySource = documents.stream()
                .filter(ref -> ref.documentId() != null)
                .collect(Collectors.groupingBy(ref -> ref.sourceId() == null ? new UUID(0, 0) : ref.sourceId(),
                        LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<UUID, List<DocumentLevelRef>> entry : bySource.entrySet()) {
            List<DocumentLevelRef> refs = entry.getValue();
            boolean known = refs.getFirst().sourceId() != null;
            if (!known) {
                refs.forEach(ref -> result.put(ref.documentId(), SensitivityLevel.HIGHLY_CONFIDENTIAL));
                continue;
            }
            UUID sourceId = entry.getKey();
            List<UUID> submissions = new ArrayList<>(refs.stream().map(DocumentLevelRef::submissionId)
                    .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new)));
            List<MetadataSnapshot> metadata = submissions.isEmpty() ? List.of()
                    : records.findMetadataSnapshots(tenantId, sourceId, submissions);
            Map<UUID, String> keyBySubmission = new HashMap<>();
            for (UUID submission : submissions) keyBySubmission.put(submission, RecordKey.forSubmission(submission, metadata));
            Map<String, SensitivityLevel> recordLevels = keyBySubmission.isEmpty() ? Map.of()
                    : records.findRecordSensitivityLevels(tenantId, sourceId, keyBySubmission.values());
            for (DocumentLevelRef ref : refs) {
                if (ref.ownLevel() == null || ref.submissionId() == null) {
                    result.put(ref.documentId(), SensitivityLevel.HIGHLY_CONFIDENTIAL);
                    continue;
                }
                SensitivityLevel recordLevel = recordLevels == null ? null
                        : recordLevels.get(keyBySubmission.get(ref.submissionId()));
                result.put(ref.documentId(), SensitivityLevel.effective(ref.ownLevel(), recordLevel));
            }
        }
        return result;
    }

    private void refuse(Document document, SensitivityLevel level, Attempt attempt, SdvErrorCode code,
                        String actorId, String correlationId) {
        try {
            audit.append(new AuditEvent(
                    UUID.randomUUID(), document.tenantId(), "USER", actorId,
                    REFUSED_ACTION, "DOCUMENT", document.documentId().toString(),
                    clock.instant(), correlationId, AuditOutcome.DENIED,
                    "{\"level\":\"" + level.name() + "\",\"attempt\":\"" + attempt.name()
                            + "\",\"code\":\"" + code.code() + "\"}"));
        } catch (RuntimeException ignored) {
            // The refusal stands even when the audit store is unavailable (fail closed).
        }
    }
}
