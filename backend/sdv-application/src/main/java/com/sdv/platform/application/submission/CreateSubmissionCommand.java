package com.sdv.platform.application.submission;

import java.util.List;
import java.util.Map;

public record CreateSubmissionCommand(
        String sourceSubmissionId,
        int expectedDocumentCount,
        List<String> expectedSourceDocumentIds,
        Map<String, String> metadata) {
}
