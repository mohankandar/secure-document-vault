package com.sdv.platform.application.approvals;

/**
 * Task 0028: PENDING until a Business Admin decides, or EXPIRED when nobody does in time. An APPROVED print or
 * download is a grant until it is USED once, or EXPIRED when its use window ends. An APPROVED lowering has been applied.
 */
public enum AccessRequestStatus { PENDING, APPROVED, DECLINED, USED, EXPIRED }
