package com.sdv.platform.application.document;

import com.sdv.platform.domain.document.Document;

public record DocumentReceipt(
        Document document,
        String receiptAcknowledgement,
        boolean idempotentReplay,
        boolean exactDuplicateContent) {
}
