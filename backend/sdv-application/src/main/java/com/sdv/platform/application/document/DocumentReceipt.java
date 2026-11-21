package com.sdv.platform.application.document;

import com.sdv.platform.domain.document.Document;

/**
 * The answer to a document upload.
 * <p>Task 0031: exactDuplicateContent is true only for an exact duplicate (same file, same sending system, same record,
 * new delivery). Then nothing was stored: document is the one Vero already had, receiptAcknowledgement is
 * {@link DocumentIntakeService#DUPLICATE_ALREADY_STORED}, and deliverySourceDocumentId is the sourceDocumentId this
 * delivery was sent with (null when it had none). For every other receipt deliverySourceDocumentId is the document's.
 */
public record DocumentReceipt(
        Document document,
        String receiptAcknowledgement,
        boolean idempotentReplay,
        boolean exactDuplicateContent,
        String deliverySourceDocumentId) {
    public DocumentReceipt(Document document, String receiptAcknowledgement, boolean idempotentReplay,
                           boolean exactDuplicateContent) {
        this(document, receiptAcknowledgement, idempotentReplay, exactDuplicateContent, document.sourceDocumentId());
    }
}
