package com.sdv.platform.application.access;

import com.sdv.platform.domain.error.InternalCheckFailed;
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
            "Printing or downloading a Highly Confidential document needs a Business Admin's approval, used once "
                    + "within its time. Ask for one, then try again.";
    /** Task 0048: what everyone but a quarantined document's reviewers is told: never its level or why. */
    public static final String UNAVAILABLE_MESSAGE = "This document is temporarily unavailable.";
    /**
     * Task 0048: what a reviewer may do with a quarantined document: read it and correct it (redaction), so it can be
     * released. Never print, download, tag, comment, change its level or ask for access while it is quarantined.
     */
    static final java.util.Set<Attempt> REVIEW_ATTEMPTS = java.util.EnumSet.of(Attempt.OPEN, Attempt.PAGE,
            Attempt.PREVIEWS, Attempt.TEXT_SEARCH, Attempt.TAGS, Attempt.COMMENTS, Attempt.ACTIVITY, Attempt.REDACT,
            Attempt.LEVEL_HISTORY);

    /**
     * What the person tried; written in the refusal's audit event. Task 0028: CHANGE_LEVEL (raise or ask to lower)
     * and REQUEST_ACCESS (ask to print or download).
     */
    public enum Attempt { OPEN, PAGE, PREVIEWS, TEXT_SEARCH, TAGS, ADD_TAG, COMMENTS, ADD_COMMENT, ACTIVITY, PRINT, DOWNLOAD, REDACT,
        CHANGE_LEVEL, REQUEST_ACCESS, LEVEL_HISTORY }

    private final BusinessRecordRepository records;
    private final AuditEventPort audit;
    private final SensitiveAccessGrants grants;
    private final QuarantineLookup quarantine;
    private final Clock clock;

    /** No grants: every Highly Confidential print and download is refused. */
    public DocumentAccessGuard(BusinessRecordRepository records, AuditEventPort audit) {
        this(records, audit, SensitiveAccessGrants.NONE, Clock.systemUTC());
    }

    public DocumentAccessGuard(BusinessRecordRepository records, AuditEventPort audit, Clock clock) {
        this(records, audit, SensitiveAccessGrants.NONE, clock);
    }


    public DocumentAccessGuard(BusinessRecordRepository records, AuditEventPort audit, SensitiveAccessGrants grants) {
        this(records, audit, grants, Clock.systemUTC());
    }

    public DocumentAccessGuard(BusinessRecordRepository records, AuditEventPort audit, SensitiveAccessGrants grants,
                               Clock clock) {
        this(records, audit, grants, QuarantineLookup.NONE, clock);
    }

    /** Task 0048: with the quarantine store, as Vero runs. */
    @Autowired
    public DocumentAccessGuard(BusinessRecordRepository records, AuditEventPort audit, SensitiveAccessGrants grants,
                               QuarantineLookup quarantine) {
        this(records, audit, grants, quarantine, Clock.systemUTC());
    }

    public DocumentAccessGuard(BusinessRecordRepository records, AuditEventPort audit, SensitiveAccessGrants grants,
                               QuarantineLookup quarantine,
                               Clock clock) {
        this.records = records;
        this.audit = audit;
        this.grants = Objects.requireNonNull(grants, "grants");
        this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
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
        // Task 0048: a quarantined document only for its reviewers, and only to read and correct it. Everyone else is
        // told it is temporarily unavailable (never its level or why); checked before the level, so the answer is the
        // same whatever the level.
        if (quarantine.isQuarantined(document.tenantId(), document.documentId())
                && !(clearance.canReviewQuarantined(level) && REVIEW_ATTEMPTS.contains(attempt))) {
            refuse(document, level, attempt, SdvErrorCode.QUA_001, clearance, actorId, correlationId);
            throw new CodedRefusal.Forbidden(SdvErrorCode.QUA_001, UNAVAILABLE_MESSAGE);
        }
        if (!clearance.canOpen(level)) {
            refuse(document, level, attempt, SdvErrorCode.SEN_008, clearance, actorId, correlationId);
            throw new CodedRefusal.Forbidden(SdvErrorCode.SEN_008, RESTRICTED_MESSAGE);
        }
        return level;
    }

    /**
     * Task 0066: the sealed original of a held document: the document must be openable, and the person a Restricted
     * Steward (the Highly Confidential clearance), whatever the document's level. Refused (403, SDV-HLD-011) and audited.
     */
    public SensitivityLevel requireSealedOpenable(Document document, Clearance clearance, Attempt attempt,
                                                  String actorId, String correlationId) {
        SensitivityLevel level = requireOpenable(document, clearance, attempt, actorId, correlationId);
        if (!clearance.canOpen(SensitivityLevel.HIGHLY_CONFIDENTIAL)) {
            refuse(document, level, attempt, SdvErrorCode.HLD_011, clearance, actorId, correlationId);
            throw new CodedRefusal.Forbidden(SdvErrorCode.HLD_011, RESTRICTED_MESSAGE);
        }
        return level;
    }

    /** What a print or download may go ahead with: the effective level, and the grant used (Highly Confidential only). */
    public record PrintOrDownload(SensitivityLevel level, UUID grantId) { }

    /**
     * Print and download: the document must be openable. Task 0028: a Highly Confidential one also needs a valid grant
     * for this person, this document and this kind (approved, unused, unexpired); without one it is refused (403,
     * SDV-SEN-009) and audited. With {@link SensitiveAccessGrants.Use#USE} the grant is used up here, so it works
     * once; {@link SensitiveAccessGrants.Use#CHECK} only looks (a download checks before it sends anything, and uses
     * the grant just before it decrypts). Returns the effective level, which the caller writes in its own audit event.
     */
    public PrintOrDownload requirePrintOrDownload(Document document, Clearance clearance, Attempt attempt,
                                                  String actorId, String correlationId, SensitiveAccessGrants.Use use) {
        if (attempt != Attempt.PRINT && attempt != Attempt.DOWNLOAD) {
            throw new InternalCheckFailed("Only print and download need this check.");
        }
        Objects.requireNonNull(use, "use");
        SensitivityLevel level = requireOpenable(document, clearance, attempt, actorId, correlationId);
        if (level != SensitivityLevel.HIGHLY_CONFIDENTIAL) return new PrintOrDownload(level, null);
        UUID grant = null;
        try {
            grant = grants.grant(document.tenantId(), document.documentId(), actorId, attempt, use, correlationId).orElse(null);
        } catch (RuntimeException ex) {
            // A grant that can't be read or used is no grant (fail closed).
        }
        if (grant == null) {
            refuse(document, level, attempt, SdvErrorCode.SEN_009, clearance, actorId, correlationId);
            throw new CodedRefusal.Forbidden(SdvErrorCode.SEN_009, APPROVAL_NEEDED_MESSAGE);
        }
        return new PrintOrDownload(level, grant);
    }

    /**
     * Task 0043 (owner answer 4 to report 0034): a grant counts as used only once something leaves Vero. A download
     * that fails before its first byte is handed to the response, or a print whose event can't be recorded, gives its
     * grant back here, audited, so it can still be used once. True when it was given back; false when it couldn't be
     * (it then stays used: fail closed). Never throws.
     */
    public boolean giveBack(Document document, Attempt attempt, UUID grantId, String actorId, String correlationId,
                            String reason) {
        if (document == null || grantId == null || (attempt != Attempt.PRINT && attempt != Attempt.DOWNLOAD)) return false;
        try {
            return grants.giveBack(document.tenantId(), document.documentId(), actorId, attempt, grantId, reason,
                    correlationId);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Task 0048: the ones of these documents that are quarantined now (for record views and search rows). */
    public java.util.Set<UUID> quarantined(UUID tenantId, java.util.Collection<UUID> documentIds) {
        return quarantine.quarantined(tenantId, documentIds);
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

    /**
     * Task 0033: the event also names the person's roles when they are known (role names, sorted), so the Sensitivity
     * report can count attempts by role. Role names are fixed enum names, never free text.
     */
    private void refuse(Document document, SensitivityLevel level, Attempt attempt, SdvErrorCode code,
                        Clearance clearance, String actorId, String correlationId) {
        try {
            String roles = clearance.roles().isEmpty() ? ""
                    : ",\"roles\":[" + clearance.roles().stream()
                    .filter(r -> r.matches("[A-Z_]{1,80}"))
                    .map(r -> "\"" + r + "\"").collect(Collectors.joining(",")) + "]";
            audit.append(new AuditEvent(
                    UUID.randomUUID(), document.tenantId(), "USER", actorId,
                    REFUSED_ACTION, "DOCUMENT", document.documentId().toString(),
                    clock.instant(), correlationId, AuditOutcome.DENIED,
                    "{\"level\":\"" + level.name() + "\",\"attempt\":\"" + attempt.name()
                            + "\",\"code\":\"" + code.code() + "\"" + roles + "}"));
        } catch (RuntimeException ignored) {
            // The refusal stands even when the audit store is unavailable (fail closed).
        }
    }
}
