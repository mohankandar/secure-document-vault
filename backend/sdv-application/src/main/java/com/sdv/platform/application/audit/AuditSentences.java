package com.sdv.platform.application.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Locale;
import java.util.Map;

/**
 * Task 0011 export: each audit event as the same sentence the Audit screen shows. This is the server copy of
 * {@code frontend/sdv-ui/src/r7/auditWords.ts}; the two must say the same thing (the screen tour compares the
 * exported sentences with the screen's). A code not listed here reads "who · Humanized code".
 */
public final class AuditSentences {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What a refused permission would have allowed, in words. */
    private static final Map<String, String> CAPABILITY_WORDS = Map.ofEntries(
            Map.entry("SEARCH_RECORDS", "search records"),
            Map.entry("VIEW_PDF", "open a document"),
            Map.entry("DOWNLOAD_PDF", "download a document"),
            Map.entry("PRINT_DOCUMENT", "print a document"),
            Map.entry("ADD_DOCUMENT_TAG", "tag a document"),
            Map.entry("ADD_DOCUMENT_COMMENT", "comment on a document"),
            Map.entry("PERMANENT_REDACTION", "permanently redact a document"),
            Map.entry("CONFIGURE_ROLE_PERMISSIONS", "change roles & permissions"),
            Map.entry("CONFIGURE_RETENTION", "change how long documents are kept"),
            Map.entry("CONFIGURE_METADATA_SEARCH", "change search fields"),
            Map.entry("CONFIGURE_INGESTION_SOURCES", "change sending systems"),
            Map.entry("CONFIGURE_STORAGE_KEY_PROVIDER", "change storage"),
            Map.entry("VIEW_INTAKE_REPORTS", "see the reports"),
            Map.entry("VIEW_TECHNICAL_FAILURES", "see problems and the audit"));

    /** Task 0026: what a refused DOCUMENT_ACCESS_REFUSED tried, in words. */
    private static final Map<String, String> ATTEMPT_WORDS = Map.ofEntries(
            Map.entry("OPEN", "open"), Map.entry("PAGE", "load a page of"), Map.entry("PREVIEWS", "see the pages of"),
            Map.entry("TEXT_SEARCH", "search in"), Map.entry("TAGS", "see the tags of"), Map.entry("ADD_TAG", "tag"),
            Map.entry("COMMENTS", "see the comments on"), Map.entry("ADD_COMMENT", "comment on"),
            Map.entry("ACTIVITY", "see the activity of"), Map.entry("PRINT", "print"),
            Map.entry("DOWNLOAD", "download"), Map.entry("REDACT", "redact"),
            Map.entry("CHANGE_LEVEL", "change the level of"), Map.entry("REQUEST_ACCESS", "ask to print or download"),
            Map.entry("LEVEL_HISTORY", "see the level history of"));

    private AuditSentences() { }

    /** The name from the event; older events and background work show the subject ID ("Vero" for system work). */
    public static String actorName(AuditQueryService.AuditEntry e) {
        if (e.actorDisplayName() != null && !e.actorDisplayName().isEmpty()) return e.actorDisplayName();
        return "SYSTEM".equals(e.actorType()) ? "Vero" : e.actorId();
    }

    public static String resultWords(String outcome) {
        return switch (outcome) {
            case "SUCCESS" -> "Done";
            case "DENIED" -> "Refused";
            case "FAILED" -> "Failed";
            default -> outcome;
        };
    }

    public static String sentence(AuditQueryService.AuditEntry e) {
        String w = actorName(e);
        // Task 0027 (0.4): admin views never name a file; the Record, Level, Received and Pages columns say which.
        String f = "a document";
        JsonNode d = details(e.safeDetailsJson());
        return switch (e.action()) {
            case "DOCUMENT_VIEW_OPENED" -> w + " opened " + f;
            case "DOCUMENT_PAGE_VIEWED" -> number(d, "page") != null ? w + " loaded page " + number(d, "page") + " of " + f : w + " loaded a page of " + f;
            case "DOCUMENT_DOWNLOADED" -> w + " downloaded " + f;
            case "DOCUMENT_DOWNLOAD_FAILED" -> w + " tried to download " + f + ", but it failed";
            case "DOCUMENT_PRINTED" -> w + " printed " + f;
            case "DOCUMENT_TAG_ADDED" -> w + " tagged " + f;
            case "DOCUMENT_COMMENT_ADDED" -> w + " commented on " + f;
            case "DOCUMENT_PERMANENTLY_REDACTED" -> w + " permanently redacted part of " + f;
            case "DOCUMENT_REDACTION_FAILED" -> w + " tried to redact part of " + f + ", but it failed";
            case "DOCUMENT_RETENTION_DELETED" -> "Vero deleted " + f + " for good, as the keeping rule says";
            case "DOCUMENT_RETENTION_DELETE_FAILED" -> "Vero couldn't delete " + f + " when its time was up";
            case "BUSINESS_RECORD_OPENED" -> w + " opened a record";
            case "BUSINESS_SEARCH" -> number(d, "resultCount") != null ? w + " searched records and found " + number(d, "resultCount") : w + " searched records";
            case "BUSINESS_RECENT_RECORDS_LISTED" -> w + " looked at Recently viewed";
            case "AUDIT_VIEWED" -> w + " looked at the audit";
            case "AUDIT_EXPORTED" -> number(d, "rowCount") != null ? w + " exported the audit (" + events(number(d, "rowCount")) + ")" : w + " exported the audit";
            case "AUTHORIZATION_DENIED" -> w + " tried to " + CAPABILITY_WORDS.getOrDefault(text(d, "capability"), "do something") + " without permission";
            case "SUBMISSION_CREATED" -> w + " delivered " + docs(number(d, "expectedDocumentCount"));
            case "DOCUMENT_RECEIVED" -> w + " sent " + f;
            // Task 0031: an exact duplicate; nothing was stored a second time.
            case "DOCUMENT_DUPLICATE_RECEIVED" -> w + " sent " + f + " again; Vero kept the copy it already had";
            // Task 0032: what a document was before a permanent correction; only the corrected version is kept.
            case "DOCUMENT_UNCORRECTED_COPY_RECEIVED" -> w + " sent an uncorrected copy of a corrected document; nothing was stored";
            case "INTAKE_REFUSED" -> d.hasNonNull("code")
                    ? w + " sent a delivery that Vero refused (" + text(d, "code") + ")" : w + " sent a delivery that Vero refused";
            case "INTAKE_REPORT_EXPORTED" -> number(d, "rowCount") != null
                    ? w + " exported the Daily intake report (" + number(d, "rowCount") + (number(d, "rowCount") == 1L ? " line)" : " lines)")
                    : w + " exported the Daily intake report";
            case "DOCUMENT_AVAILABLE" -> f + " is stored and ready to open";
            case "DOCUMENT_PROCESSING_FAILED" -> "Vero couldn't store " + f;
            case "ROLE_CAPABILITIES_UPDATED" -> w + " changed what business roles may do";
            case "ROLE_CAPABILITIES_UPDATE_REFUSED" -> w + " tried to change what a role may do; Vero refused it";
            case "ROLE_CONFLICT_DETECTED" -> w + " signed in with roles that can't be held together; Vero gave neither";
            case "ROLE_CONFLICT_ENDED" -> w + " signed in with roles that no longer conflict";
            case "DOCUMENT_ACCESS_REFUSED" -> w + " tried to " + ATTEMPT_WORDS.getOrDefault(text(d, "attempt"), "use")
                    + " " + f + ("SDV-SEN-009".equals(text(d, "code"))
                            ? ", but that needs an approval" : ", but its level is restricted");
            case "LEGAL_HOLD_PLACED" -> w + " put " + f + " on legal hold";
            case "LEGAL_HOLD_RELEASED" -> w + " released the legal hold on " + f;
            // Task 0028: level changes and approvals. Levels in words; the reason stays in the details.
            case "DOCUMENT_SENSITIVITY_RAISED" -> w + " raised " + f + " to " + levelWords(text(d, "to"));
            case "DOCUMENT_SENSITIVITY_LOWERED" -> w + " approved lowering " + f + " to " + levelWords(text(d, "to"));
            case "RECORD_SENSITIVITY_RAISED" -> w + " raised a record to " + levelWords(text(d, "to"));
            case "RECORD_SENSITIVITY_LOWERED" -> w + " approved lowering a record to " + levelWords(text(d, "to"));
            case "ACCESS_REQUEST_CREATED" -> w + " asked " + requestWords(text(d, "kind"), text(d, "requestedLevel"), f);
            case "ACCESS_REQUEST_APPROVED" -> w + " approved a request " + requestWords(text(d, "kind"), text(d, "requestedLevel"), f);
            case "ACCESS_REQUEST_DECLINED" -> w + " declined a request " + requestWords(text(d, "kind"), text(d, "requestedLevel"), f);
            case "ACCESS_REQUEST_DECISION_REFUSED" -> w + " tried to decide a request; Vero refused it";
            case "ACCESS_REQUEST_EXPIRED" -> "A request " + requestWords(text(d, "kind"), null, f) + " expired";
            case "SENSITIVE_ACCESS_GRANT_USED" -> w + " used an approval to " + ("DOWNLOAD".equals(text(d, "kind")) ? "download " : "print ") + f;
            case "ROLE_GROUP_MAPPING_CHANGED" -> "The sign-in groups for " + text(d, "role") + " changed";
            case "RETENTION_POLICY_UPDATED" -> d.path("indefinite").asBoolean(false) && d.path("indefinite").isBoolean()
                    ? w + " changed how long to keep new documents: keep them forever"
                    : w + " changed how long to keep new documents to " + ruleWords(d);
            case "METADATA_BEHAVIOR_REVISED" -> w + " changed how a search field works";
            case "METADATA_DISPLAY_ORDER_UPDATED" -> w + " changed the order of the search fields";
            case "RECORD_TITLE_FIELDS_UPDATED" -> w + " changed what records are called";
            case "METADATA_FIELD_CREATED", "SOURCE_FIELD_DEFINED" -> w + " added a field a sending system sends";
            case "METADATA_FIELD_REVISED", "SOURCE_FIELD_REVISED" -> w + " changed a field a sending system sends";
            case "INGESTION_SOURCE_CREATED" -> w + " added a sending system";
            case "INGESTION_SOURCE_UPDATED" -> w + " changed a sending system";
            case "STORAGE_VOLUME_CREATED" -> w + " added a storage volume";
            case "STORAGE_VOLUME_UPDATED" -> w + " changed a storage volume";
            case "STORAGE_FOLDER_CHECKED" -> d.path("writable").isBoolean() && !d.path("writable").asBoolean()
                    ? w + " checked a folder for a new volume; Vero couldn't write there"
                    : w + " checked a folder for a new volume";
            case "ENTERPRISE_IDENTITY_DRAFT_SAVED" -> w + " saved a draft of the sign-in settings";
            case "ENTERPRISE_IDENTITY_CONNECTION_TESTED" -> w + " tested the sign-in settings";
            case "ENTERPRISE_IDENTITY_CONNECTION_ACTIVATED" -> w + " switched on new sign-in settings";
            case "ENTERPRISE_IDENTITY_CONNECTION_ACTIVATION_FAILED" -> w + " tried to switch on new sign-in settings, but it failed";
            case "ENTERPRISE_IDENTITY_CONNECTION_ROLLED_BACK" -> w + " went back to earlier sign-in settings";
            case "ENTERPRISE_IDENTITY_CONNECTION_ROLLBACK_FAILED" -> w + " tried to go back to earlier sign-in settings, but it failed";
            default -> w + " · " + humanize(e.action());
        };
    }

    static String humanize(String code) {
        String lower = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static JsonNode details(String json) {
        if (json == null || json.isBlank()) return JSON.createObjectNode();
        try {
            JsonNode node = JSON.readTree(json);
            return node != null && node.isObject() ? node : JSON.createObjectNode();
        } catch (Exception ex) {
            return JSON.createObjectNode();
        }
    }

    private static Long number(JsonNode d, String key) {
        JsonNode v = d.get(key);
        return v != null && v.isNumber() ? v.asLong() : null;
    }

    private static String text(JsonNode d, String key) {
        JsonNode v = d.get(key);
        return v == null || v.isNull() ? "null" : v.asText();
    }

    /** Task 0028: a level in words, as the screens say it. */
    private static String levelWords(String level) {
        if (level == null) return "another level";
        return switch (level) {
            case "STANDARD" -> "Standard";
            case "CONFIDENTIAL" -> "Confidential";
            case "HIGHLY_CONFIDENTIAL" -> "Highly Confidential";
            default -> "another level";
        };
    }

    /** Task 0028: what a request asks for, in words ("to print a document", "to lower a record to Standard"). */
    private static String requestWords(String kind, String requestedLevel, String document) {
        if (kind == null) return "for approval";
        return switch (kind) {
            case "PRINT" -> "to print " + document;
            case "DOWNLOAD" -> "to download " + document;
            case "LOWER_LEVEL" -> "to lower " + document + (requestedLevel == null ? "" : " to " + levelWords(requestedLevel));
            case "LOWER_RECORD_LEVEL" -> "to lower a record" + (requestedLevel == null ? "" : " to " + levelWords(requestedLevel));
            default -> "for approval";
        };
    }

    private static String docs(Long k) {
        return k == null ? "documents" : k + " document" + (k == 1 ? "" : "s");
    }

    private static String events(Long k) {
        return k + " event" + (k == 1 ? "" : "s");
    }

    private static String ruleWords(JsonNode d) {
        Long days = number(d, "defaultRetentionDays");
        if (days == null) return "a new rule";
        if (days % 365 == 0) return days / 365 + " year" + (days == 365 ? "" : "s");
        if (days % 30 == 0) return days / 30 + " month" + (days == 30 ? "" : "s");
        return String.format(Locale.US, "%,d", days) + " days";
    }
}
