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

### SDV-SEN-008: restricted document

- **HTTP status:** 403. Sent by every request on one document: the viewer (`GET /api/v1/business/documents/{id}/viewer`), page images (`…/viewer/pages/{n}`), page previews (`…/viewer/previews`), text search in it (`…/viewer/search`), download (`…/download`), print (`…/print-event`), corrective redaction (`…/redactions`), tags and comments (`…/tags`, `…/comments`, read and add) and its activity (`…/activity`).
- **Meaning:** the person's role can't open documents at this document's level. The level checked is the effective one: the higher of the document's own level and its record's level. Confidential needs a Document steward or Restricted steward; Highly Confidential needs a Restricted steward. The message is always "This document is restricted." and says nothing else about the document. The refusal is audited as `DOCUMENT_ACCESS_REFUSED` (who, when, the document ID, its level and what was tried).
- **What the user should do:** nothing in Vero can change this for them. If they need the document for their work, ask someone who holds the right role.
- **What support should tell the customer:** this is working as intended. The record and its search fields stay visible to everyone with record access, but the document itself needs the role for its level. Role membership comes from the customer's own sign-in groups, so a change is made there by their IT team, not in Vero.

### SDV-SEN-009: Highly Confidential print or download needs approval

- **HTTP status:** 403. Sent by download (`GET /api/v1/business/documents/{id}/download`) and print (`POST …/print-event`).
- **Meaning:** the document's effective level is Highly Confidential. Printing or downloading one needs a Business Admin's approval, and this version of Vero can't give one yet, so it is refused even for a Restricted steward, who can still open and read it. The refusal is audited as `DOCUMENT_ACCESS_REFUSED`.
- **What the user should do:** read the document in the viewer. Approved printing and downloading comes in a later version.
- **What support should tell the customer:** this is intended. Highly Confidential documents can't leave Vero until the approval step is available.

## Roles (ROL)

### SDV-ROL-001: permission a role may never have

- **HTTP status:** 400. Sent by Roles & permissions (`PUT /api/v1/admin/role-capabilities`).
- **Meaning:** the change would give a role a permission that Vero's separation rules forbid. Business Admin, Tenant IT Admin and Platform Operations can never be given `VIEW_CONFIDENTIAL`, `VIEW_HIGHLY_CONFIDENTIAL` or `REQUEST_SENSITIVE_ACCESS`. Document Steward and Restricted Steward can never be given `APPROVE_SENSITIVE_ACCESS`. The message names the role and the permission. Nothing was changed, for any role in the request. The refusal is audited.
- **What the user should do:** leave that permission off for that role. A person who needs it must be given a role that has it.
- **What support should tell the customer:** this is a fixed safety rule, not a setting. People who look after the system or approve requests must not also read restricted documents, and the people who read them must not approve their own requests.

### SDV-ROL-002: role whose permissions are fixed

- **HTTP status:** 400. Sent by Roles & permissions.
- **Meaning:** the change names a role whose permissions can't be changed in Vero: Restricted Steward, Business Admin, Tenant IT Admin or Platform Operations. Only Business User and Document Steward can be changed there. Nothing was changed. The refusal is audited.
- **What the user should do:** change only Business User and Document Steward.
- **What support should tell the customer:** these roles have a fixed set of permissions. Who holds them is decided in the customer's sign-in system (its groups), not in Vero.

### SDV-ROL-003: permission that can't be changed here

- **HTTP status:** 400. Sent by Roles & permissions.
- **Meaning:** the change names a permission that isn't one of those Roles & permissions can switch (download, print, add tags, add comments, permanently redact), or a name Vero doesn't know. Nothing was changed. The refusal is audited.
- **What the user should do:** send only the permissions the screen shows.
- **What support should tell the customer:** the other permissions come with the role and can't be switched on or off per customer. If the request came from a script, the script sent a name the screen doesn't offer.

### SDV-ROL-004: sign-in with roles that can never be held together

- **HTTP status:** 401 at sign-in. The code starts the error description of the refused sign-in (there is no JSON body at sign-in).
- **Meaning:** the person's sign-in groups give Restricted Steward together with Business Admin, Tenant IT Admin or Platform Operations. Vero gives neither of the conflicting roles, and here no other role was left, so the sign-in was refused. The conflict is recorded for the Tenant IT Admin (`GET /api/v1/admin/role-conflicts`) and audited as `ROLE_CONFLICT_DETECTED`. When the person has other roles too, they sign in with those only and get no error.
- **What the user should do:** ask your IT team to take you out of one of the two groups.
- **What support should tell the customer:** someone is in the sign-in group for Restricted Steward and in an admin group at the same time. Vero never allows that. Their IT team must remove the person from one of the groups; the role conflict list names the person.

### SDV-ROL-005: role conflict could not be recorded

- **HTTP status:** 401 at sign-in. The code starts the error description of the refused sign-in.
- **Meaning:** the sign-in named roles that can never be held together, and Vero could not record the conflict (for example the database was unavailable). A conflict is never left unrecorded, so the sign-in was refused.
- **What the user should do:** try again in a few minutes. If it keeps happening, ask your IT team to take you out of one of the two groups.
- **What support should tell the customer:** Vero could not write to its database at that moment. Check the server's health. The person is also in two groups that can't be held together; their IT team should fix that too.
