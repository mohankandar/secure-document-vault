# SDV Public Ingestion Contract — V1

This directory is the producer-facing compatibility surface for SDV V1.

- `sdv-ingestion-v1.openapi.json` — authoritative HTTP/OpenAPI contract.
- `schemas/sdv-ingestion-submission-v1.schema.json` — portable JSON Schema for submission creation.
- `examples/` — producer-owned JSON examples.

## Compatibility rules

1. Producers integrate over HTTP only. They do not import SDV Java classes, share SDV database/schema objects, or depend on SDV internal IDs.
2. `sourceSubmissionId` and, when supplied, `sourceDocumentId` are stable producer-owned identities. `submissionId` and `documentId` are opaque SDV identities.
3. Submission creation is safely replayable only when the same `sourceSubmissionId` is sent with the same canonical request. A conflicting replay fails closed.
4. PDFs are uploaded independently. Reuse the same producer document identity and `Idempotency-Key` after an ambiguous network/ACK failure.
5. Metadata is source-configured. Producers send only fields configured for their ingestion source; metadata values must be scalar JSON values.
6. Production producer authentication uses the deployment's OAuth2/OIDC bearer identity. Tenant/source identity comes from the validated token, not unsigned request headers.
7. `X-SDV-Tenant-Id` and `X-SDV-Source-Id` are local-development test adapters only and are deliberately absent from the public OpenAPI security contract.
8. Within V1, producers must ignore unknown response fields. SDV will not remove documented required fields or change their meaning without a versioned contract change.
9. Producers must not depend on database tables, Java enum ordinals, storage paths, key names, or implementation-specific logs.

## Sensitivity level

Each document carries a sensitivity level: `STANDARD`, `CONFIDENTIAL` or `HIGHLY_CONFIDENTIAL` (lowest to highest).

- Set it on the document upload with the header `X-SDV-Sensitivity-Level`. Leave it out and the document is `STANDARD`.
- Any other value is refused with `400 BAD_REQUEST`, and nothing is stored. That includes a blank value and lower-case or mixed-case names (`confidential`, `Confidential`).
- A retry of the same document must send the same level as the first upload. A different level, or a missing one where the first upload had one, is refused with `409 CONFLICT`; the stored level never changes.
- The upload and document list responses return `sensitivityLevel`.
- The submission request does not take a document level: `sensitivityLevel` in its JSON is refused with `400`.

### Record level

A record (a contract, a patient file) can have a level of its own. A record is the submissions of one sending system that share the same identifier values, compared as stored after normalization. A submission without identifier values is a record on its own.

- Send it as `recordSensitivityLevel` in the submission JSON. It is optional; the same three names, exactly.
- Any other value is refused with `400 BAD_REQUEST` and nothing is stored. That includes a blank, lower-case or mixed-case value, and a value that isn't a JSON string (`1`, `null`).
- The header `X-SDV-Record-Sensitivity-Level` on a document upload is refused with `400`. The submission is the only place to send a record level.
- A delivery can set a record's first level or raise it. It never lowers it: a lower or equal value is ignored and written to the audit trail ("record level kept at …"), and the submission is still created.
- A retry of the same `sourceSubmissionId` must send the same `recordSensitivityLevel` as the first request (or none, if the first had none). Otherwise it is refused with `409 CONFLICT`.
- A document's **effective level** is the higher of its own level and its record's level. Documents that arrive later for the record get the record's level through this rule; nothing is copied onto them.
- While any record of a sending system has a level, an administrator can't change which fields are that system's identifiers, or how an identifier is normalized (`409`).

### Error codes

Each sensitivity refusal above has a stable `code` in the error body, `SDV-SEN-001` to `SDV-SEN-007`. For example, an unknown `X-SDV-Sensitivity-Level` returns `SDV-SEN-001`, where it used to return `BAD_REQUEST`. [error-codes.md](error-codes.md) lists every code with its meaning, what to do, and what support tells the customer. Read the code, not the message.

The sample under `samples/third-party-producer/` demonstrates the contract with raw HTTP calls only.
