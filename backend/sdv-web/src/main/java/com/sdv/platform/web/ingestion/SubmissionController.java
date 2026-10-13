package com.sdv.platform.web.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.sdv.platform.application.submission.CreateSubmissionCommand;
import com.sdv.platform.application.submission.SubmissionDetails;
import com.sdv.platform.application.submission.SubmissionService;
import com.sdv.platform.domain.document.BusinessMetadataValue;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.submission.SubmissionExpectedDocument;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ingestion/submissions")
public class SubmissionController {
    private final SecurityContextFacade security;
    private final SubmissionService submissions;

    public SubmissionController(SecurityContextFacade security, SubmissionService submissions) {
        this.security = security;
        this.submissions = submissions;
    }

    @PostMapping
    public ResponseEntity<SubmissionResponse> create(@RequestBody CreateSubmissionRequest request) {
        IngestionSourcePrincipal principal = security.requireIngestionSourcePrincipal();
        SubmissionDetails result = submissions.create(
                principal.tenantId(), principal.sourceId(), request.toCommand(), principal.subject(), correlationId());
        HttpStatus status = result.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(SubmissionResponse.from(result));
    }

    @GetMapping("/{submissionId}")
    public SubmissionResponse get(@PathVariable UUID submissionId) {
        IngestionSourcePrincipal principal = security.requireIngestionSourcePrincipal();
        return SubmissionResponse.from(submissions.get(principal.tenantId(), principal.sourceId(), submissionId));
    }

    @GetMapping
    public SubmissionResponse getBySourceIdentity(@RequestParam String sourceSubmissionId) {
        IngestionSourcePrincipal principal = security.requireIngestionSourcePrincipal();
        return SubmissionResponse.from(submissions.getBySourceSubmissionId(
                principal.tenantId(), principal.sourceId(), sourceSubmissionId));
    }

    private static String correlationId() {
        return MDC.get("correlationId");
    }

    public record ExpectedDocumentRequest(String sourceDocumentId) { }

    public record CreateSubmissionRequest(
            String sourceSubmissionId,
            int expectedDocumentCount,
            List<ExpectedDocumentRequest> expectedDocuments,
            Map<String, JsonNode> metadata) {

        CreateSubmissionCommand toCommand() {
            List<String> manifest = expectedDocuments == null ? List.of() : expectedDocuments.stream()
                    .map(ExpectedDocumentRequest::sourceDocumentId)
                    .toList();
            Map<String, String> values = new LinkedHashMap<>();
            if (metadata != null) {
                metadata.forEach((key, value) -> {
                    if (value == null || value.isNull() || !value.isValueNode()) {
                        throw new IllegalArgumentException("Metadata field '" + key + "' must be a scalar JSON value.");
                    }
                    values.put(key, value.isTextual() ? value.textValue() : value.asText());
                });
            }
            return new CreateSubmissionCommand(sourceSubmissionId, expectedDocumentCount, manifest, values);
        }
    }

    public record ExpectedDocumentResponse(String sourceDocumentId, String state) {
        static ExpectedDocumentResponse from(SubmissionExpectedDocument expected) {
            return new ExpectedDocumentResponse(expected.sourceDocumentId(), expected.state().name());
        }
    }

    public record DocumentStatusResponse(
            UUID documentId,
            String sourceDocumentId,
            String originalFilename,
            String status) {
        static DocumentStatusResponse from(Document document) {
            return new DocumentStatusResponse(
                    document.documentId(), document.sourceDocumentId(),
                    document.originalFilename(), document.status().name());
        }
    }

    public record SubmissionProgressResponse(
            int expectedCount,
            int receivedCount,
            int missingCount,
            List<String> missingSourceDocumentIds) { }

    public record SubmissionResponse(
            UUID submissionId,
            UUID sourceId,
            String sourceSubmissionId,
            int expectedDocumentCount,
            String status,
            List<ExpectedDocumentResponse> expectedDocuments,
            List<DocumentStatusResponse> documents,
            SubmissionProgressResponse progress,
            Map<String, String> metadata,
            boolean idempotentReplay) {

        static SubmissionResponse from(SubmissionDetails details) {
            Map<String, String> metadata = new LinkedHashMap<>();
            details.metadataValues().stream()
                    .sorted(java.util.Comparator.comparing(BusinessMetadataValue::fieldKey))
                    .forEach(value -> metadata.put(value.fieldKey(), value.storedValue()));

            SubmissionProgressResponse progress = details.progress() == null ? null :
                    new SubmissionProgressResponse(
                            details.progress().expectedCount(),
                            details.progress().receivedCount(),
                            details.progress().missingCount(),
                            details.progress().missingSourceDocumentIds());

            return new SubmissionResponse(
                    details.submission().submissionId(),
                    details.submission().sourceId(),
                    details.submission().sourceSubmissionId(),
                    details.submission().expectedDocumentCount(),
                    details.submission().status().name(),
                    details.expectedDocuments().stream().map(ExpectedDocumentResponse::from).toList(),
                    details.documents().stream().map(DocumentStatusResponse::from).toList(),
                    progress,
                    Map.copyOf(metadata),
                    details.idempotentReplay());
        }
    }
}
