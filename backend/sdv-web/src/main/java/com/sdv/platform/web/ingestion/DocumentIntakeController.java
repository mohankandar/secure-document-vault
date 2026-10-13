package com.sdv.platform.web.ingestion;

import com.sdv.platform.application.document.DocumentIntakeService;
import com.sdv.platform.application.document.DocumentReceipt;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.security.auth.IngestionSourcePrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ingestion/submissions/{submissionId}/documents")
public class DocumentIntakeController {
    private final SecurityContextFacade security;
    private final DocumentIntakeService intake;

    public DocumentIntakeController(SecurityContextFacade security, DocumentIntakeService intake) {
        this.security = security;
        this.intake = intake;
    }

    @PostMapping(consumes = "application/pdf")
    public ResponseEntity<DocumentReceiptResponse> upload(
            @PathVariable UUID submissionId,
            @RequestHeader(name = "X-SDV-Source-Document-Id", required = false) String sourceDocumentId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(name = "X-SDV-Filename") String filename,
            HttpServletRequest request) throws IOException {
        IngestionSourcePrincipal principal = security.requireIngestionSourcePrincipal();
        DocumentReceipt receipt = intake.receive(
                principal.tenantId(),
                principal.sourceId(),
                submissionId,
                sourceDocumentId,
                idempotencyKey,
                filename,
                request.getContentType(),
                request.getContentLengthLong(),
                request.getInputStream(),
                principal.subject(),
                correlationId());

        HttpStatus status = receipt.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(DocumentReceiptResponse.from(receipt));
    }

    @GetMapping
    public List<DocumentSummaryResponse> list(@PathVariable UUID submissionId) {
        IngestionSourcePrincipal principal = security.requireIngestionSourcePrincipal();
        return intake.list(principal.tenantId(), principal.sourceId(), submissionId).stream()
                .map(DocumentSummaryResponse::from)
                .toList();
    }

    @PostMapping("/{documentId}/retry-finalization")
    public DocumentSummaryResponse retryFinalization(
            @PathVariable UUID submissionId,
            @PathVariable UUID documentId) {
        IngestionSourcePrincipal principal = security.requireIngestionSourcePrincipal();
        return DocumentSummaryResponse.from(intake.retryFinalization(
                principal.tenantId(), principal.sourceId(), submissionId, documentId,
                principal.subject(), correlationId()));
    }

    private static String correlationId() {
        return MDC.get("correlationId");
    }

    public record DocumentReceiptResponse(
            UUID documentId,
            String sourceDocumentId,
            String receiptAcknowledgement,
            String currentStatus,
            String contentSha256,
            long contentSizeBytes,
            boolean idempotentReplay,
            boolean exactDuplicateContent) {
        static DocumentReceiptResponse from(DocumentReceipt receipt) {
            Document document = receipt.document();
            return new DocumentReceiptResponse(
                    document.documentId(),
                    document.sourceDocumentId(),
                    receipt.receiptAcknowledgement(),
                    document.status().name(),
                    document.contentSha256(),
                    document.contentSizeBytes() == null ? 0L : document.contentSizeBytes(),
                    receipt.idempotentReplay(),
                    receipt.exactDuplicateContent());
        }
    }

    public record DocumentSummaryResponse(
            UUID documentId,
            String sourceDocumentId,
            String originalFilename,
            String status,
            long contentSizeBytes,
            String contentSha256,
            int processingAttemptCount,
            String processingErrorCode) {
        static DocumentSummaryResponse from(Document document) {
            return new DocumentSummaryResponse(
                    document.documentId(),
                    document.sourceDocumentId(),
                    document.originalFilename(),
                    document.status().name(),
                    document.contentSizeBytes() == null ? 0L : document.contentSizeBytes(),
                    document.contentSha256(),
                    document.processingAttemptCount(),
                    document.processingErrorCode());
        }
    }
}
