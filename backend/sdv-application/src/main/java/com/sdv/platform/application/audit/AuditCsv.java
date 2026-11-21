package com.sdv.platform.application.audit;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Task 0011 Part B.6: the exported audit as CSV (UTF-8 with a byte-order mark so spreadsheets read names correctly).
 * Columns: When (UTC), What happened, Person, Record, Result, Action code; task 0027 (0.4), for a document event,
 * instead of its file name (never written): Document ID, Level (effective), Received (UTC), Pages. Every cell is
 * quoted, and a cell that a spreadsheet would run as a formula (starting with = + - @, tab or carriage return) is prefixed with an apostrophe.
 */
public final class AuditCsv {
    static final String HEADER = "When (UTC),What happened,Person,Record,Result,Action code,Document ID,Level,Received (UTC),Pages";

    private AuditCsv() { }

    public static byte[] write(List<AuditQueryService.AuditEntry> entries) {
        StringBuilder out = new StringBuilder("﻿").append(HEADER).append("\r\n");
        for (AuditQueryService.AuditEntry e : entries) {
            out.append(cell(e.occurredAt().toString())).append(',')
                    .append(cell(AuditSentences.sentence(e))).append(',')
                    .append(cell(AuditSentences.actorName(e))).append(',')
                    .append(cell(e.recordNumber())).append(',')
                    .append(cell(AuditSentences.resultWords(e.outcome().name()))).append(',')
                    .append(cell(e.action())).append(',')
                    .append(cell(e.documentId() == null ? null : e.documentId().toString())).append(',')
                    .append(cell(e.sensitivityLevel() == null ? null : e.sensitivityLevel().name())).append(',')
                    .append(cell(e.documentReceivedAt() == null ? null : e.documentReceivedAt().toString())).append(',')
                    .append(cell(e.documentPageCount() == null ? null : e.documentPageCount().toString())).append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Task 0032: also used by the Daily intake CSV, with the same formula guard. */
    public static String cell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
