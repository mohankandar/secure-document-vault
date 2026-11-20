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
- **Meaning:** the document's effective level is Highly Confidential. Printing or downloading one needs a Business Admin's approval (task 0028): a grant for this person, this document and this kind (print or download), approved, not used yet, and still inside its use window (24 hours after approval unless the customer's setting says otherwise). There is no such grant: none was asked for or approved, it was already used once, or its time ran out. Only a Restricted steward can hold one. The refusal is audited as `DOCUMENT_ACCESS_REFUSED`.
- **What the user should do:** a Restricted steward asks for approval (`POST /api/v1/business/documents/{id}/access-requests` with kind `PRINT` or `DOWNLOAD` and a reason), waits for a Business Admin to approve it, and then prints or downloads once within the time. Each approval is good for one print or one download.
- **What support should tell the customer:** this is intended. Highly Confidential documents leave Vero only with an approval, once. The person's own request list (`GET /api/v1/business/access-requests`) shows whether the request is waiting, approved, used, declined or expired.

### SDV-SEN-010: changing a level needs a Steward

- **HTTP status:** 403. Sent by raise and request-lower, for a document (`POST /api/v1/business/documents/{id}/sensitivity/raise`, `…/sensitivity/lower-request`) and for a record (`POST /api/v1/business/records/{ref}/sensitivity/raise`, `…/sensitivity/lower-request`).
- **Meaning:** the person's role can't change sensitivity levels. A Document steward or Restricted steward can. Nothing was changed. The refusal is audited as `AUTHORIZATION_DENIED` (capability `CHANGE_SENSITIVITY_LEVEL`).
- **What the user should do:** ask a Steward to raise the level, or to ask for it to be lowered.
- **What support should tell the customer:** this is working as intended. Role membership comes from the customer's own sign-in groups, so a change is made there by their IT team, not in Vero.

### SDV-SEN-011: unknown level asked for

- **HTTP status:** 400. Sent by raise and request-lower (document and record).
- **Meaning:** `level` is missing or not exactly `STANDARD`, `CONFIDENTIAL` or `HIGHLY_CONFIDENTIAL`. Lower case, mixed case and extra spaces are refused. Nothing was changed.
- **What the user should do:** choose one of the three levels and send it again.
- **What support should tell the customer:** the screen or program sent a level Vero doesn't know. Ask them to choose one of the three levels.

### SDV-SEN-012: level change reason missing or the wrong length

- **HTTP status:** 400. Sent by raise and request-lower (document and record).
- **Meaning:** the `reason` is missing, or shorter than 3 or longer than 500 characters once spaces at either end are removed. Nothing was changed.
- **What the user should do:** write why the level must change, in 3 to 500 characters, and send it again.
- **What support should tell the customer:** every level change needs a reason, because the audit records it with the change. Ask them to give one of 3 to 500 characters.

### SDV-SEN-013: a raise must go higher

- **HTTP status:** 409. Sent by raise (document and record).
- **Meaning:** the level asked for is not higher than the level now. For a document, "now" is its effective level: the higher of its own level and its record's level. For a record with no level of its own, any level above Standard is a raise. The message names the level now. Nothing was changed.
- **What the user should do:** choose a higher level. To make it lower, ask for a lowering instead.
- **What support should tell the customer:** the document or record is already at that level or higher. Lowering is a separate request that a Business Admin approves.

### SDV-SEN-014: a lowering must go lower

- **HTTP status:** 409. Sent by request-lower (document and record).
- **Meaning:** the level asked for is not lower than the level now. For a document, that is the document's own level. For a record, it is the record's level; a record with no level can't be lowered. The message names the level now. No request was made.
- **What the user should do:** choose a lower level. If the document is restricted only through its record's level, ask for the record's level to be lowered instead.
- **What support should tell the customer:** nothing can be lowered below where it is. If a document's own level is already low, its record's level is what restricts it.

### SDV-SEN-015: a Steward can lower only from Confidential

- **HTTP status:** 403. Sent by request-lower (document and record).
- **Meaning:** a Document steward may ask to lower a level only when it is Confidential now. This one is Highly Confidential (or, for a document, Standard). Only a Restricted steward may ask to lower from Highly Confidential. No request was made.
- **What the user should do:** ask a Restricted steward to request the lowering.
- **What support should tell the customer:** this is intended: Highly Confidential material is handled by Restricted stewards.

### SDV-SEN-016: no such document or record

- **HTTP status:** 404. Sent by raise and request-lower (document and record).
- **Meaning:** the document ID or record reference is malformed, or names nothing in the person's own organization. Nothing was changed.
- **What the user should do:** open the record again from search and try again from there.
- **What support should tell the customer:** the reference sent doesn't match a document or record of theirs. Search gives the right one.

### SDV-SEN-017: only an available document's level can be changed

- **HTTP status:** 409. Sent by raise and request-lower for a document.
- **Meaning:** the document is still being stored, failed to store, or was deleted by the retention cleanup (or is being deleted). Its level can't be changed. Nothing was changed.
- **What the user should do:** wait until the document is available, or, if it was deleted, nothing.
- **What support should tell the customer:** a document's level is changed once it is stored and available. A deleted document is gone.

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

## Legal hold (HLD)

Sent by the Business Admin's legal-hold endpoints, `/api/v1/admin/legal-holds` (task 0027). A document on legal hold is never deleted by the daily retention cleanup; corrective redaction still works on it.

### SDV-HLD-001: legal holds need the Business Admin

- **HTTP status:** 403. Sent by every legal-hold endpoint: find documents (`GET /api/v1/admin/legal-holds/documents`), list holds (`GET /api/v1/admin/legal-holds`), place (`POST /api/v1/admin/legal-holds`) and release (`POST /api/v1/admin/legal-holds/{holdId}/release`).
- **Meaning:** the person's role does not include managing legal holds. Only the Business Admin has it, and Roles & permissions can't give it to anyone else. Nothing was read or changed. The refusal is audited as `AUTHORIZATION_DENIED` (capability `MANAGE_LEGAL_HOLD`).
- **What the user should do:** ask a Business Admin to place or release the hold.
- **What support should tell the customer:** this is working as intended. Legal holds are a Business Admin task. Role membership comes from the customer's own sign-in groups, so a change is made there by their IT team, not in Vero.

### SDV-HLD-002: reason missing or the wrong length

- **HTTP status:** 400. Sent by place hold (`POST /api/v1/admin/legal-holds`).
- **Meaning:** the hold's `reason` is missing, or shorter than 3 or longer than 500 characters once spaces at either end are removed. Nothing was stored.
- **What the user should do:** write why the document must be kept (for example the case or inquiry it belongs to), in 3 to 500 characters, and place the hold again.
- **What support should tell the customer:** every hold needs a reason so the audit shows why a document was kept. Ask them to give one of 3 to 500 characters.

### SDV-HLD-003: identifier missing or too long

- **HTTP status:** 400. Sent by find documents (`GET /api/v1/admin/legal-holds/documents?identifier=…`).
- **Meaning:** `identifier` is missing, blank or longer than 300 characters. Nothing was looked up.
- **What the user should do:** enter the record's identifier (for example the contract or patient number) and look again.
- **What support should tell the customer:** documents are found by the record's primary identifier, the same number shown as the record elsewhere. Ask them to enter it.

### SDV-HLD-004: no such document

- **HTTP status:** 404. Sent by place hold (`POST /api/v1/admin/legal-holds`).
- **Meaning:** `documentId` is missing, is not a document reference, or names no document of the person's own organization. Nothing was stored.
- **What the user should do:** find the document again by its record's identifier and place the hold from there.
- **What support should tell the customer:** the reference sent doesn't match a document of theirs. A screen or script may be using a stale or mistyped reference; finding the record again gives the right one.

### SDV-HLD-005: already on legal hold

- **HTTP status:** 409. Sent by place hold (`POST /api/v1/admin/legal-holds`).
- **Meaning:** the document already has a legal hold in place. A document has at most one hold at a time. Nothing was stored.
- **What the user should do:** nothing; the document is already kept. To change the reason, release the hold and place a new one.
- **What support should tell the customer:** the document is already protected. The list of holds in place shows who placed it, when and why.

### SDV-HLD-006: document deleted or being deleted

- **HTTP status:** 409. Sent by place hold (`POST /api/v1/admin/legal-holds`).
- **Meaning:** the retention cleanup has already deleted the document, or has started deleting it, so it can't be held. Nothing was stored.
- **What the user should do:** nothing in Vero can bring the document back. If it is still needed, ask the sending system's team whether they can send it again.
- **What support should tell the customer:** the document's retention period had ended and the cleanup removed it (or was removing it) before the hold was placed. The audit shows when (`DOCUMENT_RETENTION_DELETED`). A hold must be placed before the retention date to keep a document.

### SDV-HLD-007: no such legal hold

- **HTTP status:** 404. Sent by release hold (`POST /api/v1/admin/legal-holds/{holdId}/release`).
- **Meaning:** `holdId` is not a hold reference, or names no hold of the person's own organization. Nothing was changed.
- **What the user should do:** open the list of holds in place again and release the hold from there.
- **What support should tell the customer:** the reference sent doesn't match a hold of theirs. The list of holds in place gives the right one.

### SDV-HLD-008: legal hold already released

- **HTTP status:** 409. Sent by release hold (`POST /api/v1/admin/legal-holds/{holdId}/release`).
- **Meaning:** the hold was already released (perhaps by someone else a moment earlier). Nothing was changed.
- **What the user should do:** nothing; the hold is no longer in place. Reload the list of holds.
- **What support should tell the customer:** the hold was already released; the audit shows who released it and when (`LEGAL_HOLD_RELEASED`). The document will be deleted at the next daily cleanup if its retention date has passed.

## Approvals (APR)

Sent by the access requests of task 0028: a Restricted steward's request to print or download one Highly Confidential document (`POST /api/v1/business/documents/{id}/access-requests`), a Steward's own list (`GET /api/v1/business/access-requests`), and the Business Admin's approvals (`/api/v1/admin/access-requests`). A Steward's request to lower a level is made with `…/sensitivity/lower-request` (see the SEN codes) and is decided here.

### SDV-APR-001: asking to print or download needs a Restricted steward

- **HTTP status:** 403. Sent by `POST /api/v1/business/documents/{id}/access-requests`.
- **Meaning:** the person's role can't ask to print or download a Highly Confidential document. Only a Restricted steward can. No request was made. The refusal is audited as `AUTHORIZATION_DENIED` (capability `REQUEST_SENSITIVE_ACCESS`).
- **What the user should do:** ask a Restricted steward.
- **What support should tell the customer:** this is working as intended. Role membership comes from the customer's own sign-in groups, so a change is made there by their IT team, not in Vero.

### SDV-APR-002: request reason missing or the wrong length

- **HTTP status:** 400. Sent by `POST /api/v1/business/documents/{id}/access-requests`.
- **Meaning:** the `reason` is missing, or shorter than 3 or longer than 500 characters once spaces at either end are removed. No request was made.
- **What the user should do:** write why the document must be printed or downloaded, in 3 to 500 characters, and ask again.
- **What support should tell the customer:** every request needs a reason for the Business Admin who decides it. Ask them to give one of 3 to 500 characters.

### SDV-APR-003: no approval needed

- **HTTP status:** 409. Sent by `POST /api/v1/business/documents/{id}/access-requests`.
- **Meaning:** the document's effective level is not Highly Confidential, so printing or downloading it needs no approval. No request was made.
- **What the user should do:** print or download it directly, if their role allows that.
- **What support should tell the customer:** only Highly Confidential documents need an approval to leave Vero.

### SDV-APR-004: the same request is already waiting

- **HTTP status:** 409. Sent by `POST /api/v1/business/documents/{id}/access-requests` and by request-lower (document and record).
- **Meaning:** this person already has the same request waiting for a decision: the same kind for the same document, or a lowering of the same record. No second request was made.
- **What the user should do:** wait for the Business Admin's decision. Their own request list shows it.
- **What support should tell the customer:** the first request is still waiting; a Business Admin decides it in the approvals list. A request nobody decides expires after 7 days (unless the customer's setting says otherwise), and then a new one can be made.

### SDV-APR-005: approvals need the Business Admin

- **HTTP status:** 403. Sent by every `/api/v1/admin/access-requests` endpoint: the list, approve, decline and the bulk decision.
- **Meaning:** the person's role can't see or decide requests. Only the Business Admin can, and Roles & permissions can never give it to a Steward. Nothing was read or changed. The refusal is audited as `AUTHORIZATION_DENIED` (capability `APPROVE_SENSITIVE_ACCESS`).
- **What the user should do:** ask a Business Admin.
- **What support should tell the customer:** this is working as intended. Role membership comes from the customer's own sign-in groups, so a change is made there by their IT team, not in Vero.

### SDV-APR-006: nobody decides their own request

- **HTTP status:** 403. Sent by approve, decline and the bulk decision (per request).
- **Meaning:** the person deciding made this request themselves. Nobody approves or declines their own request. Nothing was changed. The refusal is audited as `ACCESS_REQUEST_DECISION_REFUSED`.
- **What the user should do:** ask another Business Admin to decide it.
- **What support should tell the customer:** this is intended (separation rule 3). Another Business Admin of the customer has to decide.

### SDV-APR-007: no such request

- **HTTP status:** 404. Sent by approve, decline and the bulk decision (per request).
- **Meaning:** the request reference is malformed, or names no request of the person's own organization. Nothing was changed.
- **What the user should do:** reload the approvals list and decide from there.
- **What support should tell the customer:** the reference sent doesn't match a request of theirs. The approvals list gives the right one.

### SDV-APR-008: the request is no longer waiting

- **HTTP status:** 409. Sent by approve, decline and the bulk decision (per request).
- **Meaning:** the request was already approved, declined or used (perhaps by another Business Admin a moment earlier), or it expired because nobody decided it in time. The message says which. Nothing was changed.
- **What the user should do:** reload the approvals list. For an expired request, the Steward asks again if it is still needed.
- **What support should tell the customer:** the audit shows what happened to the request (`ACCESS_REQUEST_APPROVED`, `ACCESS_REQUEST_DECLINED`, `SENSITIVE_ACCESS_GRANT_USED` or `ACCESS_REQUEST_EXPIRED`).

### SDV-APR-009: a whole-record request is decided on its own

- **HTTP status:** 409. Sent by the bulk decision (`POST /api/v1/admin/access-requests/decisions`), per request.
- **Meaning:** the request is about a whole record (contract, patient file), for example lowering its level. Such requests are never decided together with others. This one was left waiting; the others in the same bulk decision were decided as usual.
- **What the user should do:** open it on its own and approve or decline it there (`POST /api/v1/admin/access-requests/{id}/approve` or `…/decline`).
- **What support should tell the customer:** this is intended. A whole-record change affects every document of the record, so it is decided one at a time.

### SDV-APR-010: bulk decision malformed

- **HTTP status:** 400. Sent by the bulk decision (`POST /api/v1/admin/access-requests/decisions`).
- **Meaning:** `decision` is not exactly `APPROVE` or `DECLINE`, or `requestIds` is missing, empty or longer than 100. Nothing was decided.
- **What the user should do:** choose from 1 to 100 requests and approve or decline them.
- **What support should tell the customer:** at most 100 requests can be decided at once. The screen or program sent something else.

### SDV-APR-011: the lowering no longer applies

- **HTTP status:** 409. Sent by approve and the bulk decision (per request), for a lowering.
- **Meaning:** since the Steward asked, the level has changed (it is already at or below the level asked for), or the document was deleted. Nothing was changed and the request is still waiting.
- **What the user should do:** decline the request. The Steward can ask again from the level as it is now.
- **What support should tell the customer:** the request was made against a level that is no longer current, so approving it could have the wrong effect. It must be declined.

### SDV-APR-012: unknown request kind

- **HTTP status:** 400. Sent by `POST /api/v1/business/documents/{id}/access-requests` (kind must be `PRINT` or `DOWNLOAD`) and by the approvals list filter (`GET /api/v1/admin/access-requests?kind=…`, any of `PRINT`, `DOWNLOAD`, `LOWER_LEVEL`, `LOWER_RECORD_LEVEL`, comma-separated).
- **Meaning:** the kind is missing or not one of the exact names, in capitals. Nothing was stored.
- **What the user should do:** choose one of the kinds and send it again.
- **What support should tell the customer:** the screen or program sent a kind Vero doesn't know.

### SDV-APR-013: no such document

- **HTTP status:** 404. Sent by `POST /api/v1/business/documents/{id}/access-requests`.
- **Meaning:** the document ID is malformed, or names no document of the person's own organization. No request was made.
- **What the user should do:** open the document again from its record and ask from there.
- **What support should tell the customer:** the reference sent doesn't match a document of theirs.

### SDV-APR-014: the document isn't available

- **HTTP status:** 409. Sent by `POST /api/v1/business/documents/{id}/access-requests`.
- **Meaning:** the document is still being stored, failed to store, or was deleted by the retention cleanup, so it can't be printed or downloaded. No request was made.
- **What the user should do:** wait until it is available, or, if it was deleted, nothing.
- **What support should tell the customer:** only an available document can be printed or downloaded.

### SDV-APR-015: own request list needs a Steward

- **HTTP status:** 403. Sent by `GET /api/v1/business/access-requests`.
- **Meaning:** only Stewards make requests, so only they have a list of their own. Nothing was read. The refusal is audited as `AUTHORIZATION_DENIED` (capability `CHANGE_SENSITIVITY_LEVEL`).
- **What the user should do:** nothing; their role makes no requests.
- **What support should tell the customer:** this is working as intended.
