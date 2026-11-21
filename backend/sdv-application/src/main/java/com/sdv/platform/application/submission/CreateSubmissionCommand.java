package com.sdv.platform.application.submission;

import java.util.List;
import java.util.Map;

/**
 * recordSensitivityLevel (task 0024, part 2) is the level for the whole record this submission belongs to, exactly as
 * the sending system sent it; null when it sent none.
 */
public record CreateSubmissionCommand(
        String sourceSubmissionId,
        int expectedDocumentCount,
        List<String> expectedSourceDocumentIds,
        Map<String, String> metadata,
        String recordSensitivityLevel) {

    public CreateSubmissionCommand(String sourceSubmissionId,
                                   int expectedDocumentCount,
                                   List<String> expectedSourceDocumentIds,
                                   Map<String, String> metadata) {
        this(sourceSubmissionId, expectedDocumentCount, expectedSourceDocumentIds, metadata, null);
    }
}
