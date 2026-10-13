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

The sample under `samples/third-party-producer/` demonstrates the contract with raw HTTP calls only.
