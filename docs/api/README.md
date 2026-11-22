# Vero ingestion API, v1: the contract for sending systems

A sending system (LexaVault, a scanning line, any producer) builds against this folder:

- `sdv-ingestion-v1.openapi.json`: **the contract.** Every operation, header, field and answer, with the code and whether to send again. The build checks it against the running API (`IngestionContractTest`), so the two can't drift apart.
- `schemas/sdv-ingestion-submission-v1.schema.json`: the same submission body as a portable JSON Schema, to check what you send.
- `examples/`: a submission with two documents.
- [error-codes.md](error-codes.md): every code, what it means, what to do, what support tells the customer.

## Versioning

- **The path version is the promise.** Everything under `/api/v1/` stays backward compatible.
- **Allowed within v1:** new optional request fields or headers; new answer fields; new codes; new values in an answer's state lists (`DocumentState` and the like); clearer descriptions.
- **Needs `/v2`**, and a notice period agreed with the sending systems before `/v1` changes: removing or renaming a field, header or operation; making something optional required; changing a status code or what a code means; refusing a request that `/v1` accepts.
- **What senders do in return:** ignore answer fields you don't know; treat an unknown code by its status (4xx: don't send it again unchanged; 503: wait and send again); treat an unknown document state as "on the way"; don't depend on message wording, database tables, enum order, storage paths, key names or logs.
- `info.version` in the OpenAPI file follows semantic versioning: patch for wording, minor for additions, major only together with a new path version. Each change gets an entry in the changelog at the end of this file. The build checks that `info.version` is the newest entry.

## Rules that don't change

1. Integrate over HTTP only. No Vero classes, database objects or internal IDs.
2. `sourceSubmissionId` and `sourceDocumentId` are your stable IDs. `submissionId` and `documentId` are Vero's; treat them as opaque.
3. A create is safe to send again: the same `sourceSubmissionId` with exactly the same body is answered 200 with the same submission. A different body is refused (409).
4. An upload is safe to send again: the same `X-SDV-Source-Document-Id` (or `Idempotency-Key`) with the same file is answered 200 and stored once.
5. Send only the metadata fields Tenant IT set up for your sending system, each as one JSON value (string, number, true or false).
6. The tenant and the sending system come from your verified token, never from a header. `X-SDV-Tenant-Id` and `X-SDV-Source-Id` work only in a developer's local mode and are not part of this contract.
7. Vero ignores submission fields it doesn't know. Send only documented fields: a later version may give an unknown name a meaning.

## Sender's guide

### 1. Set up the sending system

In Vero, Tenant IT creates the sending system (Integrations) and its metadata fields. Note the **tenant ID** and the **sending system's ID** (both UUIDs).

In the customer's sign-in server, create one client for the sending system:

| Setting | Value |
|---|---|
| Grant | Client credentials only (no browser sign-in, no user accounts) |
| Audience | The token's `aud` must contain Vero's audience (`SDV_AUTH_JWT_AUDIENCE`, for example `sdv-api`) |
| Claim: tenant ID | `sdv_tenant_id` (or the name in `SDV_AUTH_JWT_TENANT_CLAIM`) = the tenant ID, as a string |
| Claim: source ID | `sdv_source_id` (or the name in `SDV_AUTH_JWT_SOURCE_ID_CLAIM`) = the sending system's ID, as a string |
| Groups | None. A token with both the source-ID claim and the groups claim is refused (`SDV-AUT-005`) |
| Token lifetime | Short, for example 5 minutes. If Vero sets `SDV_AUTH_JWT_MAX_TOKEN_LIFETIME_SECONDS`, at most that |

The token's issuer must be Vero's `SDV_AUTH_JWT_ISSUER`; `sub` is the client's own ID. The source-ID claim is what makes a token a sending system's: without it Vero reads the token as a person's sign-in and refuses it on these addresses (`SDV-AUT-006`, `SDV-AUT-007` or `SDV-AUT-002`). The OpenAPI file lists every claim and what each missing or wrong one returns (`bearerAuth`, `x-sdv-required-claims`).

Report 0040 (`docs/work/0040-report.md`) shows this set up in Keycloak, step by step.

**Planned change (at Vero's switch-over to platform-core).** Vero will then find the organisation and the sending system from the verified client ID (platform-core `MachineEntityBindings`). Tokens will need only the standard audience and subject. Tenant or source claims may still be sent, but if present they must match the binding, or the token is refused. The "tenant" claim will be renamed "entity" at the same time (codes `ENT-…`). For a sender this is a relaxation, not a break: a token that works today keeps working as long as its claims match the binding.

### 2. Before sending: is Vero ready?

`GET /actuator/health/readiness` needs no token. `200 {"status":"UP"}`: send. `503`: wait and ask again (Vero's database or the sign-in server can't be reached, or an upgrade is under way). A 503 here never means something you sent was refused.

### 3. Worked example: one submission, two documents

Get a token (client credentials; Keycloak shown, any OAuth2 server works the same way):

```
POST https://login.example.com/realms/acme/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id=lexavault-intake&client_secret=<from your secret store>
```

The answer's `access_token` goes in `Authorization: Bearer <token>` on every call below.

Create the submission:

```
POST /api/v1/ingestion/submissions
Content-Type: application/json
X-Correlation-Id: lv-case-2026-00017-create

{"sourceSubmissionId": "producer-case-2026-00017",
 "expectedDocumentCount": 2,
 "expectedDocuments": [{"sourceDocumentId": "producer-doc-001"}, {"sourceDocumentId": "producer-doc-002"}],
 "metadata": {"contractNumber": "990023450001", "memberName": "Example Customer"},
 "recordSensitivityLevel": "CONFIDENTIAL"}
```

`201` with `submissionId`, `status: OPEN` and `progress: {expectedCount: 2, receivedCount: 0, missingCount: 2, ...}`. If the answer is lost, send the same request again: `200`, the same `submissionId`, `idempotentReplay: true`.

Upload each PDF to that submission:

```
POST /api/v1/ingestion/submissions/{submissionId}/documents
Content-Type: application/pdf
X-SDV-Filename: contract.pdf
X-SDV-Source-Document-Id: producer-doc-001
X-SDV-Sensitivity-Level: CONFIDENTIAL

<the PDF bytes>
```

`201` with `documentId`, `receiptAcknowledgement: RECEIVED` and `contentSha256`. The second document goes the same way with `producer-doc-002`. Then `GET /api/v1/ingestion/submissions/{submissionId}` shows `receivedCount: 2` and each document's state; `AVAILABLE` means done.

### 4. Reading the answer

Every error has one body: `{"code", "message", "reference", "timestamp"}`. Read the `code`, not the message. The `reference` is the `X-Correlation-Id` answer header; give it to support. In the OpenAPI file each answer lists its codes (`x-sdv-codes`), and each status-and-code pair has a rule for sending again (`x-sdv-answers`):

| Rule | What to do |
|---|---|
| `yes` | Wait (back off, for example 2 s doubling up to 5 minutes; check readiness) and send exactly the same request again. Safe: nothing is stored twice. |
| `new-token` | Get a new token and send once more. If refused again, stop and alert. |
| `no` | A refusal. The same request gets the same answer. Correct it, or drop it and alert. |
| `after-fix` | A person must fix Vero's or the sign-in server's setup first. Keep the delivery, alert, and send it again after the fix. |

The answers a sender can get:

| Status | Codes | Rule |
|---|---|---|
| 201 | Created (submission) or received (document) | done |
| 200 | Already received (a retry: `idempotentReplay: true`), or `DUPLICATE_ALREADY_STORED` (the same file for the same record: `duplicate: true`) | done; don't send again |
| 400 | `SDV-INT-006`, `-007`, `-008`, `-009`, `-012`, `-013`, `-015`, `-016`; `SDV-SEN-001`, `-002`, `-005`, `-006`; `SDV-SYS-015` | no |
| 401 | `SDV-AUT-001`, `SDV-AUT-003` | new-token |
| 401 | `SDV-AUT-005`, `SDV-AUT-006`, `SDV-AUT-007`, `SDV-SYS-006` | after-fix |
| 403 | `SDV-AUT-002`, `SDV-AUT-009`, `SDV-INT-005` | after-fix |
| 404 | `SDV-INT-004`, `SDV-INT-018` | no |
| 406 | `SDV-SYS-015`: the Accept header. Vero finds it only when it writes the answer, so the work may be done; send again with `Accept: application/json` | no |
| 409 | `SDV-INT-011`, `-016`, `-017`, `-018`; `SDV-SEN-003`, `-004` | no |
| 409 | `CONFLICT` (upload only; no SDV code yet: a storage setup problem in Vero) | after-fix |
| 413 | `SDV-INT-014` | no |
| 415 | `SDV-SYS-015` | no |
| 429 | Never from Vero; a gateway in front of it may send it | yes |
| 500 | `SDV-SYS-013`, `SDV-SYS-017` | yes |
| 500 | `SDV-SYS-016`, `SDV-INT-010` | after-fix |
| 503 | `SDV-SYS-001`, `SDV-SYS-002`, `SDV-SYS-014`, `SDV-AUT-004`, `SDV-STO-001` | yes |

Vero sends no `Retry-After` header; use your own back-off. The OpenAPI file says which operation gives which code.

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

## Sending the same document again

Vero tells three cases apart.

| Case | What Vero does | Answer |
|---|---|---|
| 1. A retry of the same delivery: the same `sourceDocumentId` in the same submission (or the same `Idempotency-Key`, when there is no `sourceDocumentId`) | Nothing new is stored. | `200`, the original document, `receiptAcknowledgement: RECEIVED`, `idempotentReplay: true` |
| 2. **An exact duplicate**: the same file (same SHA-256 checksum) from the same sending system, for the same record, in a new delivery (another `sourceDocumentId` or `Idempotency-Key`, in this submission or another one of the record) | No second copy is stored. The arrival is recorded and written to the audit trail. | `200`, the `documentId` of the document Vero already has, `receiptAcknowledgement: DUPLICATE_ALREADY_STORED`, `duplicate: true` |
| 3. Anything else: another file (the same paper scanned again has another checksum), or the same file for another record | Stored as a new document. | `201`, `receiptAcknowledgement: RECEIVED`, `duplicate: false` |

For an exact duplicate (case 2):
- Treat it as delivered. Don't retry it and don't raise an alert.
- It takes its place in the submission. If the submission listed it in `expectedDocuments`, that entry becomes `RECEIVED`, and `progress.receivedCount` counts it ("2 of 4 received").
- It still needs a place: in a submission that has already received all its expected documents, it is refused like any other upload.
- If it carries a higher `X-SDV-Sensitivity-Level` than the document Vero has, that document is raised to it, and the raise is audited as made by the sending system. A lower or equal level is ignored (and noted in the audit trail). The answer's `sensitivityLevel` is the document's level after this.
- A retry of the duplicate delivery itself is case 1: the same answer again, with `idempotentReplay: true`. It must send the same file and the same level, or it is refused with `409` (`SDV-SEN-003` for another level).
- If the earlier document has been deleted (or is being deleted) by the retention rules, the new file is stored as a new document.
- `exactDuplicateContent` has the same value as `duplicate`. Before task 0031 it was `true` when a second copy had been stored; that no longer happens.

The record is the same grouping as for record levels (above): the submissions of one sending system that share the same identifier values. The same file sent by another sending system, or under another record, is stored normally; no flag is set.

**A corrected document sent again** (task 0032). A Document steward may permanently correct (redact) a document; its checksum then changes. If the sending system later sends the original, uncorrected file again for the same record, Vero treats it as case 2 for the corrected document: nothing is stored (so the redacted content does not come back), and the answer is the same `200` with the corrected document's `documentId` and `receiptAcknowledgement: DUPLICATE_ALREADY_STORED`. The checksum is compared with every checksum the record's documents had before a correction. The arrival is recorded and audited as `DOCUMENT_UNCORRECTED_COPY_RECEIVED`, and the record's Document stewards and Restricted stewards are told ("An uncorrected copy of a corrected document was sent again; nothing was stored"). Since task 0033 the notice is stored (`steward_notices`): each steward sees it, wherever they sign in, until they mark it read (`GET /api/v1/business/intake-notices`, `POST /api/v1/business/intake-notices/read`). The same scope applies: tenant, sending system and record. The rules above (its place in the submission, levels, retries, deleted documents) apply in the same way.

**Refusals are counted.** Each refused submission or upload (any `4xx` answer once the sending system is known) is recorded with the code it got (audit event `INTAKE_REFUSED`, with the request ID as sent but no file name or content), so the Business admin's Daily intake report counts it, also when no submission was created.

The sample under `samples/third-party-producer/` demonstrates the contract with raw HTTP calls only.

## Changelog

### 1.0.0 (task 0040, 2026-11-22). Release tag: `sdv-ingestion-v1.0.0`

The first contract that sending systems build against. Tagged `sdv-ingestion-v1.0.0` on the merge commit; platform-core pins its contract tests to that tag.

- Every operation lists every answer with its status, codes and retry rule (`x-sdv-codes`, `x-sdv-answers`, `x-sdv-retry`).
- Readiness (`/actuator/health/readiness`) documented as the "can I send now?" check.
- Sign-in (`bearerAuth`) in full: client-credentials tokens, the required claims, and what each missing or wrong claim returns.
- Corrections to the earlier, unversioned file, so it says what Vero does: `metadata` is optional; unknown submission fields are ignored, not refused; a malformed `Authorization` header is 401 `SDV-AUT-003` (not 400); 406 can come after the work is done; the upload's 409 can carry the generic `CONFLICT`; answer fields are always present (`required`); `DocumentState` lists every state.
- The build checks this file against the running API (`IngestionContractTest`).
