# Owner answers to report 0034 (2026-11-21)

Used when the fix tasks are written (after the V1 series is merged).

1. New areas approved: SYS, AUT, STO, KEY, PRC, DOC, SCH, RED, RET, AUD, ADM, IDP.
2. Proposed codes stay in `docs/api/error-codes.md` under `####` until they are sent.
3. **Replace** today's own codes (`DATABASE_*`, `PDF_TOO_LARGE`, `INVALID_PDF`, `EXPORT_TOO_LARGE`, `VIEWER_UNAVAILABLE`, `DOWNLOAD_UNAVAILABLE`, `REDACTION_FAILED`, the three processing codes) with SDV codes now; no customer is live. Update `problemWords.ts` and `auditWords.ts` with them.
4. DOC-009: a Highly Confidential one-time grant is used **when the first byte is sent**. A failure before anything leaves Vero gives the grant back; once part of the file has left, it is used.
5. Status changes approved (409→503 no storage, 409→500 internal checks, 401→403 wrong kind of sign-in, 404/409→403 tenant). Note them for the shared delivery contract.

Order after the merge: 0035 (steady tour), then fix tasks 1 (error plumbing) to 10.
