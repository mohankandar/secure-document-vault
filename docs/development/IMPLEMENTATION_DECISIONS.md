# SDV implementation decisions accepted before fresh development

These are implementation clarifications that preserve the frozen V1 product behavior.

## 1. LexaVault / SDV boundary

LexaVault is a processing/redaction product and may deliver an already-approved/already-redacted final PDF to SDV through SDV's public API contract.

SDV is final secure custody. Its normal ingestion path does not repeat LexaVault OCR, AI discovery, review, or redaction processing. SDV corrective redaction remains a later exception/safety-net capability for an authorized Document Steward.

There is no shared code, database/schema, internal identity, or runtime dependency between the products.

## 2. Unit execution dependency clarification

The product unit catalogue remains authoritative, but secure durable receipt cannot be frozen before encryption/key protection and storage capabilities exist.

Implementation execution therefore uses this dependency-aware sequence around ingestion:

`Unit 5 -> Unit 7 -> Unit 8 -> Unit 6 -> Unit 9 -> Unit 10`

This changes implementation order only; it does not change V1 behavior or requirement ownership.

## 3. Idempotency identity direction

For later ingestion units:

- submission source identity: `(tenantId, sourceId, sourceSubmissionId)`;
- when supplied, document source identity: `(tenantId, sourceId, sourceSubmissionId, sourceDocumentId)`;
- when `sourceDocumentId` is absent, an explicit producer idempotency key is required for safely retryable document creation;
- a strong content fingerprint is used for integrity/exact-duplicate detection, not as the sole business/document identity.

This prevents an intentionally repeated identical PDF from being silently merged with a network retry.

## 4. Metadata-definition history direction

For Units 1 and 3:

- `fieldKey` is stable identity;
- display labels and validation/search behavior may evolve;
- historical definitions remain referenceable rather than being destructively deleted;
- stored submissions retain enough definition/version context to interpret historical metadata correctly;
- configuration changes do not rewrite historical values.

## 5. Viewer security interpretation

View permission and download permission are separate application capabilities. SDV will use session/cache/stream protections, but V1 does not claim impossible browser DRM or screenshot prevention.

## 6. Checkpoint A source-authentication boundary

Unit 5 needs a source identity before Unit 21 provides production OAuth2/JWT/mTLS adapters. The local profile therefore supports a distinct `IngestionSourcePrincipal` selected with `X-SDV-Source-Id`. It is not an SDV user role and cannot call user/admin services that require `SdvPrincipal`.

The source service still resolves the source by `(tenantId, sourceId)` and requires it to be ACTIVE. Outside the `local` profile this header adapter does not exist. Unit 21 must bind trusted identity-provider claims/credentials to the same logical source principal.

## 7. Submission create idempotency

A create request is identified by `(tenantId, sourceId, sourceSubmissionId)`. The first successful create stores a SHA-256 fingerprint over the canonical create request (declared expected count, stable manifest entries and supplied metadata). Exact retry returns the same SDV `submissionId`; a different request reusing the same source identity fails with conflict. PostgreSQL `ON CONFLICT` handling makes the create race-safe.

## 8. SDV-ENC-001 initial envelope

Checkpoint A defines version 1 as bounded AES-256-GCM authenticated chunks with a random per-document nonce prefix, monotonic chunk index, tenant-bound AAD, and an authenticated final marker. The primitive is JCA AES/GCM; SDV owns only the versioned envelope/framing required for streaming and crypto agility.

A document data key is unique per encryption operation. Vault Transit is the initial KeyProtectionProvider adapter; no local plaintext master key is introduced as a fallback.

## 9. Local storage volume root immutability

A configured filesystem volume root/provider is immutable after creation. Existing objects remain tied to the original `storageVolumeId` + opaque object key. V1 does not silently move/rebalance objects when capacity is added.

Filesystem volume roots may not overlap other configured roots, including across tenants. Configuration changes are serialized before overlap validation so concurrent creates cannot establish overlapping roots.
