package com.sdv.platform.application.audit;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Task 0011 Part B.6: the exported audit as CSV (UTF-8 with a byte-order mark so spreadsheets read names correctly).
 * Columns: When (UTC), What happened, Person, Record, Result, Action code. Every cell is quoted, and a cell that a
 * spreadsheet would run as a formula (starting with = + - @, tab or carriage return) is prefixed with an apostrophe.
 */
public final class AuditCsv {
    static final String HEADER = "When (UTC),What happened,Person,Record,Result,Action code";

    private AuditCsv() { }

    public static byte[] write(List<AuditQueryService.AuditEntry> entries) {
        StringBuilder out = new StringBuilder("﻿").append(HEADER).append("\r\n");
        for (AuditQueryService.AuditEntry e : entries) {
            out.append(cell(e.occurredAt().toString())).append(',')
                    .append(cell(AuditSentences.sentence(e))).append(',')
                    .append(cell(AuditSentences.actorName(e))).append(',')
                    .append(cell(e.recordNumber())).append(',')
                    .append(cell(AuditSentences.resultWords(e.outcome().name()))).append(',')
                    .append(cell(e.action())).append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String cell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
