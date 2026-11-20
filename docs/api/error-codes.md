# Vero error codes

Every refusal listed here comes back with a short, stable `code` and a plain `message`:

```json
{"code": "SDV-SEN-002", "message": "recordSensitivityLevel must be one of STANDARD, CONFIDENTIAL, HIGHLY_CONFIDENTIAL (exactly, in capitals); leave it out for no record level.", "timestamp": "2026-11-20T10:00:00Z"}
```

- **Format:** `SDV-<AREA>-<NNN>`. `SDV` is the product. `AREA` is three letters: `SEN` sensitivity, `ROL` roles, `HLD` legal hold, `APR` approvals, `INT` intake.
- **Stable:** a code never changes meaning once it is released. A code that is no longer used stays listed and is marked retired. Its number is never reused.
- **Messages** may be reworded. Programs should read the `code`, not the message.
- **Not yet coded:** errors that are not listed here still send a generic code (`BAD_REQUEST`, `CONFLICT`, `NOT_FOUND`, `FORBIDDEN` and so on). They get an `SDV-` code when their area is worked on.
- Other applications read this list, for example the Platform Console runbook. Keep one section per code in the layout below. The build checks that every code in the product is listed here.

## Sensitivity (SEN)

### SDV-SEN-001: unknown document level

- **HTTP status:** 400. Sent by the document upload (`POST /api/v1/ingestion/submissions/{id}/documents`).
- **Meaning:** the `X-SDV-Sensitivity-Level` header is not exactly `STANDARD`, `CONFIDENTIAL` or `HIGHLY_CONFIDENTIAL`. Lower case, mixed case, blanks and extra spaces are refused. Nothing was stored.
- **What the user should do:** send one of the three names exactly, in capitals. Or leave the header out for `STANDARD`.
- **What support should tell the customer:** the sending system sent a level Vero does not know. Ask whoever runs that system to correct the value it sends. The document was not stored, so the upload must be sent again.

### SDV-SEN-002: unknown record level

- **HTTP status:** 400. Sent by create submission (`POST /api/v1/ingestion/submissions`).
- **Meaning:** `recordSensitivityLevel` in the submission JSON is not exactly one of the three names. That includes values that aren't a JSON string, such as `1` or `null`. Nothing was stored.
- **What the user should do:** send one of the three names exactly, or leave the field out when the record has no level of its own.
- **What support should tell the customer:** the sending system sent a record level Vero does not know. Ask whoever runs that system to correct it. The submission was not created, so it must be sent again.

### SDV-SEN-003: document retry with a different level

- **HTTP status:** 409. Sent by the document upload.
- **Meaning:** this document was already received with another sensitivity level. A retry must repeat the level of the first upload, and the stored level is never changed by a retry. The message names the stored level.
- **What the user should do:** retry with the level named in the message. If the level really has to be different, deliver it as a new document.
- **What support should tell the customer:** the document is already safely stored with the level it was first sent with. A retry cannot change that level.

### SDV-SEN-004: submission retry with a different record level

- **HTTP status:** 409. Sent by create submission.
- **Meaning:** a submission with this `sourceSubmissionId` already exists. The retry is the same in everything except `recordSensitivityLevel`. The message says what the first request sent: a level, or none.
- **What the user should do:** retry with the same `recordSensitivityLevel` as the first request, or with none if the first had none. To raise the record's level, send it on a new submission for the same record.
- **What support should tell the customer:** the submission already exists. A retry has to match it exactly. A record's level can be raised by a later delivery for the same record, never by a retry.

### SDV-SEN-005: record level on a document upload

- **HTTP status:** 400. Sent by the document upload.
- **Meaning:** the upload carried `X-SDV-Record-Sensitivity-Level`. A record level is only accepted on the submission. Nothing was stored.
- **What the user should do:** remove the header. Send the level as `recordSensitivityLevel` when creating the submission.
- **What support should tell the customer:** the sending system put the record level in the wrong place. It belongs on the submission, not on each document. The document was not stored.

### SDV-SEN-006: document level on the submission

- **HTTP status:** 400. Sent by create submission.
- **Meaning:** the submission JSON carried `sensitivityLevel`. A document's own level is set on each document upload. The submission was not created.
- **What the user should do:** remove `sensitivityLevel` from the submission. Send it as the `X-SDV-Sensitivity-Level` header on each document upload. Use `recordSensitivityLevel` for a level that covers the whole record.
- **What support should tell the customer:** the sending system put the document level in the wrong place. The submission was not created and must be sent again.

### SDV-SEN-007: identifier change refused while records have a level

- **HTTP status:** 409. Sent by the field settings (`PUT /api/v1/admin/ingestion-sources/{id}/metadata-fields/{field}`, `PUT /api/v1/admin/ingestion-sources/{id}/source-fields/{field}` and `PUT /api/v1/admin/metadata-sources/{id}/fields/{field}`).
- **Meaning:** some records of this sending system have a sensitivity level, and the change would alter how records are grouped. That means making a field an identifier or not, or changing an identifier's normalization. Later documents would then fall into records without the level. The message gives the number of records with a level. Nothing was changed.
- **What the user should do:** keep the identifier settings as they are. Labels, search settings and the other field settings can still be changed.
- **What support should tell the customer:** this protects restricted records from losing their restriction. A change to identifiers needs a planned migration of those levels. Raise it with the Vero team; there is no setting to override it.
