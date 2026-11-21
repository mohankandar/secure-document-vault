package com.sdv.platform.application.sensitivity;

import com.sdv.platform.domain.error.InternalCheckFailed;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdv.platform.application.access.Clearance;
import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.approvals.AccessRequestService;
import com.sdv.platform.application.business.RecordKey;
import com.sdv.platform.application.business.RecordReferences;
import com.sdv.platform.application.port.AccessRequestRepository;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.RecordSensitivityRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Task 0028 (S5): a Steward (CHANGE_SENSITIVITY_LEVEL; the web layer checks it) changes a level, of one document or
 * of a whole record (contract, patient file).
 * <ul>
 *   <li><b>Raise</b>, with a reason: applies at once, and only to a level higher than now. For a document "now" is its
 *       effective level (the higher of its own and its record's), which is what everyone sees. A Steward may raise a
 *       document to Highly Confidential and then can no longer open it.</li>
 *   <li><b>Lower</b>, with a reason: only a request, applied when a Business Admin approves it
 *       ({@link AccessRequestService}). A document's own level must come down; a Steward (who can't open Highly
 *       Confidential documents) may ask to lower only from Confidential.</li>
 * </ul>
 * A document must be one the person can open (refused with SDV-SEN-008 otherwise, and audited). A record needs no
 * opening: its level applies to all its documents. Each change is audited with who, when, scope, from and to, in the
 * same transaction as the change. Task 0029 (0.4): the reason is not in the audit (IT Admins read it, and a reason is
 * free text that may hold names). It is kept in the level-change history (sensitivity_level_changes), which the audit
 * event names (levelChangeId) and which Stewards and Business Admins read ({@link #documentHistory},
 * {@link #recordHistory}).
 */
@Service
public class SensitivityLevelService {
    public static final String DOCUMENT_RAISED_ACTION = "DOCUMENT_SENSITIVITY_RAISED";
    public static final String RECORD_RAISED_ACTION = "RECORD_SENSITIVITY_RAISED";
    private static final Set<String> GONE = Set.of("EXPIRED_DELETED", "RETENTION_DELETING");
    private static final String NO_DOCUMENT = "No document has this reference.";
    private static final String NO_RECORD = "No record has this reference.";

    private final DocumentIntakeRepository documents;
    private final AccessRequestRepository documentLevels;
    private final RecordSensitivityRepository recordLevels;
    private final BusinessRecordRepository records;
    private final RecordReferences recordReferences;
    private final DocumentAccessGuard access;
    private final AccessRequestService approvals;
    private final AuditEventPort audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    public SensitivityLevelService(DocumentIntakeRepository documents, AccessRequestRepository documentLevels,
                                   RecordSensitivityRepository recordLevels, BusinessRecordRepository records,
                                   RecordReferences recordReferences, DocumentAccessGuard access,
                                   AccessRequestService approvals, AuditEventPort audit,
                                   PlatformTransactionManager transactionManager) {
        this(documents, documentLevels, recordLevels, records, recordReferences, access, approvals, audit,
                transactionManager, Clock.systemUTC());
    }

    public SensitivityLevelService(DocumentIntakeRepository documents, AccessRequestRepository documentLevels,
                                   RecordSensitivityRepository recordLevels, BusinessRecordRepository records,
                                   RecordReferences recordReferences, DocumentAccessGuard access,
                                   AccessRequestService approvals, AuditEventPort audit,
                                   PlatformTransactionManager transactionManager, Clock clock) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.documentLevels = Objects.requireNonNull(documentLevels, "documentLevels");
        this.recordLevels = Objects.requireNonNull(recordLevels, "recordLevels");
        this.records = Objects.requireNonNull(records, "records");
        this.recordReferences = Objects.requireNonNull(recordReferences, "recordReferences");
        this.access = Objects.requireNonNull(access, "access");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---------------------------------------------------------------------------------------------- documents

    public LevelChange raiseDocument(UUID tenantId, String actorId, String actorName, Clearance clearance,
                                     String documentReference, String levelText, String reason, String correlationId) {
        Document document = openableDocument(tenantId, actorId, clearance, documentReference, correlationId);
        SensitivityLevel target = level(levelText);
        String why = AccessRequestService.reasonOf(reason, SdvErrorCode.SEN_012);
        SensitivityLevel recordLevel = recordLevel(document);
        Instant now = clock.instant();
        return transactions.execute(status -> {
            AccessRequestRepository.LockedDocument locked = documentLevels.lockDocument(tenantId, document.documentId())
                    .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.SEN_016, NO_DOCUMENT));
            if (GONE.contains(locked.status())) throw notAvailable();
            SensitivityLevel current = SensitivityLevel.effective(locked.ownLevel(), recordLevel);
            if (target.compareTo(current) <= 0) {
                throw new CodedRefusal.Conflict(SdvErrorCode.SEN_013,
                        "A raise must go to a level higher than the document's level now (" + current.name() + ").");
            }
            documentLevels.updateDocumentLevel(tenantId, document.documentId(), target);
            UUID changeId = UUID.randomUUID();
            documentLevels.insertLevelChange(new AccessRequestRepository.LevelChange(changeId, tenantId,
                    AccessRequestRepository.LevelChange.Scope.DOCUMENT, document.documentId(), null, null,
                    AccessRequestRepository.LevelChange.Direction.RAISED, locked.ownLevel(), target, why, actorId,
                    blankToNull(actorName), now, null));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("scope", "DOCUMENT");
            details.put("from", locked.ownLevel().name());
            details.put("to", target.name());
            details.put("effectiveBefore", current.name());
            details.put("levelChangeId", changeId.toString());
            append(tenantId, actorId, actorName, DOCUMENT_RAISED_ACTION, "DOCUMENT", document.documentId().toString(),
                    now, correlationId, details);
            SensitivityLevel after = SensitivityLevel.effective(target, recordLevel);
            return new LevelChange(LevelChange.Outcome.APPLIED, after, clearance.canOpen(after), null, null);
        });
    }

    public LevelChange requestLowerDocument(UUID tenantId, String actorId, String actorName, Clearance clearance,
                                            String documentReference, String levelText, String reason,
                                            String correlationId) {
        Document document = openableDocument(tenantId, actorId, clearance, documentReference, correlationId);
        SensitivityLevel target = level(levelText);
        String why = AccessRequestService.reasonOf(reason, SdvErrorCode.SEN_012);
        SensitivityLevel own = document.sensitivityLevel();
        if (target.compareTo(own) >= 0) {
            throw new CodedRefusal.Conflict(SdvErrorCode.SEN_014,
                    "A lowering must go to a level lower than the document's own level (" + own.name() + ").");
        }
        requireStewardFromConfidential(clearance, own);
        AccessRequestService.RequestView request = approvals.requestLowerDocument(tenantId, actorId, actorName,
                document.documentId(), own, target, why, correlationId);
        return new LevelChange(LevelChange.Outcome.REQUESTED, access.effectiveLevel(document), true,
                request.requestId(), request.expiresAt());
    }

    // ------------------------------------------------------------------------------------------------ records

    public LevelChange raiseRecord(UUID tenantId, String actorId, String actorName, Clearance clearance,
                                   String recordReference, String levelText, String reason, String correlationId) {
        RecordReferences.ResolvedRecord record = record(tenantId, recordReference);
        SensitivityLevel target = level(levelText);
        String why = AccessRequestService.reasonOf(reason, SdvErrorCode.SEN_012);
        Instant now = clock.instant();
        return transactions.execute(status -> {
            RecordSensitivityRepository.StoredRecordLevel stored =
                    recordLevels.lockByKey(tenantId, record.sourceId(), record.recordKey()).orElse(null);
            UUID recordLevelId = null;
            if (stored == null) {
                requireRaise(target, null);
                UUID newId = UUID.randomUUID();
                if (recordLevels.insertIfAbsent(new RecordSensitivityRepository.NewRecordLevel(newId,
                        tenantId, record.sourceId(), record.recordKey(), target, why, actorId, now))) {
                    recordLevelId = newId;
                } else {
                    // A delivery set a first level at the same moment: compare with that one instead.
                    stored = recordLevels.lockByKey(tenantId, record.sourceId(), record.recordKey())
                            .orElseThrow(() -> new InternalCheckFailed("The record's level could not be read."));
                }
            }
            SensitivityLevel from = stored == null ? null : stored.level();
            if (stored != null) {
                requireRaise(target, stored.level());
                recordLevelId = stored.recordLevelId();
                recordLevels.setLevel(tenantId, recordLevelId, target, actorId, why, now);
            }
            UUID changeId = UUID.randomUUID();
            documentLevels.insertLevelChange(new AccessRequestRepository.LevelChange(changeId, tenantId,
                    AccessRequestRepository.LevelChange.Scope.RECORD, null, record.sourceId(), record.recordKey(),
                    AccessRequestRepository.LevelChange.Direction.RAISED, from, target, why, actorId,
                    blankToNull(actorName), now, null));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("scope", "RECORD");
            details.put("from", from == null ? null : from.name());
            details.put("to", target.name());
            details.put("levelChangeId", changeId.toString());
            details.put("sourceId", record.sourceId().toString());
            details.put("submissionId", record.submissionIds().getFirst().toString());
            append(tenantId, actorId, actorName, RECORD_RAISED_ACTION, "RECORD_SENSITIVITY", recordLevelId.toString(),
                    now, correlationId, details);
            return new LevelChange(LevelChange.Outcome.APPLIED, target, clearance.canOpen(target), null, null);
        });
    }

    public LevelChange requestLowerRecord(UUID tenantId, String actorId, String actorName, Clearance clearance,
                                          String recordReference, String levelText, String reason,
                                          String correlationId) {
        RecordReferences.ResolvedRecord record = record(tenantId, recordReference);
        SensitivityLevel target = level(levelText);
        String why = AccessRequestService.reasonOf(reason, SdvErrorCode.SEN_012);
        SensitivityLevel current = records.findRecordSensitivityLevel(tenantId, record.sourceId(), record.recordKey())
                .orElse(null);
        if (current == null || target.compareTo(current) >= 0) {
            throw new CodedRefusal.Conflict(SdvErrorCode.SEN_014, "A lowering must go to a level lower than the record's "
                    + "level now (" + (current == null ? "none" : current.name()) + ").");
        }
        requireStewardFromConfidential(clearance, current);
        AccessRequestService.RequestView request = approvals.requestLowerRecord(tenantId, actorId, actorName, record,
                current, target, why, correlationId);
        return new LevelChange(LevelChange.Outcome.REQUESTED, current, clearance.canOpen(current),
                request.requestId(), request.expiresAt());
    }

    // ------------------------------------------------------------------------------------------------ history

    /** The most history rows one read returns. */
    public static final int HISTORY_LIMIT = 100;

    /**
     * Task 0029 (0.4): a document's level changes with their reasons, newest first. A Steward must be able to open the
     * document (SDV-SEN-008 and audited otherwise); a Business Admin (who approves lowerings and already reads their
     * reasons) reads it without opening the document. The web layer checks that the person is one of the two.
     */
    public List<HistoryEntry> documentHistory(UUID tenantId, String actorId, Clearance clearance, boolean businessAdmin,
                                              String documentReference, String correlationId) {
        if (tenantId == null) throw new InternalCheckFailed("tenantId is required.");
        UUID documentId = AccessRequestService.referenceOf(documentReference, SdvErrorCode.SEN_016, NO_DOCUMENT);
        Document document = documents.findDocumentByIdForTenant(tenantId, documentId)
                .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.SEN_016, NO_DOCUMENT));
        if (!businessAdmin) {
            access.requireOpenable(document, clearance, DocumentAccessGuard.Attempt.LEVEL_HISTORY, actorId, correlationId);
        }
        return documentLevels.findDocumentLevelChanges(tenantId, documentId, HISTORY_LIMIT).stream()
                .map(HistoryEntry::of).toList();
    }

    /** Task 0029 (0.4): a record's level changes with their reasons, newest first. */
    public List<HistoryEntry> recordHistory(UUID tenantId, String recordReference) {
        RecordReferences.ResolvedRecord record = record(tenantId, recordReference);
        return documentLevels.findRecordLevelChanges(tenantId, record.sourceId(), record.recordKey(), HISTORY_LIMIT)
                .stream().map(HistoryEntry::of).toList();
    }

    /**
     * One change as Stewards and Business Admins see it. changedBy is the person's name, or their sign-in subject when
     * none was known. accessRequestId names the approved request of a lowering.
     */
    public record HistoryEntry(UUID changeId, AccessRequestRepository.LevelChange.Scope scope,
                               AccessRequestRepository.LevelChange.Direction direction, SensitivityLevel from,
                               SensitivityLevel to, String reason, String changedBy, Instant changedAt,
                               UUID accessRequestId) {
        static HistoryEntry of(AccessRequestRepository.LevelChange c) {
            String who = c.changedByName() == null || c.changedByName().isBlank() ? c.changedBy() : c.changedByName();
            return new HistoryEntry(c.changeId(), c.scope(), c.direction(), c.from(), c.to(), c.reason(), who,
                    c.changedAt(), c.accessRequestId());
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The document, if the person can open it at its effective level (SDV-SEN-008 and audited otherwise) and it is available. */
    private Document openableDocument(UUID tenantId, String actorId, Clearance clearance, String reference,
                                      String correlationId) {
        if (tenantId == null) throw new InternalCheckFailed("tenantId is required.");
        if (actorId == null || actorId.isBlank()) throw new InternalCheckFailed("actorId is required.");
        UUID documentId = AccessRequestService.referenceOf(reference, SdvErrorCode.SEN_016, NO_DOCUMENT);
        Document document = documents.findDocumentByIdForTenant(tenantId, documentId)
                .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.SEN_016, NO_DOCUMENT));
        access.requireOpenable(document, clearance, DocumentAccessGuard.Attempt.CHANGE_LEVEL, actorId, correlationId);
        if (document.status() != DocumentStatus.AVAILABLE) throw notAvailable();
        return document;
    }

    private RecordReferences.ResolvedRecord record(UUID tenantId, String reference) {
        if (tenantId == null) throw new InternalCheckFailed("tenantId is required.");
        return recordReferences.fromReference(tenantId, reference)
                .orElseThrow(() -> new CodedRefusal.NotFound(SdvErrorCode.SEN_016, NO_RECORD));
    }

    /** The document's record level alone (null when the record has none). */
    private SensitivityLevel recordLevel(Document document) {
        String key = RecordKey.forSubmission(document.submissionId(), records.findMetadataSnapshots(
                document.tenantId(), document.sourceId(), List.of(document.submissionId())));
        return records.findRecordSensitivityLevel(document.tenantId(), document.sourceId(), key).orElse(null);
    }

    private static void requireRaise(SensitivityLevel target, SensitivityLevel current) {
        boolean higher = current == null ? target != SensitivityLevel.STANDARD : target.compareTo(current) > 0;
        if (!higher) {
            throw new CodedRefusal.Conflict(SdvErrorCode.SEN_013, "A raise must go to a level higher than the record's "
                    + "level now (" + (current == null ? "none" : current.name()) + ").");
        }
    }

    /** A Steward without Highly Confidential clearance may ask to lower only from Confidential. */
    private static void requireStewardFromConfidential(Clearance clearance, SensitivityLevel from) {
        if (!clearance.highlyConfidential() && from != SensitivityLevel.CONFIDENTIAL) {
            throw new CodedRefusal.Forbidden(SdvErrorCode.SEN_015,
                    "A Steward can ask to lower a level only from Confidential.");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static SensitivityLevel level(String text) {
        if (text != null) {
            for (SensitivityLevel level : SensitivityLevel.values()) {
                if (level.name().equals(text)) return level;
            }
        }
        throw new CodedRefusal.BadRequest(SdvErrorCode.SEN_011,
                "level must be one of STANDARD, CONFIDENTIAL, HIGHLY_CONFIDENTIAL (exactly, in capitals).");
    }

    private static CodedRefusal.Conflict notAvailable() {
        return new CodedRefusal.Conflict(SdvErrorCode.SEN_017, "Only an available document's level can be changed.");
    }

    private void append(UUID tenantId, String actorId, String actorName, String action, String targetType,
                        String targetId, Instant at, String correlationId, Map<String, Object> details) {
        String safe;
        try {
            safe = json.writeValueAsString(details);
        } catch (JsonProcessingException ex) {
            throw InternalCheckFailed.auditNotWritten("The level change could not be audited, so nothing was changed.", ex);
        }
        audit.append(new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId, action, targetType, targetId, at,
                correlationId, AuditOutcome.SUCCESS, safe,
                actorName == null || actorName.isBlank() ? null : actorName.strip()));
    }

    /**
     * What happened: APPLIED (a raise; level is the new level) or REQUESTED (a lowering waiting for a Business Admin;
     * level is still the current one). canStillOpen: whether this person can still open it (a Steward who raised a
     * document to Highly Confidential can't).
     */
    public record LevelChange(Outcome outcome, SensitivityLevel level, boolean canStillOpen, UUID requestId,
                              Instant requestExpiresAt) {
        public enum Outcome { APPLIED, REQUESTED }
    }
}
