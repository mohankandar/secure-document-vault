# Vero error codes

Every error from Vero comes back with one body: a short, stable `code`, a plain `message`, a `reference` and the time (task 0036):

```json
{"code": "SDV-SEN-002", "message": "recordSensitivityLevel must be one of STANDARD, CONFIDENTIAL, HIGHLY_CONFIDENTIAL (exactly, in capitals); leave it out for no record level.", "reference": "6f1c0f0e-5b7a-4c1e-9f4e-2d8a7c3b1e90", "timestamp": "2026-11-20T10:00:00Z"}
```

- **Reference:** made by Vero for each request (task 0062: never the caller's own), the same as the `X-Correlation-Id` response header. It is on Vero's log line for the error, and on the audit event when one was written. Give it to support.
- **Format:** `SDV-<AREA>-<NNN>`. `SDV` is the product. `AREA` is three letters: `SEN` sensitivity, `ROL` roles, `AUT` sign-in, `IDP` sign-in connections, `HLD` legal hold, `APR` approvals, `INT` intake, `RPT` reports, `SYS` the server as a whole, `STO` storage, `KEY` the key service, `DOC` viewing, print and download, `SCH` search.
- **Stable:** a code never changes meaning once it is released. A code that is no longer used stays listed and is marked retired. Its number is never reused.
- **Messages** may be reworded. Programs should read the `code`, not the message.
- **Not yet coded:** errors that are not listed here still send a generic code (`BAD_REQUEST`, `CONFLICT`, `NOT_FOUND`, `FORBIDDEN` and so on). They get an `SDV-` code when their area is worked on. The failure catalog at the end of this file (task 0034) lists every such point and proposes its code.
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

### SDV-SEN-018: the level history needs a Steward or a Business admin

- **HTTP status:** 403. Sent by `GET /api/v1/business/documents/{id}/sensitivity/history` and `GET /api/v1/business/records/{recordRef}/sensitivity/history`. (An unknown document or record gets SDV-SEN-016; a Steward who can't open the document gets SDV-SEN-008.)
- **Meaning:** the level history holds the reasons people gave for changing a level. Only Document stewards, Restricted stewards and Business admins can read it. The refusal is recorded in the audit.
- **What the user should do:** ask a Steward or a Business admin if you need to know why a level changed.
- **What support should tell the customer:** the reasons are kept out of the audit on purpose (they are free text and may hold names), so IT admins can't read them either. A Steward or Business admin of the organization can.

### SDV-SEN-019: unknown level in the audit's sensitivity filter

- **HTTP status:** 400. Sent by `GET /api/v1/operations/audit-events` and `GET /api/v1/operations/audit-events/export` when `sensitivityLevel` is given.
- **Meaning:** the filter is not exactly `STANDARD`, `CONFIDENTIAL` or `HIGHLY_CONFIDENTIAL`. Lower case and extra spaces are refused, so a typing slip never quietly shows every event. Nothing was listed or exported.
- **What the user should do:** choose Any, Confidential or Highly Confidential in the Audit screen's Sensitivity filter. A program should send one of the three names exactly, or leave the filter out.
- **What support should tell the customer:** the filter keeps only events that recorded the document's level when they happened: downloads, prints, refused openings and requests. Since task 0031, opening a document and loading its pages also record the level the document had at that moment. Openings and page views from before then recorded no level, so the filter doesn't find them.

## Roles (ROL)

### SDV-ROL-001: permission a role may never have

- **HTTP status:** 400. Sent by Roles & permissions (`PUT /api/v1/admin/role-capabilities`).
- **Meaning:** the change would give a role a permission that Vero's separation rules forbid. Business Admin, IT Admin and Platform Operations can never be given `VIEW_CONFIDENTIAL`, `VIEW_HIGHLY_CONFIDENTIAL` or `REQUEST_SENSITIVE_ACCESS`. Document Steward and Restricted Steward can never be given `APPROVE_SENSITIVE_ACCESS`. The message names the role and the permission. Nothing was changed, for any role in the request. The refusal is audited.
- **What the user should do:** leave that permission off for that role. A person who needs it must be given a role that has it.
- **What support should tell the customer:** this is a fixed safety rule, not a setting. People who look after the system or approve requests must not also read restricted documents, and the people who read them must not approve their own requests.

### SDV-ROL-002: role whose permissions are fixed

- **HTTP status:** 400. Sent by Roles & permissions.
- **Meaning:** the change names a role whose permissions can't be changed in Vero: Restricted Steward, Business Admin, IT Admin or Platform Operations. Only Business User and Document Steward can be changed there. Nothing was changed. The refusal is audited.
- **What the user should do:** change only Business User and Document Steward.
- **What support should tell the customer:** these roles have a fixed set of permissions. Who holds them is decided in the customer's sign-in system (its groups), not in Vero.

### SDV-ROL-003: permission that can't be changed here

- **HTTP status:** 400. Sent by Roles & permissions.
- **Meaning:** the change names a permission that isn't one of those Roles & permissions can switch (download, print, add tags, add comments, permanently redact), or a name Vero doesn't know. Nothing was changed. The refusal is audited.
- **What the user should do:** send only the permissions the screen shows.
- **What support should tell the customer:** the other permissions come with the role and can't be switched on or off per customer. If the request came from a script, the script sent a name the screen doesn't offer.

### SDV-ROL-004: sign-in with roles that can never be held together

- **HTTP status:** 401 at sign-in. Task 0038: with the one error body and the message "The roles from this sign-in can never be held together, so none of them were given. Ask your IT team to correct your sign-in groups." (before, the code started the error description and there was no body). One INFO line with the reference.
- **Meaning:** the person's sign-in groups give Restricted Steward together with Business Admin, IT Admin or Platform Operations. Vero gives neither of the conflicting roles, and here no other role was left, so the sign-in was refused. The conflict is recorded for the IT Admin (`GET /api/v1/admin/role-conflicts`) and audited as `ROLE_CONFLICT_DETECTED`. When the person has other roles too, they sign in with those only and get no error.
- **What the user should do:** ask your IT team to take you out of one of the two groups.
- **What support should tell the customer:** someone is in the sign-in group for Restricted Steward and in an admin group at the same time. Vero never allows that. Their IT team must remove the person from one of the groups; the role conflict list names the person.

### SDV-ROL-005: role conflict could not be recorded

- **HTTP status:** 401 at sign-in. Task 0038: with the one error body and the message "Vero couldn't record a role conflict in this sign-in, so the sign-in was refused. Try again in a few minutes." One INFO line with the reference.
- **Meaning:** the sign-in named roles that can never be held together, and Vero could not record the conflict (for example the database was unavailable). A conflict is never left unrecorded, so the sign-in was refused.
- **What the user should do:** try again in a few minutes. If it keeps happening, ask your IT team to take you out of one of the two groups.
- **What support should tell the customer:** Vero could not write to its database at that moment. Check the server's health. The person is also in two groups that can't be held together; their IT team should fix that too.

### SDV-ROL-006: the role doesn't include this action

- **HTTP status:** 403. Task 0038: every capability check that has no code of its own: viewer, download, print, tags, comments, activity, redaction, search, records, Operations, Audit, storage, sending systems, fields, retention policy, Roles & permissions, role conflicts, Identity settings and session. It used to be 403 `FORBIDDEN`. The areas with their own code keep it (SEN-010, HLD-001, APR-001/005/015, INT-001, RPT-001).
- **Meaning:** the person is signed in, but none of their roles has the permission for this action. Nothing was read or changed. Message: "Your role doesn't include <the action in plain words>. Ask someone who has it.", for example "Your role doesn't include managing legal holds." The refusal is audited as `AUTHORIZATION_DENIED` with the capability, and Vero logs one INFO line with the reference and the capability.
- **What the user should do:** ask someone who has the role, or ask your IT team whether you should have it.
- **What support should tell the customer:** working as intended. Roles come from the customer's sign-in groups; their IT team changes the person's groups if the person should have the action.

## Sign-in (AUT)

Task 0038. A sign-in Vero refuses gets the one error body. The message is a fixed sentence: it never says whether an account exists and never holds anything from the token. Vero's log has one line with the code, the reference and the exact cause (a claim's name, a host, a reason class; never a claim's value or the token): INFO for an expected refusal, WARN when a person must fix something, ERROR for a 5xx. A refused bearer token keeps its `WWW-Authenticate: Bearer error="invalid_token"` header; its `error_description` is the code only.

### SDV-AUT-001: not signed in

- **HTTP status:** 401. Any address except health and system information, when the request carries no sign-in. It used to be 401 `UNAUTHORIZED` (or Spring's 401 with no body).
- **Meaning:** no token, or the session has ended. Message: "Please sign in again." The screen sends the person to sign in. One DEBUG line (task 0039, owner answer to report 0038: one INFO line per request without a sign-in was noise).
- **What the user should do:** sign in again.
- **What support should tell the customer:** the session ended; the person signs in again. Nothing is wrong.

### SDV-AUT-002: wrong kind of sign-in for this address

- **HTTP status:** 403. It used to be 401 `UNAUTHORIZED`, which told the caller to sign in again, which can't help.
- **Meaning:** a person's sign-in was used on a sending system's address (`/api/v1/ingestion/…`): "This address is for sending systems only."; or a sending system's sign-in on a person's address: "This address is for people, not sending systems." Nothing was read or stored. One INFO line names which kind of sign-in came.
- **What the user should do:** a sending system signs in with its own client (client credentials), not a person's account.
- **What support should tell the customer:** the program signed in as the wrong kind of user. Its setup must use the sending system's own client from the sign-in service.

### SDV-AUT-003: token not accepted

- **HTTP status:** 401. Task 0040: also when the `Authorization` header itself is malformed (for example `Bearer !!!`); the contract test (`IngestionContractTest`) shows it. The earlier note "400 when the header is malformed" was not right for Vero's settings.
- **Meaning:** the token is expired or not yet valid, its signature doesn't match the sign-in hub's keys, it was issued by another issuer or for another audience, its lifetime is longer than Vero allows, or it isn't a token at all. Message: "Please sign in again." One INFO line with the reason class (expired, signature, issuer, audience, lifetime, malformed); never the token or its times.
- **What the user should do:** sign in again.
- **What support should tell the customer:** the sign-in expired or wasn't issued for Vero. If everyone gets it, their IT team checks the sign-in hub's issuer and audience settings for Vero against `SDV_AUTH_JWT_ISSUER` and `SDV_AUTH_JWT_AUDIENCE`.

### SDV-AUT-004: sign-in hub's keys can't be fetched

- **HTTP status:** 503. Any signed-in request while the sign-in hub's signing keys (`SDV_AUTH_JWT_JWK_SET_URI`) can't be fetched. Confirmed by test (task 0038): before, Spring rethrew the failure and Vero answered 500 SDV-SYS-013, which said nothing about the sign-in hub.
- **Meaning:** the sign-in hub (for example Keycloak) is down or unreachable from Vero, so no sign-in can be checked. Message: "Vero can't check sign-ins right now because the sign-in service isn't answering. Try again in a few minutes." One ERROR line with the reference and the key set's host. Readiness (`/actuator/health/readiness`) fetches the keys too: it is 503 while they can't be fetched (one WARN line `code=SDV-AUT-004 …` with the host) and 200 again on its own when they can (one INFO line). Liveness stays UP. Task 0039: this check belongs to readiness only; it reports `NOT_READY`, which readiness answers 503 and the overall `/actuator/health` and liveness ignore, so neither is DOWN while the hub is away.
- **What the user should do:** try again in a few minutes.
- **What support should tell the customer:** their sign-in service is unavailable, or Vero's server can't reach it; nobody can sign in to Vero until it is back. Check the sign-in hub and the network path to the key set's host the log names. No restart of Vero is needed.

### SDV-AUT-005: sign-in claims not usable

- **HTTP status:** 401. It used to be 401 with Vero's English description.
- **Meaning:** the token was checked, but a claim Vero needs is missing, isn't text or is too long (the subject, the groups list), the client-ID claims (`azp`, `client_id`, `cid`) name different clients, or a client set up as a sending system also sends groups (a token is a person or a sending system, not both). Task 0062: or its token isn't the client's own (it has claims of a person's sign-in, or lacks the mark set in `SDV_AUTH_JWT_MACHINE_CLAIM`). Task 0041: a sending system's entity and source come from its client binding, not from claims, so a missing tenant or source claim is no longer this code. Message: "Your sign-in is missing details Vero needs. Your IT team can fix this." One WARN line names the claim, never its value.
- **What the user should do:** tell your IT team.
- **What support should tell the customer:** the sign-in hub isn't sending a detail Vero needs; the log names the claim. Their IT team adds or corrects the claim mapper in the sign-in hub.

### SDV-AUT-006: no sign-in connection for this issuer

- **HTTP status:** 401. It used to be 401 with "No ACTIVE persisted OIDC connection matches…".
- **Meaning:** the token is good, but no customer has an ACTIVE login connection for Vero with its issuer, audience and provider (task 0041: loaded by the setup command from the sign-in file into `idn_login_connection`). Message: "Vero isn't set up to accept this sign-in. Your IT team can fix this." One WARN line with the issuer's host, the provider and the audience.
- **What the user should do:** tell your IT team.
- **What support should tell the customer:** the login connection isn't loaded or doesn't match. IT runs the setup command with the sign-in file (issuer, `providerId` equal to `SDV_AUTH_JWT_PROVIDER_ID`, API audience).

### SDV-AUT-007: no Vero role from the sign-in groups

- **HTTP status:** 401. It used to be 401 with "Authenticated identity has no mapped SDV role.".
- **Meaning:** the person signed in, but none of their sign-in groups gives a Vero role. Message: "You're signed in, but you don't have access to Vero. Ask your IT team to add you to a Vero group." It says nothing about other accounts. One INFO line with the reference and the subject (the user's or client's ID, task 0039, so IT knows who was refused); never the token, the groups, the name or another claim.
- **What the user should do:** ask your IT team to add you to a Vero group.
- **What support should tell the customer:** the person needs to be in a group the role mapping file grants a Vero role in an ACTIVE entity (task 0041: loaded by the setup command into `idn_group_grant`). A grant of a role Vero doesn't have, or of the platform role, gives nothing.

### SDV-AUT-008: local test sign-in headers not valid

- **HTTP status:** 401. Only in a local start with `SDV_AUTH_MODE=local-header` (a developer machine).
- **Meaning:** an `X-SDV-*` test sign-in header is missing or malformed. Message: "Local sign-in headers are not valid: <which>.", for example "X-SDV-Tenant-Id is not a UUID". It names the header, never the value sent. One INFO line.
- **What the user should do:** send the headers the local guide lists.
- **What support should tell the customer:** not used by customers.

### SDV-AUT-009: organisation not found or not active

- **HTTP status:** 403, the same for both. It used to be 404 "Tenant not found." or 409 "Tenant is not active.", which told the caller whether the tenant exists.
- **Meaning:** the sign-in names a tenant that doesn't exist in Vero or is suspended. Message: "Your organisation's Vero account isn't available. Contact your IT team." The answer is identical for both, apart from the reference and the time, so it never tells an outsider which tenants exist. One WARN line with the tenant ID and whether it was missing or suspended. Task 0039: every signed-in request checks it, so a suspended tenant is refused everywhere. The status is kept for up to 60 seconds (`SDV_TENANT_STATUS_CACHE_SECONDS`, default 30, 0 to 60), so a suspension takes effect everywhere within that time, and at once where Vero reads the tenant anyway (the intake, the business screens, fields, Operations, sending systems, storage, retention and redaction). The log line then ends "(request check)" when the per-request check refused it.
- **What the user should do:** contact your IT team.
- **What support should tell the customer:** their organisation's Vero account is suspended or the sign-in hub sends the wrong tenant. The log line says which. Escalate to Vero operations to reactivate the tenant, or correct the sign-in hub's tenant claim.

### SDV-AUT-010: choose an entity

- **HTTP status:** 409. Added in task 0041.
- **Meaning:** the person may work in more than one entity and the request chose none (`X-SDV-Entity-Id`), or it chose one that isn't theirs or isn't an entity ID. Also every request of a person who has not chosen yet, other than the entity list (`GET /api/v1/session/entities`) and activation. Message: "Choose which entity to work in first." One INFO line says which case.
- **What the user should do:** choose the entity in the account menu.
- **What support should tell the customer:** the person has roles in several entities; Vero shows the entity chooser. If it keeps coming back, the browser's stored entity is no longer theirs: choose again.

### SDV-AUT-011: client not set up as a sending system

- **HTTP status:** 401. Added in task 0041. Recorded as an unidentified intake attempt (Problems).
- **Meaning:** a sending system's token was checked, but its client ID has no ACTIVE client binding for Vero (setup's client-binding file), or the bound entity or the customer's login connection is not ACTIVE. Also a token with neither a binding nor groups on an intake address. Message: "This client isn't set up to send documents to Vero. Your IT team can fix this." One WARN line names the client ID (never a secret) and, when bound, its slot.
- **What the user should do:** tell your IT team.
- **What support should tell the customer:** add the client ID to the client-binding file (`client_id,entity_slot,source,description`) and run setup, or activate the entity.

### SDV-AUT-012: token claim not the client binding's

- **HTTP status:** 401. Added in task 0041. Recorded as an unidentified intake attempt.
- **Meaning:** the sending system's token carries `sdv_entity_id`, `sdv_source_id` or `sdv_source_key`, and the value is not the entity or source its client binding gives (or isn't a UUID). These claims are optional; when sent they must match. Message: "The entity or sending system in this token doesn't match how this client is set up in Vero. Your IT team can fix this." One WARN line names the claim and the client ID, never the value.
- **What the user should do:** tell your IT team.
- **What support should tell the customer:** remove the claim from the client's token, or correct it (or the client-binding file) so both name the same entity and sending system.

### SDV-AUT-013: sending system of the binding not in Vero

- **HTTP status:** 401. Added in task 0041. Recorded as an unidentified intake attempt.
- **Meaning:** the client binding names a source key (for example `redaction-service`) that the bound entity has no sending system for in Vero. Message: "The sending system this client is set up for doesn't exist in Vero. Your IT team can fix this." One WARN line names the client ID, the source key and the entity.
- **What the user should do:** tell your IT team.
- **What support should tell the customer:** the IT Admin adds the sending system with that key in Integrations, or the binding file is corrected.

## Entities (ENT)

Sent by the Business Admin's activation address, `POST /api/v1/admin/entities/{slotId}/activate` (task 0041). The setup command loads the licence's entity slots as not activated; the Business Admin activates and names each.

### SDV-ENT-001: entity can't be activated

- **HTTP status:** 409.
- **Meaning:** platform-core refused the activation: no licence in force, the licence doesn't list the slot, the slot is unknown or already activated, or the name is empty, longer than 200 characters or taken. The message is platform-core's sentence; it names the slot, never the typed name. One INFO line with the reason.
- **What the user should do:** do what the message says (another name, or ask IT to install the licence).
- **What support should tell the customer:** the licence must be stored by setup and list the slot; names are unique per customer.

### SDV-ENT-002: not a Business Admin of this entity

- **HTTP status:** 403.
- **Meaning:** the person's groups don't give Business Admin on that slot (role mapping file), so they may not activate it. Message: "Only a Business Admin of <slot> can activate it." One INFO line.
- **What the user should do:** ask your IT team for the Business Admin group of that entity.
- **What support should tell the customer:** add the person's group to the role mapping file as `BUSINESS_ADMIN` for that slot and run setup.

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
- **Meaning:** the hold was already released (perhaps by someone else a moment earlier). Nothing was changed. Task 0037: when two Business Admins release the same hold at the same moment, the second gets this code with "This hold was already released." (it used to be 500 SDV-SYS-016). Every HLD-008 refusal is audited as `LEGAL_HOLD_RELEASE_REFUSED` (outcome DENIED, with the code).
- **What the user should do:** nothing; the hold is no longer in place. Reload the list of holds.
- **What support should tell the customer:** the hold was already released; the audit shows who released it and when (`LEGAL_HOLD_RELEASED`). The document will be deleted at the next daily cleanup if its retention date has passed.

## Quarantine (QUA)

Task 0048. A Steward quarantines a document while a suspected leak or wrong delivery is looked into. Endpoints are under `/api/v1/quarantine` (Stewards) and `/api/v1/admin/quarantine-releases` (Business Admin). Reasons are kept with the quarantine, never in the audit trail.

### SDV-QUA-001: document temporarily unavailable

- **HTTP status:** 403. Sent by every way of reaching a document (open, pages, previews, text search, tags, comments, activity, print, download, correct, level history) while it is quarantined.
- **Meaning:** the document is quarantined. Only a Steward who can open its level may review it (open it and correct it); nobody prints or downloads it until it is released. The answer does not say the level or why. The refusal is audited as `DOCUMENT_ACCESS_REFUSED` with this code.
- **What the user should do:** nothing for now. Vero says only that the document is temporarily unavailable, nothing else (task 0051). If you need it urgently, ask a Document Steward.
- **What support should tell the customer:** this is working as intended. A Steward has quarantined the document for review; a Steward releases it, or a Business Admin approves the release of the whole incident.

### SDV-QUA-002: needs a Steward who can open the document's level

- **HTTP status:** 403. Sent by every `/api/v1/quarantine` endpoint.
- **Meaning:** the person is not a Document Steward or Restricted Steward, or the document (or one in the incident) has a level they can't open. Highly Confidential documents are quarantined, reviewed and released only by a Restricted Steward. Nothing was changed. A missing role is audited as `AUTHORIZATION_DENIED`.
- **What the user should do:** ask a Steward who can open the document's level, or a Restricted Steward, to do it.
- **What support should tell the customer:** this is working as intended. Quarantine is a Steward task, limited to the levels the Steward can open. Role membership comes from the customer's own sign-in groups.

### SDV-QUA-003: quarantine by filter is for a Restricted Steward

- **HTTP status:** 403. Sent by `POST /api/v1/quarantine/filter/count` and `POST /api/v1/quarantine/filter`.
- **Meaning:** only a Restricted Steward may quarantine every document matching a filter (an incident-wide quarantine). Nothing was counted or changed. The refusal is audited as `AUTHORIZATION_DENIED` (capability `QUARANTINE_BY_FILTER`).
- **What the user should do:** quarantine the documents one at a time, or ask a Restricted Steward to quarantine by filter.
- **What support should tell the customer:** this is working as intended. Incident-wide quarantine is kept to Restricted Stewards.

### SDV-QUA-004: name, reason or filter not valid

- **HTTP status:** 400. Sent by quarantine, release, release request and filter endpoints.
- **Meaning:** the incident name is missing or longer than 200 characters, a reason is shorter than 3 or longer than 500 characters, or the filter is not usable: the received dates are missing or not `YYYY-MM-DD`, the "to" date is before the "from" date, a field is given without a sending system, a field can't be searched, a value is too long, or the count wasn't taken; or (task 0052) a page of an incident list was asked for from a position Vero didn't give. Nothing was changed.
- **What the user should do:** correct what the message names and try again. For a page that isn't known, open the incident again from its first page.
- **What support should tell the customer:** the request was incomplete; the message says which part. Filters match field values exactly, as search stores them.

### SDV-QUA-005: no document with this reference

- **HTTP status:** 404. Sent by quarantine one document and release one document.
- **Meaning:** no document of this entity has the reference. Nothing was changed.
- **What the user should do:** open the document again from its record and try once more.
- **What support should tell the customer:** the reference is wrong or belongs to another entity.

### SDV-QUA-006: already quarantined, or deleted

- **HTTP status:** 409. Sent by quarantine one document.
- **Meaning:** the document is already quarantined (each document is in at most one quarantine at a time), or the retention cleanup deleted it or is deleting it. Nothing was changed.
- **What the user should do:** look at the incident it is already in, in the quarantine list.
- **What support should tell the customer:** this is working as intended; the document is already quarantined, or no longer stored.

### SDV-QUA-007: no incident with this reference

- **HTTP status:** 404. Sent by the incident, release request, exposure and add-to-incident endpoints.
- **Meaning:** no quarantine incident of this entity has the reference. Nothing was changed.
- **What the user should do:** open the incident again from the quarantine list.
- **What support should tell the customer:** the reference is wrong or belongs to another entity.

### SDV-QUA-008: incident already released

- **HTTP status:** 409. Sent by add to incident and request release.
- **Meaning:** every document of the incident was released, so the incident is closed; or (task 0052) its approved whole release is running in the background. Nothing can be added to it or asked for it.
- **What the user should do:** start a new incident for the document.
- **What support should tell the customer:** this is working as intended; a released incident stays as history.

### SDV-QUA-009: the matching documents changed

- **HTTP status:** 409. Sent by `POST /api/v1/quarantine/filter`.
- **Meaning:** the number of documents matching the filter is not the number the person counted and selected (documents arrived, were deleted, or were quarantined meanwhile). Nothing was quarantined.
- **What the user should do:** count the matching documents again, check the number, and select them all again.
- **What support should tell the customer:** Vero quarantines exactly what the person saw counted, never more or fewer; counting again fixes it.

### SDV-QUA-010: the release request can't be made or decided

- **HTTP status:** 409 (404 for an unknown request). Sent by request release and by the Business Admin's approve and decline.
- **Meaning:** a request to release the incident is already waiting; or the request was already decided or expired; or the person deciding made the request; or the incident is still being prepared, or (task 0052) is already being released. Nobody decides their own request, and these requests are decided one at a time. Nothing was changed.
- **What the user should do:** wait for the request that is waiting, or ask again if it expired. A Business Admin who made the request asks another Business Admin to decide it.
- **What support should tell the customer:** this is working as intended. Release requests expire like other requests (7 days unless set otherwise).

### SDV-QUA-011: document not quarantined

- **HTTP status:** 409. Sent by release one document.
- **Meaning:** the document isn't quarantined now (it was released, perhaps by someone else at the same moment). Nothing was changed.
- **What the user should do:** refresh the incident; the document is already available.
- **What support should tell the customer:** this is working as intended.

## Audit report (AUD)

Task 0050. The sealed audit report: a Business Admin or IT Admin generates it for a period and the Audit list's filters, as a CSV for machines and a PDF for people, and hands it over. There is no auditor role. Every report is kept in a register with the SHA-256 fingerprint of its CSV, and its generation is audited (`AUDIT_REPORT_GENERATED`). Endpoints are under `/api/v1/audit-reports`. Codes SDV-AUD-001 to -003 are reserved for the audit list (see "Failure catalog" below).

### SDV-AUD-010: the audit report is for a Business Admin or IT Admin

- **HTTP status:** 403. Sent by every `/api/v1/audit-reports` endpoint.
- **Meaning:** only a Business Admin or IT Admin may generate, list, download or verify a sealed audit report. Nothing was generated or read. The refusal is audited as `AUTHORIZATION_DENIED` (capability `GENERATE_AUDIT_REPORT`).
- **What the user should do:** ask a Business Admin or your IT team to generate the report and hand it to you.
- **What support should tell the customer:** this is working as intended. Vero has no auditor role: an admin hands the sealed report over, so nobody outside it can change what Vero recorded.

### SDV-AUD-011: the report's days are not valid

- **HTTP status:** 400. Sent by `POST /api/v1/audit-reports`.
- **Meaning:** the first or last day is missing, is not a day (YYYY-MM-DD), or the first day comes after the last. Days are whole days in UTC, both included. Nothing was generated.
- **What the user should do:** choose the first and last day again, then generate the report.
- **What support should tell the customer:** the request named no valid period. Days are sent as YYYY-MM-DD.

### SDV-AUD-012: a filter of the report is not valid

- **HTTP status:** 400. Sent by `POST /api/v1/audit-reports`.
- **Meaning:** a filter is too long, the result is not Done, Refused or Failed (`SUCCESS`, `DENIED`, `FAILED`), the action is not an action code, or the level is not one of the three level names. Nothing was generated.
- **What the user should do:** change or clear the filter, then generate the report again.
- **What support should tell the customer:** the filters are those of the Audit list and are checked the same way.

### SDV-AUD-013: the report would be too large

- **HTTP status:** 400. Sent by `POST /api/v1/audit-reports`.
- **Meaning:** the period and filters cover more than 10,000 audit events, the most one report holds. Nothing was generated or registered.
- **What the user should do:** choose fewer days or more filters, and generate several reports if needed.
- **What support should tell the customer:** one report holds at most 10,000 events; split the period.

### SDV-AUD-014: no audit report has this reference

- **HTTP status:** 404. Sent by `GET /api/v1/audit-reports/{reportId}/csv` and `/pdf`.
- **Meaning:** there is no report with this reference in this entity. Reports of other entities are never shown.
- **What the user should do:** open the list of audit reports again and choose the report there.
- **What support should tell the customer:** the reference is wrong or belongs to another entity.

### SDV-AUD-015: the fingerprint is not valid

- **HTTP status:** 400. Sent by `POST /api/v1/audit-reports/verify`.
- **Meaning:** a fingerprint is 64 characters, the digits 0 to 9 and the letters a to f (the SHA-256 of the report's CSV file). Nothing was checked.
- **What the user should do:** choose the CSV file itself, or paste the whole fingerprint from the report's PDF.
- **What support should tell the customer:** the fingerprint is printed on the report's PDF and sent with the file (`X-SDV-Export-Fingerprint`).

### SDV-AUD-016: the report could not be recorded

- **HTTP status:** 500. Sent by `POST /api/v1/audit-reports`.
- **Meaning:** the report or its audit event could not be written, so the report was not handed out (fail closed). Nothing is in the register.
- **What the user should do:** try again in a few minutes. If it keeps happening, tell your IT team the reference shown.
- **What support should tell the customer:** check the database from the log line with the reference; the report can be generated again once it works.

### SDV-AUD-017: audit records can't be changed or removed

- **HTTP status:** none: this is the database's own refusal, in the message of the refused statement. If Vero itself ever met it, the answer is a 500 with a reference.
- **Meaning:** the audit trail (`audit_events`) and the report register (`audit_reports`) are append-only. Since changeset 037, triggers refuse `UPDATE`, `DELETE` and `TRUNCATE` for everyone, the owner included, and the running app's user has only `SELECT` and `INSERT` on them.
- **What the user should do:** nothing. Audit records are kept as they were written.
- **What support should tell the customer:** this is working as intended. Clean-up or test scripts that delete audit rows must leave them; the database will not remove them.

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
- **Meaning:** the request was already approved, declined or used (perhaps by another Business Admin a moment earlier), or it expired because nobody decided it in time. The message says which. Nothing was changed. Task 0037: when two Business Admins decide the same request at the same moment, the second gets this code with "Someone else already decided this request." (it used to be 500 SDV-SYS-016). The refusal is audited as `ACCESS_REQUEST_DECISION_REFUSED`, like every refused decision.
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

## Intake (INT)

### SDV-INT-001: intake notices need a Steward

- **HTTP status:** 403. Sent by `GET /api/v1/business/intake-notices` and `POST /api/v1/business/intake-notices/read`.
- **Meaning:** these notices tell Document stewards and Restricted stewards that a sending system sent an uncorrected copy of a corrected document (task 0032). Other roles don't get them. Nothing was read. The refusal is audited as `AUTHORIZATION_DENIED` (capability `CHANGE_SENSITIVITY_LEVEL`).
- **What the user should do:** nothing; their role gets no intake notices.
- **What support should tell the customer:** this is working as intended.

### SDV-INT-002: notice not found

- **HTTP status:** 404. Sent by `POST /api/v1/business/intake-notices/read`.
- **Meaning:** one of the notices named is not a notice of this tenant (task 0033). Nothing was marked read.
- **What the user should do:** reload Vero; the list shows the notices that are there.
- **What support should tell the customer:** the notice may belong to another tenant, or the link was made by hand.

### SDV-INT-003: no notices named, or too many

- **HTTP status:** 400. Sent by `POST /api/v1/business/intake-notices/read`.
- **Meaning:** the request named no notice, more than 100, or a reference that is not a notice ID (task 0033). Nothing was marked read.
- **What the user should do:** mark the notices from the list in Vero.
- **What support should tell the customer:** send `{"noticeIds": [...]}` with 1 to 100 notice IDs.

### Intake answers to sending systems (task 0039)

INT-004 to INT-018 are sent by the sending systems' addresses (`/api/v1/ingestion/…`): creating a submission (`POST /api/v1/ingestion/submissions`), reading one, uploading a document (`POST …/{submissionId}/documents`) and retrying its finishing (`POST …/documents/{documentId}/retry-finalization`). Each has the one error body; the message is the sentence below (it names a field, a header or a limit, never a value sent). Vero's log has one line with the code, the reference and the exact cause (a field's key, a rule, a header's name, a limit): INFO for a refusal, WARN for INT-005, ERROR for INT-010.

**Recorded, every time** (task 0039). Every error answer on these addresses is recorded in one place, also the ones Spring gives before Vero's code runs (SDV-SYS-015: JSON that doesn't parse, a missing `X-SDV-Filename`, a content type other than PDF), the sign-in refusals and the 5xx failures:
- a sending system Vero knows (a verified sending-system sign-in whose system is one of the tenant's, active or paused): a delivery, upload or finishing retry is counted by the Daily intake report under that system, as **refused** (4xx, audit event `INTAKE_REFUSED`) or **failed** (5xx, `INTAKE_FAILED`), with the code it got. A status query (`GET`) delivers nothing and isn't counted.
- no known sending system (no sign-in, a refused token, a person's sign-in, a sending system the tenant doesn't have): it is listed for IT and Platform operations under Problems, "Unidentified intake attempts", with the time, the address called (IDs blanked), the caller's network address, the status, the code and the reference; never anything from a token. Without a verified tenant an attempt is shown to every tenant's IT; with one, to that tenant's only. Kept 30 days; Problems shows the last 7.
  - Task 0039 part 2: one line per caller address, address called and code **per minute** (and per tenant, method and status), with how many times and the last time; the reference is the first request's (every request's is in the log). Parts of an address that aren't Vero's own words are blanked, an unknown method is `OTHER`, and past 100 lines in a minute new callers are counted together as "(many addresses)", so a flood can't grow the list without bound. Task 0040: the 100 is the setting `SDV_UNIDENTIFIED_ATTEMPTS_MAX_ROWS_PER_MINUTE` (1 to 10000, default 100).
  - The caller's address is the one Vero's server sees. Behind a proxy or load balancer, `SDV_TRUSTED_PROXIES` lists the proxies' IP addresses and, since task 0040, address ranges (CIDR, IPv4 or IPv6, for example `10.0.4.0/24`); only from those is `X-Forwarded-For` read. Empty by default: the header is ignored.
- a failure while Vero's database is away can't be recorded; it is in Vero's log only.

### SDV-INT-004: no such submission

- **HTTP status:** 404. It used to be 404 `NOT_FOUND` "Submission not found.".
- **Meaning:** no submission of this sending system has this ID (or this `sourceSubmissionId`, when looked up by it). Message: "No submission of this sending system has this ID. Create the submission first, or check the ID." Nothing was stored.
- **What the user should do:** the sending system uses the `submissionId` that creating the submission returned.
- **What support should tell the customer:** the sending system used a wrong ID, or another system's submission ID.

### SDV-INT-005: sending system unknown or paused

- **HTTP status:** 403, the same for both. It used to be 404 `NOT_FOUND` "Ingestion source not found." or 409 `CONFLICT` "Ingestion source is not active.".
- **Meaning:** the sign-in names a sending system that isn't one of the tenant's, or IT paused it. Message: "This sending system is paused or not set up in Vero. Nothing was stored." One WARN line with the source ID and which of the two it is.
- **What the user should do:** IT reactivates the sending system (Integrations), or corrects the client's source claim in the sign-in service.
- **What support should tell the customer:** the sending system is paused in Vero, or its sign-in names a system Vero doesn't know; the log line says which. IT checks Integrations.

### SDV-INT-006: submission request incomplete

- **HTTP status:** 400. It used to be 400 `BAD_REQUEST`.
- **Meaning:** the submission has no body, `expectedDocumentCount` is below 1, or `sourceSubmissionId` is missing or longer than 200 characters. The message names the field. Nothing was created.
- **What the user should do:** the sending system corrects the field the message names.
- **What support should tell the customer:** the sending system left out a required field; the message names it.

### SDV-INT-007: document list not valid

- **HTTP status:** 400. It used to be 400 `BAD_REQUEST`.
- **Meaning:** `expectedDocumentCount` is above the limit for the tenant or sending system, there are more listed documents than the count, or a listed `sourceDocumentId` is missing, too long or appears twice. Nothing was created.
- **What the user should do:** the sending system corrects its list of expected documents.
- **What support should tell the customer:** the submission's list of expected documents is inconsistent; the message says how.

### SDV-INT-008: metadata field unknown or missing

- **HTTP status:** 400. It used to be 400 `BAD_REQUEST`.
- **Meaning:** a metadata field this sending system doesn't have, a required field left out or blank, a field sent as `null`, or a value that isn't a single JSON value (an object or a list). The message names the field, never the value. Nothing was created.
- **What the user should do:** send the fields IT set up for the sending system, or ask IT to add the field.
- **What support should tell the customer:** the sending system and Vero's field setup disagree about a field; the message names it.

### SDV-INT-009: metadata value not valid

- **HTTP status:** 400. It used to be 400 `BAD_REQUEST`.
- **Meaning:** a value of the wrong type, or one that breaks its field's pattern, range or length. The message names the field and the rule, never the value (a parser's own message, which could repeat the value, is never sent). The INFO line has the field and the rule. Nothing was created.
- **What the user should do:** the sending system corrects the value.
- **What support should tell the customer:** a field's value breaks the rule IT set for it; the message names the field and the rule.

### SDV-INT-010: a field's own rule can't be used

- **HTTP status:** 500. It used to be 409 `CONFLICT`, which told the sending system to change a request that was fine.
- **Meaning:** the rule IT stored for a field (its pattern, a length, its rules as a whole) can't be used, so the submission can't be checked. Message: "Vero's setup for field '<key>' is broken, so this submission can't be checked. Nothing was stored. Your IT team has been told." One ERROR line with the field and which rule. The Daily intake report counts it as failed.
- **What the user should do:** tell your IT team; send the submission again once the field is fixed.
- **What support should tell the customer:** a field's rule in Vero is broken; IT corrects it (Integrations, the sending system's fields), then the sending system sends again.

### SDV-INT-011: submission retry differs from the first

- **HTTP status:** 409. It used to be 409 `CONFLICT`. (A retry that differs only in its record level is SDV-SEN-004.)
- **Meaning:** the same `sourceSubmissionId` was sent again with different content. Nothing was changed.
- **What the user should do:** repeat the first request exactly, or use a new `sourceSubmissionId`.
- **What support should tell the customer:** the submission already exists; a retry must match it exactly.

### SDV-INT-012: document upload has no identity

- **HTTP status:** 400. It used to be 400 `BAD_REQUEST`.
- **Meaning:** the upload sends neither `X-SDV-Source-Document-Id` nor `Idempotency-Key`, or the submission listed all its documents and this ID isn't one of them (or none was sent). Nothing was stored.
- **What the user should do:** send the document's `X-SDV-Source-Document-Id` from the submission's list.
- **What support should tell the customer:** each document needs its ID from the submission's list.

### SDV-INT-013: upload headers not valid

- **HTTP status:** 400. It used to be 400 `BAD_REQUEST`. (A missing `X-SDV-Filename` header, or a content type the address doesn't take, is refused before Vero's code runs: SDV-SYS-015.)
- **Meaning:** the file name is blank, longer than 500 characters or looks like a path; `X-SDV-Source-Document-Id` or `Idempotency-Key` is longer than 200 characters; or the content type isn't `application/pdf`. The message and the INFO line name the header, never its value. Nothing was stored.
- **What the user should do:** correct the header the message names.
- **What support should tell the customer:** the upload's file name or headers are wrong; the message says which.

### SDV-INT-014: PDF too large

- **HTTP status:** 413. It used to be 413 `PDF_TOO_LARGE`.
- **Meaning:** the PDF is bigger than the limit for the tenant or the sending system. The message gives the limit in bytes. Nothing was stored.
- **What the user should do:** send a smaller file, or ask IT to raise the sending system's limit.
- **What support should tell the customer:** the file is over the size limit; nothing was stored.

### SDV-INT-015: not a PDF

- **HTTP status:** 400. It used to be 400 `INVALID_PDF`.
- **Meaning:** the body is empty, cut short, or doesn't start with a PDF header. Nothing was stored.
- **What the user should do:** the sending system sends the PDF file itself.
- **What support should tell the customer:** what was sent isn't a PDF, or was cut short; send it again.

### SDV-INT-016: submission already full

- **HTTP status:** 409 when the submission already has its expected number of documents; 400 when this document would take the submission over its total size limit. It used to be 409 `CONFLICT` and 400 `BAD_REQUEST`.
- **Meaning:** the submission can't take this document. Nothing was stored.
- **What the user should do:** send the document on a new submission.
- **What support should tell the customer:** the submission is complete or at its size limit; extra documents go on a new one.

### SDV-INT-017: document retry differs from the first

- **HTTP status:** 409. It used to be 409 `CONFLICT`. (A retry that differs only in its level is SDV-SEN-003.)
- **Meaning:** a document with this ID is already stored (or was acknowledged as an exact duplicate) with other content or another file name. The INFO line names the document ID and which differs. Nothing was stored.
- **What the user should do:** repeat the first upload exactly, or send the changed document with a new ID.
- **What support should tell the customer:** a document with this ID is already stored with other content; a changed document needs a new ID.

### SDV-INT-018: document can't be finished again

- **HTTP status:** 404 when no document of this submission has the ID; 409 when the document is in a state that can't be finished again. It used to be 404 `NOT_FOUND` and 409 `CONFLICT`.
- **Meaning:** retry-finalization can't run. Messages: "No document of this submission has this ID, so it can't be finished again." or "This document can't be finished again in its current state (<state>)."
- **What the user should do:** none; check the document's state first (`GET …/documents`).
- **What support should tell the customer:** the document is already being handled, or was deleted.

## Reports (RPT)

Sent by the Business Admin's reports: the Daily intake report (task 0032, `GET /api/v1/admin/reports/intake`) and its CSV (`GET /api/v1/admin/reports/intake/export`), and the Sensitivity report (task 0033, `GET /api/v1/admin/reports/sensitivity`) and its CSV (`GET /api/v1/admin/reports/sensitivity/export`). All take `from` and `to` (days, `YYYY-MM-DD`, in the tenant's time zone; both left out means today for Daily intake and this month so far for Sensitivity) and `sourceId` (left out or empty means every sending system).

### SDV-RPT-001: reports need a Business Admin

- **HTTP status:** 403. Sent by every report endpoint.
- **Meaning:** only a Business Admin sees the reports. Nothing was read. The refusal is audited as `AUTHORIZATION_DENIED` (capability `VIEW_INTAKE_REPORTS`).
- **What the user should do:** ask a Business Admin.
- **What support should tell the customer:** this is working as intended. Role membership comes from the customer's own sign-in groups.

### SDV-RPT-002: day or date range not valid

- **HTTP status:** 400. Sent by every report endpoint.
- **Meaning:** `from` or `to` is not a date written `YYYY-MM-DD`, only one of the two was sent, `to` is before `from`, or the range is longer than 31 days (Daily intake) or 366 days (Sensitivity). Nothing was read.
- **What the user should do:** choose one day, or a range of up to 31 days (Daily intake) or 366 days (Sensitivity).
- **What support should tell the customer:** a Daily intake report covers at most 31 days, a Sensitivity report 366; export several for a longer period.

### SDV-RPT-003: sending system not known

- **HTTP status:** 400. Sent by every report endpoint.
- **Meaning:** `sourceId` is not the ID of one of this tenant's sending systems. Nothing was read.
- **What the user should do:** choose a sending system from the list, or All.
- **What support should tell the customer:** the sending system may have been removed, or the link was made for another tenant.

### SDV-RPT-004: tenant time zone not valid

- **HTTP status:** 409. Sent by every report endpoint.
- **Meaning:** the tenant's time zone setting (`tenants.time_zone`, or the install default `SDV_DEFAULT_TIME_ZONE` when the tenant has none) is not a time zone Vero knows, so "today" can't be worked out. Vero refuses the report rather than guess. Nothing was read.
- **What the user should do:** tell IT.
- **What support should tell the customer:** set the time zone to an IANA name such as `America/New_York` or `Europe/London`. A wrong install default also stops the server from starting.

### SDV-RPT-005: too much for one report

- **HTTP status:** 400. Sent by the Daily intake report and its CSV (the Sensitivity report reads counts only and never sends it).
- **Meaning:** the range holds more than 200,000 documents, duplicates and refusals, more than one report reads at once. Nothing was returned.
- **What the user should do:** choose fewer days, or one sending system.
- **What support should tell the customer:** export the period in parts.

## Server (SYS)

Task 0036. Every error body also carries `reference`: the request's correlation ID, the same as the `X-Correlation-Id` response header. The same ID is on Vero's log line, and on the audit event when one was written. Every 5xx writes one ERROR log line with the code, the reference and the exact cause; the body holds only the plain sentence.

Task 0037: SYS-003 to SYS-012 are start-up codes. A start that stops prints one ERROR block, "APPLICATION FAILED TO START", with the code, the sentence, the exact cause and what to do, and no stack trace. A packaged start is any start without the `local` profile, as a customer install runs.

### SDV-RST-001: the Restrictions page needs the Business Admin

- **HTTP status:** 403. Sent by every `/api/v1/admin/restrictions` endpoint: the overview, the records with a record level and the level changes.
- **Meaning:** the person's role can't see the Restrictions page. Only the Business Admin can (capability `VIEW_RESTRICTIONS`). Nothing was read. The refusal is audited as `AUTHORIZATION_DENIED`.
- **What the user should do:** ask a Business Admin.
- **What support should tell the customer:** this is working as intended. Role membership comes from the customer's own sign-in groups, so a change is made there by their IT team, not in Vero.

### SDV-RST-002: a page of the Restrictions page isn't known

- **HTTP status:** 400. Sent by `GET /api/v1/admin/restrictions/records` and `/level-changes`.
- **Meaning:** the page position (`after`) isn't one Vero gave. Nothing was read.
- **What the user should do:** open the Restrictions page again and go from its first page.
- **What support should tell the customer:** the page position was changed or cut short; opening the page again fixes it.

### SDV-SYS-001: database older than this version

- **HTTP status:** 503, on every request except health and system information (`/actuator/health…`, `/actuator/info`, `/api/v1/system/info`). Replaces `DATABASE_OLDER`.
- **Meaning:** this Vero version has database changes that have not been applied yet. Vero refuses everything until they are. Nothing was read or changed.
- **What the user should do:** wait; Vero can't be used until the database is updated. Tell the IT team if they don't already know.
- **What support should tell the customer:** Vero was upgraded but its database wasn't. The database team runs the schema step from the install guide ("Update the database"), then restarts Vero. No data is lost.

### SDV-SYS-002: database newer than this version

- **HTTP status:** 503, on every request except health and system information. Replaces `DATABASE_NEWER`.
- **Meaning:** the database was updated for a later Vero version than the one running. Nothing was read or changed.
- **What the user should do:** wait, and tell the IT team.
- **What support should tell the customer:** an older Vero was started after an upgrade. Start the Vero version the database was updated for. Never roll the database back by hand.

### SDV-SYS-003: database unreachable at start

- **HTTP status:** none since task 0037; it is the start-up log line. While the database can't be reached, requests get 503 **SDV-SYS-014** (task 0036 sent this code with 503, and Vero refused until it was restarted).
- **Meaning:** when Vero started it couldn't reach the database to check its version. Vero keeps running and keeps trying: after 2 s, then twice as long each time, at most once a minute. As soon as the database answers, the version check runs and Vero serves (or refuses with SYS-001 or SYS-002), without a restart. Readiness (`/actuator/health/readiness`) is 503 until then; liveness stays UP. Task 0039 part 2: the same rule as when the database goes away later: the schema check reports `NOT_READY` (readiness only), so the overall `/actuator/health` stays 200 too (it was 503 `OUT_OF_SERVICE`). A database that answers and doesn't match (SYS-001, SYS-002) is still `OUT_OF_SERVICE` in readiness and the overall health.
- **What the user should do:** try again in a few minutes. If it keeps happening, tell the IT team.
- **What support should tell the customer:** Vero started while its database was unavailable. Check that PostgreSQL is running and reachable; no restart is needed. The log has one ERROR line `SDV-SYS-003 …` with the database host and port and the SQL state, a WARN line per further try, and an INFO line when the database can be reached again.

### SDV-SYS-004: bundled changelog unreadable

- **HTTP status:** none; Vero doesn't start.
- **Meaning:** the database changelog inside the Vero package is missing or damaged, so Vero can't check the database's version. The start stops with one ERROR block, "APPLICATION FAILED TO START", that holds the code, the cause and the action.
- **What the user should do:** nothing; Vero is not running. Tell the IT team.
- **What support should tell the customer:** the installed Vero package is damaged. Reinstall it from the release.

### SDV-SYS-005: sign-in settings not valid

- **HTTP status:** none; Vero doesn't start.
- **Meaning:** a sign-in setting is wrong: `SDV_AUTH_MODE` is unknown, `local-header` is used outside a local start, an issuer, key set (JWKS) or audience setting is missing, not HTTPS or not a URI, a tenant ID setting is not a UUID, or two claim names are the same. Since task 0039 part 2 also a setting for recording who calls the intake addresses: an entry in `SDV_TRUSTED_PROXIES` that isn't an IP address or a valid range (task 0040), or `SDV_UNIDENTIFIED_ATTEMPTS_MAX_ROWS_PER_MINUTE` that isn't a whole number from 1 to 10000 (task 0040). The ERROR block names the setting (never a secret value).
- **What the user should do:** nothing; Vero is not running. Tell the IT team.
- **What support should tell the customer:** a sign-in setting is wrong; the log names it. Correct it and start Vero again.

### SDV-SYS-006: sign-in switched off

- **HTTP status:** 401 since task 0038: with sign-in off, every request except health and system information is refused 401 with this code and the message "Sign-in is switched off for Vero, so nobody can sign in yet. Your IT team has been told." (it was `UNAUTHORIZED`). Each such refusal writes one WARN line with the reference.
- **Meaning:** `SDV_AUTH_MODE` is `disabled` (its default). In a local start (the `local` profile) Vero starts with one WARN line `SDV-SYS-006 …` that says nobody can sign in. A packaged start (any start without the local profile, as a customer install runs) refuses to start, with an ERROR line and the ERROR block.
- **What the user should do:** nothing; tell the IT team.
- **What support should tell the customer:** the install is missing its sign-in setting. Set `SDV_AUTH_MODE=enterprise-jwt` and the issuer settings (install guide, "Sign-in"), then start Vero again.

### SDV-SYS-007: key service settings not valid

- **HTTP status:** none; Vero doesn't start.
- **Meaning:** with `SDV_KEY_PROVIDER=vault`, the Vault address is missing, both a token and a token file are given (or neither), the token file is empty or can't be read, or the mount or key prefix is not valid. The ERROR block names the setting; it never holds the token or the token file's contents.
- **What the user should do:** nothing; Vero is not running. Tell the IT team.
- **What support should tell the customer:** a key service setting is wrong; the log names it. Correct it and start Vero again.

### SDV-SYS-008: approval settings not valid

- **HTTP status:** none; Vero doesn't start.
- **Meaning:** `SDV_APPROVALS_GRANT_HOURS` or `SDV_APPROVALS_PENDING_DAYS` is not a whole number in its range, or a per-tenant entry isn't written `tenant id=number` or names a tenant twice. The ERROR block names the setting.
- **What the user should do:** nothing; Vero is not running. Tell the IT team.
- **What support should tell the customer:** an approvals setting is wrong; the log names it. Correct it and start Vero again.

### SDV-SYS-009: retention schedule settings not valid

- **HTTP status:** none; Vero doesn't start.
- **Meaning:** `SDV_RETENTION_DAILY_AT` is empty or not `HH:mm`, `SDV_RETENTION_CRON` can't be read, or one of the old fixed-delay settings is still set. The ERROR block names the setting and its value (not secret).
- **What the user should do:** nothing; Vero is not running. Tell the IT team.
- **What support should tell the customer:** the daily cleanup time is written wrongly. Use `HH:mm`, for example `02:00`, and start Vero again.

### SDV-SYS-010: install default time zone not valid

- **HTTP status:** none; Vero doesn't start. (The same problem in one tenant's own setting is SDV-RPT-004.)
- **Meaning:** `SDV_DEFAULT_TIME_ZONE` is empty or not an IANA time zone name. The ERROR block holds the value.
- **What the user should do:** nothing; Vero is not running. Tell the IT team.
- **What support should tell the customer:** the default time zone is written wrongly. Set an IANA name such as `America/New_York` and start Vero again.

### SDV-SYS-012: start-up work needs the database

- **HTTP status:** none.
- **Meaning:** Vero does some work once at start that reads or writes the database: the Restricted Steward sign-in group check (with `enterprise-jwt`) and the sign-in connection bootstrap. Since task 0037 this work waits for the database like the rest of Vero: while the database doesn't match it doesn't run, and one WARN line `SDV-SYS-012 Start-up work waits for the database …` names it; it runs as soon as the database matches, also when the database comes back without a restart. If the work itself fails, Vero doesn't serve with it undone (fail closed): at start the start stops, and after a late recovery Vero stops, both with an ERROR line holding the code, the work's name and the cause (exception classes and SQL state only, never the SQL).
- **What the user should do:** nothing; tell the IT team.
- **What support should tell the customer:** Vero couldn't finish its start-up work because of the database. Check that PostgreSQL is running and reachable, then start Vero again.

### SDV-SYS-013: unexpected server failure

- **HTTP status:** 500. Any request; the last-resort answer for a failure no other code covers.
- **Meaning:** something failed inside Vero that it didn't expect. Message: "Vero couldn't finish this just now. Try again. If it keeps happening, give your IT team the reference."
- **What the user should do:** try again. If it keeps happening, give the IT team the reference.
- **What support should tell the customer:** something failed inside Vero. Ask for the reference and the time and pass them to Vero support. Vero's log has one ERROR line with that reference, the exception class and the stack trace.

### SDV-SYS-014: database failed during a request

- **HTTP status:** 503. Any request that reads or writes the database. Task 0037: also every request except health and system information while the database couldn't be reached since the start (SDV-SYS-003); Vero keeps trying.
- **Meaning:** PostgreSQL went down or timed out while Vero was answering, or hasn't answered since Vero started. (Task 0040 part 2: no connection free in Vero's pool, or PostgreSQL out of connections, is SDV-SYS-018, busy, with `Retry-After`. SYS-014 never carries `Retry-After`.) Message: "Vero can't reach its database just now. Try again in a few minutes." Task 0037: readiness pings the database, so `/actuator/health/readiness` is 503 while it is gone and 200 again when it answers. Task 0039: the ping belongs to readiness only (status `NOT_READY`); the overall `/actuator/health` and liveness are not DOWN while the database is away, and Spring's own database check is switched off.
- **What the user should do:** try again in a few minutes. A sending system re-sends what got a 503.
- **What support should tell the customer:** Vero lost its database for a while. Check the database server. The log line has the reference, the SQL state and the Vero method; it never holds the SQL or the values sent.

### SDV-SYS-015: request malformed before Vero's code ran

- **HTTP status:** 400 when the request can't be read: an ID that isn't a UUID, JSON that doesn't parse, a required header or parameter missing. A wrong method (405), a wrong content type (415), an Accept header Vero can't answer (406) and an unknown address (404) keep their own status.
- **Meaning:** the request was refused before Vero looked at it. Nothing was stored. The message says which part, for example "Vero couldn't read this request: the submissionId is not valid." It names the field, never the value sent.
- **What the user should do:** correct the part the message names and send it again. For a person: reload the page and try again.
- **What support should tell the customer:** the sending program or screen sent the request in the wrong form; the message says which part. Vero logs an INFO line with the reference and the route. Task 0039: on an intake address it is also recorded (the Daily intake report, or Problems when no sending system is known).

### SDV-SYS-016: internal check failed

- **HTTP status:** 500. Any request. It used to be 409 `CONFLICT` with an internal sentence, which told the caller to change a request that was fine.
- **Meaning:** a check inside Vero that should never fail did fail: a bug, or data changed outside Vero. Nothing was changed. Message: "Vero stopped this because something inside didn't add up. Nothing was changed. Give your IT team the reference."
- **What the user should do:** give the IT team the reference. Trying again usually gives the same answer.
- **What support should tell the customer:** this is a Vero fault, not the customer's. Pass the reference to Vero support. The log line has the check's own sentence and the stack trace.

### SDV-SYS-017: action not done because the audit couldn't be written

- **HTTP status:** 500. Any action that writes an audit event. It used to be 409 `CONFLICT`.
- **Meaning:** Vero never acts without recording it. The record couldn't be written, so the action was not done. Message: "Vero couldn't record this action, so it wasn't done. Try again in a few minutes."
- **What the user should do:** try again in a few minutes.
- **What support should tell the customer:** the audit couldn't be written, so nothing happened. Check the database, then try again. The log line has the reference and the cause.

### SDV-SYS-018: too busy to take the request

- **HTTP status:** 503, with a `Retry-After` header in seconds (the setting `SDV_BUSY_RETRY_AFTER_SECONDS`, 1 to 300, default 5; another value stops the start with SDV-SYS-005). Task 0040 part 2: any request. It is the only answer from Vero that carries `Retry-After`, and Vero never answers 429. Before, these requests got 503 SDV-SYS-014, which said the database couldn't be reached.
- **Meaning:** Vero is too busy to take the request: the database is there, but no connection was free for this request. Vero tells the two apart from the cause. Busy is: the connection pool waited its full time (HikariCP's `connectionTimeout`, 30 s by default) and no new connection had failed; or PostgreSQL refused a new connection because it has none left (SQL state 53300, too_many_connections). When connections to the database fail, it is SDV-SYS-014 as before. Message: "Vero is too busy to take this request just now. Try again in a few seconds."
- **What the user should do:** wait the seconds in `Retry-After`, then send the same request again. A sending system does this without alerting; a retry of a create or an upload is never stored twice. A person tries again in a few seconds.
- **What support should tell the customer:** Vero had more work at once than its database connections allow. If it happens often, raise `SDV_DB_POOL_MAX` (10 by default) within the database's own connection limit, or spread the senders' load. While the pool stays full for more than 2 s, readiness (`/actuator/health/readiness`) answers `503 {"status":"BUSY","code":"SDV-SYS-018",…}` with `Retry-After` too (only when busy is the one reason; every other readiness 503 has no code and no `Retry-After`). A readiness probe is not counted as a busy answer. Each such answer writes one WARN line (not ERROR: busy is expected under load) with the code, the reference, the exception classes and the SQL state (never the SQL). Problems shows how many busy answers Vero gave in the last hour and since it started.

### SDV-SYS-019: the request is too large

- **HTTP status:** 413. Task 0062: any request with a body over the limit (1 MB; 8 MB for a submission; settings `SDV_MAX_REQUEST_BODY_BYTES` and `SDV_MAX_SUBMISSION_BODY_BYTES`), by its `Content-Length` or while it is read. Not document uploads: a PDF over its limit is SDV-INT-014.
- **Meaning:** Vero didn't read the request to its end; nothing was changed. Message: "This request is larger than Vero takes. Send less at once."
- **What the user should do:** send less at once, for example fewer documents in one list.
- **What support should tell the customer:** the request was over Vero's limit. If a real submission needs more, the installer raises the setting. One INFO line has the reference.

## Storage (STO)

### SDV-STO-001: no storage can take the document

- **HTTP status:** 503. Sent by the document upload (`POST /api/v1/ingestion/submissions/{id}/documents`), corrective redaction and the storage placement preview. It used to be 409 `CONFLICT`, and intake counted it as a refusal.
- **Meaning:** every ACTIVE storage volume is full, at its stop-write level, or can't be written or read. Nothing was stored. Message: "Vero has no storage space for new documents right now. Nothing was stored; send it again later."
- **What the user should do:** a sending system sends the document again later. A person tells the IT team.
- **What support should tell the customer:** Vero's storage is full. IT frees space, adds a volume, or raises the stop-write level; then senders re-send. Vero logs an ERROR line with the reference.

### SDV-STO-004: storage type not installed

- **HTTP status:** 503, no `Retry-After`. Task 0040 part 2 (owner answer 2 to report 0040): sent by the document upload (`POST /api/v1/ingestion/submissions/{id}/documents`). It used to be 409 with the generic code `CONFLICT`, which told the sender to change a request that was fine. The other places that meet the same problem (finishing a document, the viewer, download, corrective redaction, retention) keep their answers until the storage fix task.
- **Meaning:** storage for this tenant isn't available: the ACTIVE storage volume Vero picked names a storage provider this install doesn't have. Nothing was stored. Message: "Storage for this tenant isn't available, so nothing was stored. The installer must fix it."
- **What the user should do:** a sending system keeps the document, alerts, and sends it again after the fix (retry rule `after-fix`). A person tells the IT team.
- **What support should tell the customer:** an install problem, not the sender's. Install or configure the storage provider the volume names, or move the tenant to a volume whose provider is installed; then senders re-send. Vero logs an ERROR line with the code, the reference and the provider ID.

### SDV-STO-005: storage volume settings not valid

- **HTTP status:** 400. Task 0037: sent by the storage placement preview (`GET /api/v1/admin/storage-volumes/placement-preview?requiredBytes=…`) when `requiredBytes` is negative. It used to be 400 `BAD_REQUEST` with an internal sentence. The other volume settings keep `BAD_REQUEST` until the storage fix task gives them this code.
- **Meaning:** a value IT sent for storage is missing or out of range. Nothing was changed. Message: "requiredBytes must be 0 or more."
- **What the user should do:** correct the value the message names and try again.
- **What support should tell the customer:** a storage setting is out of range; the message says which.

### SDV-STO-008: the folder can't be used for documents

- **HTTP status:** 400. Task 0062: adding a storage volume. (Checking a folder gives the same sentence as the check's result.)
- **Meaning:** the folder is outside the folders the installer allows (`SDV_STORAGE_ALLOWED_ROOTS`), is a network share or a link, or is used by another volume. The answer doesn't say which. Nothing was created. Message: "This folder can't be used for documents. Choose another, or ask your installer which folders are allowed."
- **What the user should do:** choose another folder inside the allowed ones.
- **What support should tell the customer:** the log's INFO line with the reference says which it was. The installer can allow more folders.

## Key service (KEY)

### SDV-KEY-001: encryption not set up

- **HTTP status:** none yet. Task 0037 sends it in the start-up log; until the key service fix task, the viewer answers SDV-DOC-003 and a download SDV-DOC-007 (task 0043; they were `VIEWER_UNAVAILABLE` and `DOWNLOAD_UNAVAILABLE`), and other document actions a 500.
- **Meaning:** `SDV_KEY_PROVIDER` is `disabled` (its default), so no document can be stored, viewed, printed, downloaded or redacted. In a local start Vero starts with one WARN line `SDV-KEY-001 …` that names what will fail. A packaged start (any start without the local profile) refuses to start, with an ERROR line and the ERROR block.
- **What the user should do:** nothing; tell the IT team.
- **What support should tell the customer:** the install has no key service configured. Set `SDV_KEY_PROVIDER=vault` and the Vault settings (see the install guide), then start Vero again.

## Viewing, print and download (DOC)

Task 0043. Sent by the viewer (`GET /api/v1/business/documents/{id}/viewer`, `…/viewer/pages/{n}`, `…/viewer/previews`, `…/viewer/search`), download (`…/download`), print (`…/print-event`), tags and comments (`…/tags`, `…/comments`) and a document's activity (`…/activity`). They replace `VIEWER_UNAVAILABLE` and `DOWNLOAD_UNAVAILABLE`, which covered every cause with one code and were never logged (owner answer 3 to report 0034). The level check still comes first: a restricted document gets SDV-SEN-008, never one of these.

Every one writes one log line with the reference and the exact cause: ERROR for SDV-DOC-003, -004, -007 and -009; WARN for -005 and -008; INFO for -001 and -002; DEBUG for -006 and -010. Statuses (owner answer 1 to report 0043): Vero's own faults are not the caller's mistake, so storage or the key service is 503 (SDV-DOC-003, and SDV-DOC-007 for those causes) and a failed check is 500 (SDV-DOC-004, and SDV-DOC-007 for a check). What belongs to the document itself stays 422: too large to view (SDV-DOC-004) and can't be drawn (SDV-DOC-005). None of these 503 answers carries `Retry-After`; only the busy answer (SDV-SYS-018) does.

**Highly Confidential grants (owner answer 4 to report 0034).** A one-time print or download grant counts as used when the first byte is sent. A download takes the grant just before it decrypts, so two downloads racing on one grant can't both start. If it then fails before any byte is handed to the answer (storage, the key service, a check), the grant is given back (audited `SENSITIVE_ACCESS_GRANT_RETURNED`) and can be used once more. Once part of the file has left, it stays used. Print follows the same rule: the screen prints only after Vero has recorded the print, so a print that can't be recorded gives its grant back.

### SDV-DOC-001: no such document

- **HTTP status:** 404.
- **Meaning:** the document ID names no document of the person's organisation. The message is "This document isn't there any more, or the link is wrong. Open the record again from search." INFO line with the document ID.
- **What the user should do:** open the record again from search.
- **What support should tell the customer:** old links can point to documents that were deleted or never existed in this organisation.

### SDV-DOC-002: document not available

- **HTTP status:** 409.
- **Meaning:** the document exists, but it can't be opened, printed, downloaded, tagged or commented on yet: it is still being stored, Vero couldn't store it, a permanent correction is being made, or it is being or was deleted. The message names the state in plain words: "This document can't be opened yet: it is still being stored." INFO line with the state's name.
- **What the user should do:** wait a few minutes and open it again. If Vero couldn't store it, a Steward sees it on Problems.
- **What support should tell the customer:** the document is still being processed, failed (see Problems) or was removed by the keeping rule.

### SDV-DOC-003: the viewer can't read the stored copy

- **HTTP status:** 503 (it was 422 until owner answer 1 to report 0043). No `Retry-After`.
- **Meaning:** the viewer couldn't read the document: its storage details or its storage volume are missing, the storage provider isn't installed, storage couldn't be read, or the key service failed. The message is "Vero couldn't read this document from storage. Try again later." ERROR line with the reference, the document ID and which step failed. Nothing was shown.
- **What the user should do:** try again later. If it keeps happening, give the IT team the reference.
- **What support should tell the customer:** a storage or key service problem. IT checks Storage on Operations and the key service; the log line with the reference names the step.

### SDV-DOC-004: the stored copy failed its check before viewing

- **HTTP status:** 500 when the stored copy fails its check; 422 for "too large to view here", which belongs to the document (owner answer 1 to report 0043; both were 422).
- **Meaning:** the stored copy no longer matches what Vero stored: its encrypted size or fingerprint, a decryption check, or the decrypted size, PDF header or fingerprint. The message is "This document's stored copy didn't pass Vero's check, so it isn't shown." A document too large for the in-memory viewer gets the same code with "This document is too large to view here." ERROR line with the reference, the document ID and which check. Nothing was shown.
- **What the user should do:** give the IT team the reference. For the size case, download it if the role allows.
- **What support should tell the customer:** escalate to Vero support with the reference; the copy may be damaged and may need restoring from backup.

### SDV-DOC-005: the document can't be drawn

- **HTTP status:** 422 (unchanged: a property of the document, not a fault; owner answer 1 to report 0043).
- **Meaning:** the stored copy passed its checks, but the PDF can't be loaded or a page can't be drawn as an image. The message is "Vero can't draw this page. Download it if your role allows, or tell your IT team." WARN line with the reference, the document ID and the page.
- **What the user should do:** download it if the role allows, or tell the IT team.
- **What support should tell the customer:** the file is stored safely, but the viewer can't draw it. Send Vero support the document ID.

### SDV-DOC-006: viewer request not valid

- **HTTP status:** 400.
- **Meaning:** a page number outside the document, a zoom too large for the page, a preview count out of range, or a find text that is empty or too long. The message says which. DEBUG line.
- **What the user should do:** reload the document.
- **What support should tell the customer:** the screen asked for something the document doesn't have; reloading fixes it.

### SDV-DOC-007: download can't be verified

- **HTTP status:** 503 when storage or the key service failed, 500 when a check failed (owner answer 1 to report 0043; it was 422). No `Retry-After`.
- **Meaning:** before anything was sent, the stored copy was missing, couldn't be read, or failed its size or fingerprint check, or the key service failed. The message is "Vero couldn't verify this document, so it wasn't downloaded." Audited `DOCUMENT_DOWNLOAD_FAILED` with the code; a Highly Confidential grant was given back. ERROR line with the reference, the document ID, which step and the grant's state.
- **What the user should do:** try again later; give the IT team the reference if it keeps happening. A Highly Confidential approval is still good.
- **What support should tell the customer:** as SDV-DOC-003 or SDV-DOC-004; the log line says which step.

### SDV-DOC-008: document changed while downloading

- **HTTP status:** 409.
- **Meaning:** a correction replaced the document between preparing the download and sending it, so the old version was not sent. The message is "This document was just changed. Open it again and download the new version." Audited `DOCUMENT_DOWNLOAD_FAILED`. WARN line. No grant was used.
- **What the user should do:** open the document again and download the new version.
- **What support should tell the customer:** someone corrected the document at that moment; downloading again gets the corrected version.

### SDV-DOC-009: download stopped part-way

- **HTTP status:** none can be sent: the file had started. The screen shows "The download didn't finish." when the file is shorter than announced or the connection breaks.
- **Meaning:** storage, the key service or a check failed, or the browser stopped taking the file, after part of it was sent. Audited `DOCUMENT_DOWNLOAD_FAILED` with the code and the bytes sent. ERROR line with the reference, the document ID, the step, the bytes sent and the grant's state. A Highly Confidential grant stays used, because part of the file left Vero.
- **What the user should do:** download again. For a Highly Confidential document, ask for approval again and mention the reference.
- **What support should tell the customer:** the download was interrupted; the log line with the reference says why.

### SDV-DOC-010: tag or comment not valid

- **HTTP status:** 400.
- **Meaning:** the tag or comment is empty, or longer than its limit (80 characters for a tag, 2,000 for a comment). The message says which. DEBUG line.
- **What the user should do:** shorten the text, or write something.
- **What support should tell the customer:** the text was empty or too long.

## Search (SCH)

Task 0043. Sent by search (`POST /api/v1/business/search`), a record (`GET /api/v1/business/records/{recordRef}`) and Recently viewed (`GET /api/v1/business/me/recent-records`).

### SDV-SCH-001: search criteria not valid

- **HTTP status:** 400.
- **Meaning:** the search text is too short or too long, a page is out of range, there are no criteria, dates are reversed, too many fields are combined, a field isn't searchable, or no sending system was chosen. The message says which and names the field's key, never the value searched for. DEBUG line.
- **What the user should do:** change the search as the message says.
- **What support should tell the customer:** the search was refused as sent; the message says what to change.

### SDV-SCH-002: record not found or link not valid

- **HTTP status:** 404 when the reference names no record of this organisation (another organisation's sending system, or no documents left); 400 when the reference can't be read. The person reads the same sentence for both: "This record isn't there any more, or the link is wrong. Search for it again." INFO line with the exact cause, never the reference itself (it holds identifier values).
- **Meaning:** the record link is old, broken or from elsewhere.
- **What the user should do:** search for the record again.
- **What support should tell the customer:** old links can break when documents are deleted; searching again finds the record if it is still there.

### SDV-SCH-003: Recently viewed request not valid

- **HTTP status:** 400.
- **Meaning:** the number of records asked for is out of range, or no person is signed in for the request. DEBUG line.
- **What the user should do:** reload the page.
- **What support should tell the customer:** the screen asked for an unusual number of records; reloading fixes it.

## Failure catalog (task 0034): proposed codes

Task 0034 went through the code flow by flow and listed every point where something can fail: thrown, caught, returned or swallowed. The released codes above are unchanged. Everything below is a **proposal**: **Vero does not send these codes yet.**

- **Why the headings are smaller.** Each proposed code has a `####` heading, not a `###` heading. The build test (`ErrorCodesDocumentedTest`) reads only `### SDV-…:` headings, and it requires every listed code to exist in the product. When a fix task makes Vero send a code, its block moves up into its area as a `###` section with the three required parts.
- **Where.** File names are unique in `backend/`, so `File.java:123` is enough to find a line. Line numbers are from branch `docs/failure-catalog` (from `feature/reports-2` at `92ea7da`).
- **New areas.**
  - `SYS`: start-up, setup and the server as a whole.
  - `AUT`: sign-in.
  - `STO`: storage.
  - `KEY`: key service and encryption.
  - `PRC`: processing after receipt.
  - `DOC`: viewing, print, download, tags and comments.
  - `SCH`: search and records.
  - `RED`: corrective redaction.
  - `RET`: retention.
  - `AUD`: audit log.
  - `ADM`: admin screens for sending systems, fields and roles.
  - `IDP`: the sign-in connection set up by IT.
  - Existing areas continue their own numbering, for example `INT` from 004 and `ROL` from 006.
- **Kind** is one of:
  - **never reached us**: refused before Vero's own code ran;
  - **reached but refused**: the request is wrong as sent; nothing was stored;
  - **accepted then failed**;
  - **needs a person**: a setting, a group or data must be fixed by someone;
  - **not authorised**: the role doesn't allow it;
  - **restricted**: the role can't open this sensitivity level;
  - **not found**;
  - **authorised but system failed**;
  - **outside dependency down**.
- **Generic today** means the answer has no stable code, or the code covers several different causes (for example `CONFLICT`, `BAD_REQUEST`, `NOT_FOUND`, `HTTP 500`, `VIEWER_UNAVAILABLE`).
- **Message rules** for every code below:
  - no passwords, keys, tokens, document content or redacted values in messages or logs;
  - sign-in messages never say whether an account exists;
  - every logged failure carries the request's reference (the `X-Correlation-Id`). Today the reference is sent only as a response header, never in the error body.
  - "Ref" in a message below means that reference.

### Start-up and setup (SYS)

#### SDV-SYS-001 (released in task 0036): database older than this version
- **Released:** now sent; its section is under "Server (SYS)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `SchemaGateFilter.java:43` (answer), `DatabaseSchemaCheck.java:36` (log), `SchemaVersion.java:31` (sentence).
- **Trigger:** changesets of this version have not been applied to the database.
- **Today:** 503 `DATABASE_OLDER` with a plain sentence, on every request except health and system information. Generic: no (own code, not in SDV form).
- **Kind:** needs a person.
- **User sees:** "Vero is waiting for a database update and can't be used yet. Your IT team has been told. (SDV-SYS-001)"
- **IT admin and log:** ERROR at start, with the changesets not applied (already logged). Add the code to the log line.
- **Fix:** the database team runs the schema step (`Update-SDV-Schema.ps1`), then Vero is restarted.
- **L1 tells the customer:** Vero was upgraded but its database wasn't. Their database team runs the schema step from the install guide, then restarts Vero. No data is lost.

#### SDV-SYS-002 (released in task 0036): database newer than this version
- **Released:** now sent; its section is under "Server (SYS)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `SchemaGateFilter.java:43`, `DatabaseSchemaCheck.java:36`, `SchemaVersion.java:32`.
- **Trigger:** the database has changesets this version doesn't know (an older Vero started against an upgraded database).
- **Today:** 503 `DATABASE_NEWER`. Generic: no (own code).
- **Kind:** needs a person.
- **User sees:** "Vero can't start because its version doesn't match the database. Your IT team has been told. (SDV-SYS-002)"
- **IT admin and log:** ERROR at start with the unknown changesets.
- **Fix:** start the Vero version the database was updated for.
- **L1 tells the customer:** an older Vero was started after an upgrade. Start the newer version again; never roll the database back by hand.

#### SDV-SYS-003 (released in task 0036): database unreachable at start
- **Task 0037:** Vero now keeps trying (the "Fix task" below); requests meanwhile get SDV-SYS-014, and SYS-003 is the start-up log line.
- **Released:** now sent; its section is under "Server (SYS)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DatabaseSchemaCheck.java:52-54` (caught; logs the JDBC exception class and message), `SchemaGateFilter.java:43`.
- **Trigger:** PostgreSQL is down, unreachable, or refuses the user when Vero starts.
- **Today:** 503 `DATABASE_UNREACHABLE` until Vero is **restarted**. The check runs once, so Vero stays refusing even after the database comes back. Generic: no (own code).
- **Kind:** outside dependency down.
- **User sees:** "Vero can't reach its database. Try again in a few minutes. If it keeps happening, tell your IT team. (SDV-SYS-003)"
- **IT admin and log:** WARN today. Make it ERROR, with the code, the host and port from the URL, and the JDBC SQL state. The PostgreSQL message can name the database user; it never holds the password.
- **Fix:** bring the database back, then restart Vero. Fix task: re-check the database every minute while unreachable.
- **L1 tells the customer:** Vero started while its database was unavailable. Once the database is up, restart Vero.

#### SDV-SYS-004 (released in task 0037): bundled changelog unreadable
- **Released:** now sent (task 0037); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `SchemaVersion.java:75-76`.
- **Trigger:** the changelog inside the Vero package is missing or damaged.
- **Today:** start stops with a stack trace, "The changelog bundled with this Vero version could not be read." Generic: yes (no code).
- **Kind:** authorised but system failed.
- **User sees:** nothing; Vero is not running.
- **IT admin and log:** ERROR with the code and the resource name.
- **Fix:** reinstall the Vero package.
- **L1 tells the customer:** the installed package is damaged. Reinstall it from the release.

#### SDV-SYS-005 (released in task 0037): sign-in settings not valid
- **Released:** now sent (task 0037); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:**
  - `SecurityConfiguration.java:41`, `:48`, `:55`;
  - `EnterpriseJwtConfiguration.java:101`, `:113`, `:125`, `:140`, `:143`, `:149`, `:156`;
  - `EnterpriseJwtAuthenticationConverter.java:106`, `:194`;
  - `MaximumTokenLifetimeValidator.java:17`.
- **Trigger:** one of these:
  - `SDV_AUTH_MODE` is unknown;
  - `local-header` is used outside the local profile;
  - an issuer, JWKS or audience setting is missing, not HTTPS, or not a URI;
  - a tenant ID setting is not a UUID;
  - two claim names are the same.
- **Today:** start stops with a Spring stack trace. The messages are good, but carry no code. Generic: yes.
- **Kind:** needs a person.
- **User sees:** nothing; Vero is not running.
- **IT admin and log:** one ERROR line, with the code and the setting's name (never its value when it is a secret).
- **Fix:** correct the named setting.
- **L1 tells the customer:** a sign-in setting is wrong. The log names it.

#### SDV-SYS-006 (released in task 0037): sign-in switched off
- **Released:** now sent (task 0037); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `SecurityConfiguration.java:52-54` (`SDV_AUTH_MODE` left at its default `disabled`).
- **Trigger:** Vero is started outside the local profile without `SDV_AUTH_MODE`.
- **Today:** Vero starts, and every request gets 401 with Spring's generic body. Nothing is logged. Generic: yes.
- **Kind:** needs a person.
- **User sees:** "Sign-in isn't set up for Vero yet. Your IT team has been told. (SDV-SYS-006)"
- **IT admin and log:** WARN at start, with the code: "sign-in is disabled (SDV_AUTH_MODE)". Health info shows it.
- **Fix:** set `SDV_AUTH_MODE=enterprise-jwt` and the issuer settings.
- **L1 tells the customer:** the install is missing its sign-in setting. See the install guide, "Sign-in".

#### SDV-SYS-007 (released in task 0037): key service settings not valid
- **Released:** now sent (task 0037); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `VaultCryptoConfiguration.java:42`, `:47`, `:50`, `:57`; `VaultTransitKeyProtectionProvider.java:41-44`.
- **Trigger:** with `SDV_KEY_PROVIDER=vault`, one of these:
  - the Vault address is missing;
  - both a token and a token file are given, or neither;
  - the token file is empty or can't be read;
  - the mount or key prefix is not valid.
- **Today:** start stops with a stack trace; messages name the setting, never the token. Generic: yes (no code).
- **Kind:** needs a person.
- **User sees:** nothing; Vero is not running.
- **IT admin and log:** ERROR with the code and the setting's name. Never log the token or the token file's contents.
- **Fix:** correct the named setting.
- **L1 tells the customer:** the key service setting is wrong; the log names it.

#### SDV-SYS-008 (released in task 0037): approval settings not valid
- **Released:** now sent (task 0037); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `ApprovalSettings.java:66`, `:74`, `:79`, `:82` (`:63` is caught and refused at `:66`).
- **Trigger:** one of these:
  - `SDV_APPROVALS_GRANT_HOURS` or `SDV_APPROVALS_PENDING_DAYS` is not a whole number in its range;
  - a per-tenant entry isn't written `tenant id=number`;
  - a per-tenant entry names a tenant twice.
- **Today:** start stops; plain message, no code. Generic: yes.
- **Kind:** needs a person.
- **User sees:** nothing; Vero is not running.
- **IT admin and log:** ERROR with the code and the setting's name.
- **Fix:** correct the setting.
- **L1 tells the customer:** an approvals setting is wrong; the log names it.

#### SDV-SYS-009 (released in task 0037): retention schedule settings not valid
- **Released:** now sent (task 0037); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `RetentionSchedule.java:49`, `:58-59`, `:71`, `:75-76`.
- **Trigger:** `SDV_RETENTION_DAILY_AT` is empty or not `HH:mm`, or `SDV_RETENTION_CRON` can't be read.
- **Today:** start stops; plain message, no code. Generic: yes.
- **Kind:** needs a person.
- **User sees:** nothing; Vero is not running.
- **IT admin and log:** ERROR with the code and the setting's name and value. The value is not secret.
- **Fix:** correct the setting.
- **L1 tells the customer:** the daily cleanup time is written wrongly; use `HH:mm`, for example `02:00`.

#### SDV-SYS-010 (released in task 0037): install default time zone not valid
- **Released:** now sent (task 0037); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `IntakeReportService.java:101`, `:105-106`.
- **Trigger:** `SDV_DEFAULT_TIME_ZONE` is empty or not an IANA name.
- **Today:** start stops; plain message, no code. Generic: yes. (The same problem in one tenant's own setting is SDV-RPT-004.)
- **Kind:** needs a person.
- **User sees:** nothing; Vero is not running.
- **IT admin and log:** ERROR with the code and the value.
- **Fix:** set an IANA name such as `America/New_York`.
- **L1 tells the customer:** the default time zone is written wrongly.

#### SDV-SYS-011 (released in task 0037): sign-in recovery at start refused
- **Retired in task 0041:** the sign-in recovery went with Vero's own login connections; a connection is changed only by setup. The code is no longer sent.
- **Released:** now sent (task 0037); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `OidcKnownGoodRecovery.java:35`, `:37`, `:38` (`UUID.fromString` with no message of its own); `JdbcOidcConnectionBindingRepository.java:124`, `:126`, `:128`, `:129`, `:131`, `:136`, `:140`, `:144`.
- **Trigger:** the recovery settings are incomplete, the database doesn't match, the request ID was already used, or the target isn't a disabled known-good connection.
- **Today:** start stops. The messages are good except at `:38`, where a malformed ID gives Java's raw "Invalid UUID string". Generic: yes (no code).
- **Kind:** needs a person.
- **User sees:** nothing; Vero is not running.
- **IT admin and log:** ERROR with the code and which check failed.
- **Fix:** correct the recovery settings, or remove them to start normally.
- **L1 tells the customer:** the emergency sign-in recovery could not be applied; the log says why. Follow the recovery runbook.

#### SDV-SYS-012 (released in task 0037): start-up work needs the database
- **Released:** now sent (task 0037); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:**
  - `RestrictedStewardMappingWatcher.java:35`: no schema check before it reads and writes the database;
  - `OidcConnectionBootstrap.java:29`: `UUID.fromString` on `SDV_AUTH_JWT_TENANT_ID` with no message of its own.
- **Trigger:** with `enterprise-jwt`, Vero starts while the database is unreachable or doesn't match, or the tenant ID setting is not a UUID.
- **Today:** likely the start stops with a raw database or UUID stack trace. That defeats the "stay up and show why" design of SYS-001 to SYS-003. Generic: yes.
- **Confirmed (task 0037):** with `enterprise-jwt` and the database unreachable, the jar built from `release/vero-r7` at `9d8b4e6` logged "Started", then stopped with `CannotCreateTransactionException` from the watcher and exit code 1.
- **Kind:** outside dependency down, or needs a person (tenant ID).
- **User sees:** nothing; Vero is not running.
- **IT admin and log:** ERROR with the code.
- **Fix:** fix task. Skip the watcher while the database doesn't match (as the bootstrap does), and check the tenant ID with the other sign-in settings (SYS-005).
- **L1 tells the customer:** Vero could not start because its database was unavailable. Start it again once the database is up.

#### SDV-SYS-013 (released in task 0036): unexpected server failure
- **Released:** now sent; its section is under "Server (SYS)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `FoundationExceptionHandler.java` (no handler for `Exception`, `IOException` or Spring's `DataAccessException`). Any failure not listed elsewhere ends here. Main callers:
  - `DocumentIntakeController.java:62-67`: storage, key service and client-disconnect `IOException`s;
  - `ControlledDownloadController`: failures after the download started.
- **Trigger:** a failure Vero didn't expect.
- **Today:** 500 with Spring's default body (`timestamp`, `status`, `error`, `path`). It has no code and no message, and the screen shows "HTTP 500". Tomcat logs the stack trace; Vero adds nothing. Generic: yes.
- **Kind:** authorised but system failed.
- **User sees:** "Vero couldn't finish this just now. Try again. If it keeps happening, give your IT team this reference: Ref <id>. (SDV-SYS-013)"
- **IT admin and log:** ERROR with the code, the reference, the exception class and the stack trace. No request body.
- **Fix:** fix task. Add a last-resort handler that sends `{code, message, reference, timestamp}` with 500, and give each recurring cause its own code.
- **L1 tells the customer:** something failed inside Vero. Ask for the reference and pass it to Vero support with the time.

#### SDV-SYS-014 (released in task 0036): database failed during a request
- **Released:** now sent; its section is under "Server (SYS)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** every JDBC repository (`Jdbc*Repository.java`). Spring's `DataAccessException` reaches no Vero handler.
- **Trigger:** PostgreSQL goes down, times out or runs out of connections after Vero has started.
- **Today:** 500, generic body, same as SYS-013. Readiness stays UP, because it reports only the start-up schema check. Generic: yes.
- **Kind:** outside dependency down.
- **User sees:** "Vero can't reach its database just now. Try again in a few minutes. Ref <id>. (SDV-SYS-014)"
- **IT admin and log:** ERROR with the code, the reference, the SQL state and the repository method. No SQL parameters, because they can hold identifiers.
- **Fix:** restore the database. Fix task: map `DataAccessException` to 503 with this code, and add a database ping to readiness.
- **L1 tells the customer:** Vero lost its database for a while. Check the database server; nothing needs re-sending unless the sender got a 503.

#### SDV-SYS-015 (released in task 0036): request malformed before Vero's code ran
- **Released:** now sent; its section is under "Server (SYS)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** Spring MVC, before any controller (`DefaultHandlerExceptionResolver`):
  - an `@PathVariable UUID` that isn't a UUID (every `{documentId}`, `{submissionId}`, `{sourceId}`, `{volumeId}`, `{requestId}` and `{holdId}`);
  - JSON that doesn't parse;
  - a required header that is missing, such as `X-SDV-Filename` (`DocumentIntakeController.java:41`);
  - the wrong method;
  - an upload that isn't `application/pdf`. That gets 415 before `DocumentIntakeService.java:581-584` can answer.
- **Trigger:** the caller sends something that can't be read.
- **Today:** 400, 405 or 415 with Spring's generic body. No code, nothing logged, and the refusal is not counted in the Daily intake report. Generic: yes.
- **Kind:** never reached us.
- **User sees:** "Vero couldn't read this request: <what> (SDV-SYS-015)", where <what> is, for example, "the document ID is not valid".
- **IT admin and log:** INFO with the code, the reference, the path pattern (not the values) and Spring's reason.
- **Fix:** fix task. Handle these exceptions in `FoundationExceptionHandler`, and record intake ones as `INTAKE_REFUSED`.
- **L1 tells the customer:** the sending program or screen sent a request in the wrong form. The message says which part.

#### SDV-SYS-016 (released in task 0036): internal check failed
- **Released:** now sent; its section is under "Server (SYS)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** checks that "can't happen". Today they are `IllegalStateException` (sent as **409 `CONFLICT` with developer text**), `UnsupportedOperationException`, or a bare `orElseThrow()` (sent as **404 "No value present"**).
  - SHA-256: `BusinessExperienceService.java:873`, `RecordKey.java:51`, `:112`, `BusinessRecordRefCodec.java:38`, `ValidatedPdfInputStream.java:28`, `SubmissionService.java:433`, `StorageVolumeAdminService.java:153`, `CorrectiveRedactionService.java:412`, `:459`.
  - Row counts:
    - `JdbcAccessRequestRepository.java:101`, `:263`;
    - `JdbcRecordSensitivityRepository.java:75`, `:92`;
    - `JdbcFoundationRepository.java:104`, `:182`;
    - `JdbcSecureIngestionRepository.java:52`, `:188`, `:421`, `:538`, `:573`, `:623`, `:646`;
    - `JdbcLegalHoldRepository.java:103`;
    - `JdbcRetentionRepository.java:32`, `:239`.
  - Unreadable stored values:
    - `JdbcSensitivityReportRepository.java:77-78`, `:270`, `:291`;
    - `SensitivityReportService.java:156`, `:162`, `:263`;
    - `StewardNoticeService.java:98`;
    - `IntakeReportService.java:162`;
    - `SensitivityLevelService.java:177`;
    - `SubmissionService.java:369`;
    - `RetentionPolicyService.java:91`;
    - `CorrectiveRedactionService.java:220`, `:223`, `:306`;
    - `DocumentIntakeService.java:223`, `:269`.
  - Bare `orElseThrow()`: `BusinessRecordRefCodec.java:107`, `RoleConflictService.java:73`, `JdbcOidcConnectionBindingRepository.java:62`, `:74`, `:97`, `:117`, `:146`.
  - Internal "required" arguments that no request can trigger:
    - `AccessRequestService.java:646`, `:650`;
    - `LegalHoldService.java:197`, `:201`;
    - `SensitivityLevelService.java:235`, `:273`, `:274`, `:284`;
    - `AuditQueryService.java:62`, `:97`, `:98`;
    - `DocumentAccessGuard.java:115`;
    - `PdfSanitizingRedactor.java:30-32`;
    - `ValidatedPdfInputStream.java:22-23`;
    - `StoragePlacementService.java:24`;
    - `SdvEnc001StreamingCrypto.java:63`, `:119`;
    - `VaultTransitKeyProtectionProvider.java:110`;
    - `Document.java:28`;
    - `SensitivityLevel.java:50`;
    - `ForbiddenGrant.java:12`, `:16`;
    - `RoleSeparationPolicy.java:23`, `:34`;
    - `RoleSeparationRule.java:18`, `:24`, `:28`;
    - `JdbcRoleGovernanceRepository.java:151`;
    - `JdbcDocumentRedactionRepository.java:78`, `:81`.
  - Unsupported defaults: `MetadataFieldDefinitionRepository.java:17`, `:20`, `RecordSensitivityRepository.java:28`, `StorageVolumeProbe.java:19`. The no-op class at `SubmissionService.java:462-493` is used only when nothing else is wired, and is never reached in the product.
- **Trigger:** a bug, or data changed outside Vero.
- **Today:** 409 `CONFLICT` with an internal sentence such as "Tenant-scoped ingestion source update affected 2 rows.". That tells the caller to change their request, which is wrong. Not logged. Generic: yes.
- **Kind:** authorised but system failed.
- **User sees:** "Vero stopped this because something inside didn't add up. Nothing was changed. Give your IT team this reference: Ref <id>. (SDV-SYS-016)"
- **IT admin and log:** ERROR with the code, the reference, the check's own sentence (it holds no values) and the stack trace.
- **Fix:** fix task. Throw a dedicated `InternalCheckFailed` (500), not `IllegalStateException`.
- **L1 tells the customer:** this is a Vero fault, not the customer's. Pass the reference to Vero support.

#### SDV-SYS-017 (released in task 0036): action not done because the audit couldn't be written
- **Released:** now sent; its section is under "Server (SYS)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** audit JSON failures: `AccessRequestService.java:577-578`, `AuditQueryService.java:164-165`, `LegalHoldService.java:163-164`, `IntakeReportService.java:355-356`, `SensitivityReportService.java:287-288`, `StewardNoticeService.java:104-105`, `SensitivityLevelService.java:335-336`, `RetentionPolicyService.java:127-128`, `RoleJson.java:25-26`, `DocumentIntakeService.java:483-484`, `ControlledDownloadService.java:212-213`, `CorrectiveRedactionService.java:428-429`, `RetentionDeletionService.java:143-144`. More often, `audit.append` itself fails with SYS-014.
- **Trigger:** the audit store can't be written, so the action is not done (fail closed).
- **Today:** 409 `CONFLICT` with "…could not be audited, so nothing was changed." (good words, wrong status and no code), or a generic 500. Generic: yes.
- **Kind:** authorised but system failed.
- **User sees:** "Vero couldn't record this action, so it wasn't done. Try again in a few minutes. Ref <id>. (SDV-SYS-017)"
- **IT admin and log:** ERROR with the code, the reference, the audit action and the target type. No details JSON.
- **Fix:** restore the database; fix task as SYS-016.
- **L1 tells the customer:** Vero never acts without recording it. The database was not writable; check it, then try again.

### Sign-in (AUT, ROL)

#### SDV-AUT-001 (released in task 0038): not signed in
- **Released:** now sent (task 0038); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:**
  - `SecurityConfiguration.java:27` (entry point);
  - `SecurityContextFacade.java:30`;
  - `FoundationExceptionHandler.java:104` (`UNAUTHORIZED`, "Authentication is required.").
- **Trigger:** no token, or a request reached Vero with no sign-in.
- **Today:** 401 from Spring with its generic body and no message, or 401 `UNAUTHORIZED`. Generic: yes. The screen sends the person to sign in again (`api.ts:65`).
- **Kind:** not authorised.
- **User sees:** "Please sign in again." (SDV-AUT-001) Nothing about the account.
- **IT admin and log:** DEBUG only. These are frequent and normal.
- **Fix:** none; the person signs in.
- **L1 tells the customer:** the session ended. Sign in again.

#### SDV-AUT-002 (released in task 0038): wrong kind of sign-in for this address
- **Released:** now sent (task 0038); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `SecurityContextFacade.java:12` (a sending system's token on a person's endpoint) and `:21` (a person's token on an intake endpoint).
- **Trigger:** a person's token is used on `/api/v1/ingestion/…`, or a sending system's token on a person's endpoint.
- **Today:** **401** `UNAUTHORIZED` "Authentication is required.". That is wrong: the caller is signed in, and retrying sign-in won't help. Generic: yes.
- **Kind:** not authorised.
- **User sees:** "This address is for sending systems only." / "This address is for people, not sending systems." (SDV-AUT-002), sent with 403.
- **IT admin and log:** INFO with the code, the reference and the principal type.
- **Fix:** the sending system uses its own client credentials.
- **L1 tells the customer:** the program signed in as the wrong kind of user. Its setup must use the sending system's own client.

#### SDV-AUT-003 (released in task 0038): token not accepted
- **Released:** now sent (task 0038); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** Spring's resource server (signature, expiry, issuer, audience) and `MaximumTokenLifetimeValidator.java`. Answer: `WWW-Authenticate: Bearer error="invalid_token", error_description=…`.
- **Trigger:** an expired, forged or wrongly issued token, or one with too long a lifetime.
- **Today:** 401 with Spring's English description. Not logged. Generic: yes.
- **Kind:** not authorised.
- **User sees:** "Please sign in again." (SDV-AUT-003)
- **IT admin and log:** INFO with the code, the reference and the reason class (expired, signature, issuer, audience, lifetime). Never the token.
- **Fix:** sign in again. If it happens to everyone, check the sign-in hub's issuer and audience settings.
- **L1 tells the customer:** the sign-in has expired, or wasn't issued for Vero. If everyone sees it, their IT team checks the sign-in hub's settings for Vero.

#### SDV-AUT-004 (released in task 0038): sign-in hub's keys can't be fetched
- **Released:** now sent (task 0038); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** Spring's JWKS fetch (`SDV_AUTH_JWT_JWK_SET_URI`). It is not checked at start.
- **Trigger:** the sign-in hub (Keycloak) is down or unreachable when Vero needs its keys.
- **Today:** from the code, this becomes a 401 `invalid_token` or a 500, depending on Spring's handling (to confirm with a test). Either way, nothing says the sign-in hub is down. Generic: yes.
- **Kind:** outside dependency down.
- **User sees:** "Vero can't check sign-ins right now because the sign-in service isn't answering. Try again in a few minutes. (SDV-AUT-004)"
- **IT admin and log:** ERROR with the code, the reference and the JWKS host.
- **Fix:** bring the sign-in hub back. Fix task: add the JWKS fetch to the health checks.
- **L1 tells the customer:** their sign-in service is unavailable; nobody can sign in to Vero until it is back.

#### SDV-AUT-005 (released in task 0038): sign-in claims not usable
- **Released:** now sent (task 0038); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `EnterpriseJwtAuthenticationConverter.java:121`, `:123`, `:125`, `:152`, `:171`, `:178`, `:185`, `:188`. There are 7 descriptions: subject missing, claim missing or not a string, claim too long, tenant or source not a UUID, both a user and a source in one token.
- **Trigger:** the sign-in hub sends a token without the claims Vero needs.
- **Today:** 401 `invalid_token` with Vero's English description. Not logged. Generic: yes.
- **Kind:** needs a person.
- **User sees:** "Your sign-in is missing details Vero needs. Your IT team can fix this. (SDV-AUT-005)"
- **IT admin and log:** WARN with the code, the reference and the claim's name (never its value).
- **Fix:** add the claim mapper in the sign-in hub.
- **L1 tells the customer:** the sign-in hub isn't sending a detail Vero needs; the log names it.

#### SDV-AUT-006 (released in task 0038): no sign-in connection for this issuer
- **Released:** now sent (task 0038); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `EnterpriseJwtAuthenticationConverter.java:159`, `:162`.
- **Trigger:** the token's issuer or audience matches no ACTIVE connection set up by IT.
- **Today:** 401 `invalid_token`, "No ACTIVE persisted OIDC connection matches…". Generic: yes.
- **Kind:** needs a person.
- **User sees:** "Vero isn't set up to accept this sign-in. Your IT team can fix this. (SDV-AUT-006)"
- **IT admin and log:** WARN with the code, the reference and the issuer's host.
- **Fix:** IT activates the connection (Identity settings).
- **L1 tells the customer:** the sign-in connection isn't active in Vero. IT checks Identity settings.

#### SDV-AUT-007 (released in task 0038): no Vero role from the sign-in groups
- **Released:** now sent (task 0038); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `SdvGroupRoleMapper.java:53`, `RoleSeparationGuard.java:59` (`NO_ROLE`).
- **Trigger:** the person is in none of the mapped groups.
- **Today:** 401 `invalid_token`, "Authenticated identity has no mapped SDV role." Generic: yes.
- **Kind:** needs a person.
- **User sees:** "You're signed in, but you don't have access to Vero. Ask your IT team to add you to a Vero group. (SDV-AUT-007)" It says nothing about other accounts.
- **IT admin and log:** INFO with the code, the reference and the subject. Not the group list.
- **Fix:** add the person to a mapped group.
- **L1 tells the customer:** the person needs to be in one of the Vero sign-in groups.

#### SDV-AUT-008 (released in task 0038): local test sign-in headers not valid
- **Released:** now sent (task 0038); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `LocalHeaderAuthenticationFilter.java:86`, `:90`, `:110`, `:131-132`. Local profile only.
- **Trigger:** a missing or malformed `X-SDV-*` header on a developer machine.
- **Today:** 401 from `sendError` with Spring's generic body. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** "Local sign-in headers are not valid: <which>. (SDV-AUT-008)"
- **IT admin and log:** DEBUG.
- **Fix:** send the headers the local guide lists.
- **L1 tells the customer:** not used by customers.

#### SDV-AUT-009 (released in task 0038): organisation not found or not active
- **Released:** now sent (task 0038); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** "Tenant not found." (404) and "Tenant is not active." (409):
  - `DocumentIntakeService.java:546-547`, `SubmissionService.java:244-245`;
  - `BusinessExperienceService.java:839`, `:841`;
  - `MetadataFieldAdminService.java:268-269`;
  - `OperationsService.java:177-178`;
  - `IngestionSourceAdminService.java:60`, `:62`, `:125`, `:127`;
  - `StorageVolumeAdminService.java:186-187`;
  - `RetentionPolicyService.java:44`, `:69`;
  - `CorrectiveRedactionService.java:113`.
- **Trigger:** the token names a tenant that doesn't exist or is suspended.
- **Today:** 404 `NOT_FOUND` or 409 `CONFLICT`. Two different answers tell the caller whether the tenant exists. Generic: yes.
- **Kind:** not authorised.
- **User sees:** "Your organisation's Vero account isn't available. Contact your IT team. (SDV-AUT-009)", the same for both, with 403.
- **IT admin and log:** WARN with the code, the reference, the tenant ID and whether it was missing or suspended.
- **Fix:** reactivate the tenant, or correct the sign-in hub's tenant claim.
- **L1 tells the customer:** their organisation's Vero account is suspended or misconfigured. Escalate to Vero operations.

#### SDV-ROL-006 (released in task 0038): the role doesn't include this action
- **Released:** now sent (task 0038); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:**
  - `AuthorizationService.java:27`, answered by `FoundationExceptionHandler.java:98` (`FORBIDDEN`, "The authenticated principal does not have the required capability.");
  - `SecurityConfiguration.java:28` ("Access is denied.").
  - Used by every capability check without a coded wrapper: viewer, download, print, tags, comments, activity, redaction, search, records, Operations, Audit, storage, sending systems, fields, retention policy, Roles & permissions, role conflicts, Identity settings and session.
- **Trigger:** the person's role lacks the capability.
- **Today:** 403 `FORBIDDEN`. Audited as `AUTHORIZATION_DENIED` (the audit write is swallowed if it fails, `AuthorizationService.java:56`). Generic: yes. The released area codes (SEN-010, HLD-001, APR-001/005/015, INT-001, RPT-001) cover only their own areas.
- **Kind:** not authorised.
- **User sees:** "Your role doesn't include <action in plain words>. Ask someone who has it. (SDV-ROL-006)"
- **IT admin and log:** INFO with the code, the reference and the capability.
- **Fix:** none, or change the person's groups.
- **L1 tells the customer:** working as intended. Roles come from the customer's sign-in groups.

### Intake: API (INT)

The watch folder and polling intake are **not in this repository**. Sending systems deliver only through the API (`/api/v1/ingestion/…`). There are no failure points to list for them.

#### SDV-INT-004 (released in task 0039): no such submission
- **Released:** now sent (task 0039); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DocumentIntakeService.java:144`, `:277`, `SubmissionService.java:180`, `:189`, `:239`, `JdbcSecureIngestionRepository.java:202`.
- **Trigger:** the submission ID names no submission of this sending system.
- **Today:** 404 `NOT_FOUND` "Submission not found." Generic: yes.
- **Kind:** not found.
- **User sees:** "No submission of this sending system has this ID. Create the submission first, or check the ID. (SDV-INT-004)"
- **IT admin and log:** INFO with the code and the reference.
- **Fix:** the sender uses the `submissionId` returned by create.
- **L1 tells the customer:** the sending system used a wrong or another system's submission ID.

#### SDV-INT-005 (released in task 0039): sending system unknown or paused
- **Released:** now sent (task 0039); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DocumentIntakeService.java:553`, `:555`, `SubmissionService.java:251`, `:253`.
- **Trigger:** the token's source ID isn't a sending system of the tenant (404), or IT paused it (409).
- **Today:** 404 `NOT_FOUND` or 409 `CONFLICT` "Ingestion source is not active." Generic: yes.
- **Kind:** not authorised (paused), or needs a person (unknown).
- **User sees:** "This sending system is paused or not set up in Vero. Nothing was stored. (SDV-INT-005)", with 403.
- **IT admin and log:** WARN with the code, the reference and the source ID.
- **Fix:** IT reactivates the sending system (Integrations), or corrects the client's source claim.
- **L1 tells the customer:** the sending system is paused in Vero, or its sign-in names a system Vero doesn't know. IT checks Integrations.

#### SDV-INT-006 (released in task 0039): submission request incomplete
- **Released:** now sent (task 0039); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `SubmissionService.java:265`, `:268`, `:453`, `:455`.
- **Trigger:** no body, `expectedDocumentCount` below 1, or a required text field missing or too long.
- **Today:** 400 `BAD_REQUEST` with a plain message naming the field. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-INT-006)".
- **IT admin and log:** INFO with the code, the reference and the field.
- **Fix:** the sender corrects the field.
- **L1 tells the customer:** the sending system left out a required field; the message names it. Nothing was created.

#### SDV-INT-007 (released in task 0039): document list not valid
- **Released:** now sent (task 0039); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `SubmissionService.java:120`, `:123`, `:276`.
- **Trigger:** `expectedDocumentCount` is above the limit, there are more listed documents than the count, or a listed `sourceDocumentId` appears twice.
- **Today:** 400 `BAD_REQUEST`. The `:276` message repeats the producer's `sourceDocumentId`. That is an ID, not content, but it is the sender's own value. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-INT-007)".
- **IT admin and log:** INFO with the code and the reference.
- **Fix:** the sender corrects the list of documents.
- **L1 tells the customer:** the submission's list of expected documents is inconsistent; nothing was created.

#### SDV-INT-008 (released in task 0039): metadata field unknown or missing
- **Released:** now sent (task 0039); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `SubmissionService.java:285`, `:311`, `:316`, `MetadataValueValidator.java:26`, `SubmissionController.java:106`.
- **Trigger:** the sender uses a field this sending system doesn't have, leaves out a required one, sends `null`, or sends a non-scalar JSON value.
- **Today:** 400 `BAD_REQUEST`, naming the field key (never the value). Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-INT-008)".
- **IT admin and log:** INFO with the code, the reference and the field key.
- **Fix:** the sender sends the fields IT set up, or IT adds the field.
- **L1 tells the customer:** the sending system and Vero's field setup disagree about a field; the message names it.

#### SDV-INT-009 (released in task 0039): metadata value not valid
- **Released:** now sent (task 0039); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `MetadataValueValidator.java:46`, `:53`, `:58-59`, `:60-62`, `:92`, `:103`, `:107`, `:123`, `:126`, `:136`.
- **Trigger:** a value of the wrong type, pattern, range or length.
- **Today:** 400 `BAD_REQUEST`, naming the field key and the rule (never the value). Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-INT-009)".
- **IT admin and log:** INFO with the code, the reference, the field key and the rule.
- **Fix:** the sender corrects the value.
- **L1 tells the customer:** a field value breaks the rule IT set for it; the message names the field and the rule.

#### SDV-INT-010 (released in task 0039): a field's own rules are broken
- **Released:** now sent (task 0039); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `MetadataValueValidator.java:94-95`, `:112-113`, `:121`, `:134`.
- **Trigger:** the stored rule for a field (pattern, length) can't be used.
- **Today:** 409 `CONFLICT`. The sender is told to change the request, but the fault is Vero's setup. Generic: yes.
- **Kind:** needs a person.
- **User sees:** "Vero's setup for field '<key>' is broken, so this submission can't be checked. Nothing was stored. Your IT team has been told. (SDV-INT-010)"
- **IT admin and log:** ERROR with the code, the reference, the field key and which rule.
- **Fix:** IT corrects the field's rule.
- **L1 tells the customer:** a field's rule in Vero is broken; IT fixes it, then the sender re-sends.

#### SDV-INT-011 (released in task 0039): submission retry differs from the first
- **Released:** now sent (task 0039); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `SubmissionService.java:390`, `:409`. (Retries that differ only in record level are SDV-SEN-004.)
- **Trigger:** the same `sourceSubmissionId` was sent again with different content.
- **Today:** 409 `CONFLICT` "sourceSubmissionId already exists with a different submission create request." Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-INT-011)".
- **IT admin and log:** INFO with the code and the reference.
- **Fix:** the sender repeats the first request exactly, or uses a new `sourceSubmissionId`.
- **L1 tells the customer:** the submission already exists; a retry must match it exactly.

#### SDV-INT-012 (released in task 0039): document upload has no identity
- **Released:** now sent (task 0039); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DocumentIntakeService.java:493`, `:499`, `:504`.
- **Trigger:** neither `X-SDV-Source-Document-Id` nor `Idempotency-Key` is sent, or the ID isn't in the submission's complete list.
- **Today:** 400 `BAD_REQUEST`. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-INT-012)".
- **IT admin and log:** INFO with the code and the reference.
- **Fix:** the sender sends a listed `X-SDV-Source-Document-Id`.
- **L1 tells the customer:** each document needs its ID from the submission's list.

#### SDV-INT-013 (released in task 0039): upload headers not valid
- **Released:** now sent (task 0039); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DocumentIntakeService.java:581`, `:584`, `:591`, `:594`, `:596`, `:605`.
- **Trigger:** a missing, too long or path-like file name; a text header too long; a content type other than PDF (mostly refused earlier by SYS-015).
- **Today:** 400 `BAD_REQUEST`. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-INT-013)".
- **IT admin and log:** INFO with the code, the reference and the header's name.
- **Fix:** the sender corrects the header.
- **L1 tells the customer:** the upload's file name or headers are wrong; the message names which.

#### SDV-INT-014 (released in task 0039): PDF too large
- **Released:** now sent (task 0039); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DocumentIntakeService.java:156`, `ValidatedPdfInputStream.java:56`.
- **Trigger:** the PDF is bigger than the limit for the tenant or sending system.
- **Today:** 413 `PDF_TOO_LARGE` with the limit in bytes. Generic: no (own code, not in SDV form).
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-INT-014)".
- **IT admin and log:** INFO with the code, the reference and the limit.
- **Fix:** send a smaller file, or IT raises the sending system's limit.
- **L1 tells the customer:** the file is over the size limit; nothing was stored.

#### SDV-INT-015 (released in task 0039): not a PDF
- **Released:** now sent (task 0039); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `ValidatedPdfInputStream.java:73`, `:74`, `:77`.
- **Trigger:** the body is empty, cut short, or doesn't start with a PDF header.
- **Today:** 400 `INVALID_PDF`. Generic: no (own code, not in SDV form).
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-INT-015)".
- **IT admin and log:** INFO with the code and the reference.
- **Fix:** the sender sends the PDF itself.
- **L1 tells the customer:** what was sent isn't a PDF, or was cut short; send it again.

#### SDV-INT-016 (released in task 0039): submission already full
- **Released:** now sent (task 0039); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DocumentIntakeService.java:332` (409, count) and `:365` (400, total size).
- **Trigger:** the submission already has its expected number of documents, or this document takes it over the total size.
- **Today:** 409 `CONFLICT` and 400 `BAD_REQUEST`. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-INT-016)".
- **IT admin and log:** INFO with the code and the reference.
- **Fix:** send the document on a new submission.
- **L1 tells the customer:** the submission is complete; extra documents go on a new one.

#### SDV-INT-017 (released in task 0039): document retry differs from the first
- **Released:** now sent (task 0039); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DocumentIntakeService.java:258-259` (duplicate replay) and `:531-532` (`ensureReplayMatches`). (Different level only: SDV-SEN-003.)
- **Trigger:** the same document ID was sent again with different content or a different file name.
- **Today:** 409 `CONFLICT`. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-INT-017)".
- **IT admin and log:** INFO with the code, the reference and the document ID.
- **Fix:** repeat the first upload exactly, or send a new document ID.
- **L1 tells the customer:** a document with this ID is already stored with other content. A changed document needs a new ID.

#### SDV-INT-018 (released in task 0039): document can't be finished again
- **Released:** now sent (task 0039); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DocumentIntakeService.java:290` (404 "Document not found."), `:294` (409).
- **Trigger:** retry-finalization on an unknown document, or on one that is not received, failed or available.
- **Today:** 404 `NOT_FOUND` or 409 `CONFLICT`. Generic: yes.
- **Kind:** not found, or reached but refused.
- **User sees:** "This document can't be finished again in its current state (<state>). (SDV-INT-018)"
- **IT admin and log:** INFO with the code and the reference.
- **Fix:** none; check the document's state first.
- **L1 tells the customer:** the document is already being handled, or was deleted.

### Processing, storage and encryption (PRC, STO, KEY)

#### SDV-PRC-001 (proposed): stored copy missing after receipt
- **Where:** `DocumentFinalizationService.java:117`, `:119`, `:121`, `:125`, `:168`, all recorded as `DURABLE_OBJECT_VALIDATION_FAILED` (`:171-174`).
- **Trigger:** the storage reference, the key record, the volume or the encrypted file itself can't be found right after writing.
- **Today:** the upload answers **201 with `currentStatus: PROCESSING_FAILED`** and `processingErrorCode`. Audited `DOCUMENT_PROCESSING_FAILED`. Not logged. Problems shows "could not store". Generic: yes (one code for many causes).
- **Kind:** accepted then failed.
- **User sees (sending system):** `processingErrorCode: SDV-PRC-001` with "Vero received the document but couldn't find its stored copy. It will be shown under Problems; retry with retry-finalization."
- **IT admin and log:** ERROR with the code, the reference, the document ID and which piece was missing.
- **Fix:** check the storage volume, then retry-finalization.
- **L1 tells the customer:** the document arrived but its stored copy is missing. IT checks the storage volume on Problems, then the sender retries.

#### SDV-PRC-002 (proposed): stored copy doesn't match what was received
- **Where:** `DocumentFinalizationService.java:144`, `:147`, `:150`, `:153`, `:260`, `:264` (and decryption failures, KEY-004), recorded as `DURABLE_OBJECT_VALIDATION_FAILED`.
- **Trigger:** the size or fingerprint of the encrypted or decrypted copy differs from what was received.
- **Today:** as PRC-001. Generic: yes.
- **Kind:** accepted then failed.
- **User sees (sending system):** `processingErrorCode: SDV-PRC-002` with "Vero received the document but its stored copy didn't check out, so it won't be shown. Send the document again."
- **IT admin and log:** ERROR with the code, the reference, the document ID and which check (size or fingerprint, encrypted or decrypted).
- **Fix:** check the storage hardware; the sender re-sends.
- **L1 tells the customer:** Vero refused to keep a copy it couldn't verify. If it repeats, the storage may be failing.

#### SDV-PRC-003 (proposed): key service down while finishing
- **Where:** `DocumentFinalizationService.java:97`, `:171-174`. A key service `IOException` (KEY-002) is recorded as `DURABLE_OBJECT_VALIDATION_FAILED`.
- **Trigger:** Vault is unreachable or refuses during the read-back check.
- **Today:** recorded as a storage integrity problem, which is misleading. Generic: yes.
- **Kind:** outside dependency down.
- **User sees (sending system):** `processingErrorCode: SDV-PRC-003` with "Vero received the document but its key service didn't answer. Retry with retry-finalization later."
- **IT admin and log:** ERROR with the code, the reference, the document ID and Vault's HTTP status.
- **Fix:** restore Vault, then retry-finalization. Fix task: classify crypto failures apart from storage failures.
- **L1 tells the customer:** the key service was unavailable. Once it is back, the sender retries; nothing needs re-sending.

#### SDV-PRC-004 (proposed): storage security check failed while finishing
- **Where:** `DocumentFinalizationService.java:172` (`STORAGE_SECURITY_VALIDATION_FAILED`), from `LocalFilesystemStorageProvider.java:91`.
- **Trigger:** the volume belongs to another tenant.
- **Today:** `PROCESSING_FAILED` with its own code; not logged. Generic: no (own code).
- **Kind:** authorised but system failed.
- **User sees (sending system):** `processingErrorCode: SDV-PRC-004` with "Vero stopped storing this document for a security reason. Your IT team has been told."
- **IT admin and log:** ERROR (security) with the code, the reference, the tenant ID and the volume ID.
- **Fix:** Vero support investigates; this should never happen.
- **L1 tells the customer:** escalate to Vero security at once.

#### SDV-PRC-005 (proposed): finishing failed for another reason
- **Where:** `DocumentFinalizationService.java:97` (catch-all, `DOCUMENT_FINALIZATION_FAILED`). If marking the failure itself fails (`:99-101`), the exception escapes. The document stays `PROCESSING` and later shows as "Stuck".
- **Trigger:** an unexpected failure while finishing.
- **Today:** `PROCESSING_FAILED`, `DOCUMENT_FINALIZATION_FAILED`; not logged. Generic: yes.
- **Kind:** accepted then failed.
- **User sees (sending system):** `processingErrorCode: SDV-PRC-005` with "Vero received the document but couldn't finish storing it. Retry with retry-finalization."
- **IT admin and log:** ERROR with the code, the reference, the document ID and the exception class.
- **Fix:** retry-finalization; if it repeats, Vero support.
- **L1 tells the customer:** pass the document ID and time to Vero support.

#### SDV-STO-001 (released in task 0036): no storage can take the document
- **Released:** now sent; its section is under "Storage (STO)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `StoragePlacementService.java:38` (`NoWritableStorageException`); probe failures are swallowed at `:56` as "not eligible".
- **Trigger:** every ACTIVE volume is full, at its stop-write level, unwritable, or unreadable.
- **Today:** **409 `CONFLICT`** "No healthy ACTIVE storage volume can safely accept the requested content." That tells the sender to change the request; it is counted as a refusal. Not logged. Generic: yes.
- **Kind:** needs a person.
- **User sees (sending system):** "Vero has no storage space for new documents right now. Nothing was stored; send it again later. (SDV-STO-001)", with 503.
- **IT admin and log:** ERROR with the code, the reference and each volume's reason (full, stop-write, unwritable, unreadable).
- **Fix:** IT frees space, adds a volume, or raises the stop-write level.
- **L1 tells the customer:** Vero's storage is full. IT adds or frees storage; senders re-send.

#### SDV-STO-002 (proposed): writing to storage failed
- **Where:** `LocalFilesystemStorageProvider.java:33`, `:57-58`, `:70`, plus `IOException`s from the file system (disk full, permission) during `store`. Called from `DocumentIntakeService.java:191` and `CorrectiveRedactionService.java`.
- **Trigger:** the volume went inactive or offline, the file system refused, or the volume can't move files safely.
- **Today:** intake gets 500 with Spring's generic body (SYS-013); not logged by Vero. Generic: yes.
- **Kind:** outside dependency down.
- **User sees (sending system):** "Vero couldn't write to its storage. Nothing was stored; send it again later. Ref <id>. (SDV-STO-002)", with 503.
- **IT admin and log:** ERROR with the code, the reference, the volume ID and the reason.
- **Fix:** IT checks the volume (Storage), then senders re-send.
- **L1 tells the customer:** storage refused a write. IT checks the volume's health.

#### SDV-STO-003 (proposed): storage safety check failed
- **Where:** `LocalFilesystemStorageProvider.java:90-92`, `:96`, `:99`, `:102`.
- **Trigger:** wrong tenant, unknown provider, bad object key, or a key that escapes the volume's folder.
- **Today:** 500 generic, or a `PROCESSING_FAILED` code. Not logged. Generic: yes.
- **Kind:** authorised but system failed.
- **User sees:** "Vero stopped this for a security reason. Your IT team has been told. Ref <id>. (SDV-STO-003)"
- **IT admin and log:** ERROR (security) with the code, the reference, the volume ID and the check.
- **Fix:** Vero support.
- **L1 tells the customer:** escalate to Vero security.

#### SDV-STO-004 (released for the upload in task 0040 part 2): storage type not installed
- **Released for the upload:** the upload now sends it (503); its section is under "Storage (STO)" above. The other places below still answer as "Today" says.
- **Where:** `DocumentIntakeService.java:564` (409), `DocumentFinalizationService.java:168`, `DocumentViewerService.java:484`, `ControlledDownloadService.java:197`, `CorrectiveRedactionService.java:374`, `RetentionDeletionService.java:129`.
- **Trigger:** a volume names a storage provider this install doesn't have.
- **Today:** 409 `CONFLICT` (intake), 422 `VIEWER_UNAVAILABLE` or `DOWNLOAD_UNAVAILABLE`, or `REDACTION_FAILED`. Generic: yes.
- **Kind:** needs a person.
- **User sees:** "Vero can't use the storage this document is on. Your IT team has been told. Ref <id>. (SDV-STO-004)"
- **IT admin and log:** ERROR with the code, the reference and the provider ID.
- **Fix:** install or configure the provider, or move the volume.
- **L1 tells the customer:** an install problem; escalate to Vero operations.

#### SDV-STO-005 (released in task 0037): storage volume settings not valid
- **Released:** now sent (task 0037); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `StorageVolumeAdminService.java:75`, `:76`, `:110`, `:112`, `:113`, `:166`, `:197`, `:198`, `:202`, `:203`, `:204`; `StorageAdminController.java:50`; `LocalFilesystemVolumeProbe.java:28`, `:36-37`, `:111`, `:114-115`.
- **Trigger:** IT enters a missing, out-of-range, overlapping or unwritable value for a volume.
- **Today:** 400 `BAD_REQUEST` with a plain message (`:37` includes the folder path IT typed). Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-STO-005)".
- **IT admin and log:** INFO with the code and the reference.
- **Fix:** correct the value.
- **L1 tells the customer:** a storage setting is out of range; the message says which.

#### SDV-STO-006 (proposed): no such storage volume
- **Where:** `StorageVolumeAdminService.java:193`.
- **Trigger:** the volume ID names no volume of the tenant.
- **Today:** 404 `NOT_FOUND`. Generic: yes.
- **Kind:** not found.
- **User sees:** "No storage volume has this ID. Reload Storage. (SDV-STO-006)"
- **IT admin and log:** INFO.
- **Fix:** none.
- **L1 tells the customer:** reload the Storage page.

#### SDV-KEY-001 (released in task 0037): encryption not set up
- **Released:** now sent (task 0037); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DisabledCryptoConfiguration.java:29`, `:34` (`SDV_KEY_PROVIDER` left at its default `disabled`).
- **Trigger:** a document is uploaded, viewed or downloaded with no key service configured.
- **Today:** upload gets 500 with Spring's generic body; viewer and download get 422 `VIEWER_UNAVAILABLE` or `DOWNLOAD_UNAVAILABLE`. Nothing at start says encryption is off. Generic: yes.
- **Kind:** needs a person.
- **User sees:** "Vero can't store or open documents until its key service is set up. Your IT team has been told. (SDV-KEY-001)"
- **IT admin and log:** WARN at start, plus ERROR per attempt, with the code.
- **Fix:** set `SDV_KEY_PROVIDER=vault` and the Vault settings.
- **L1 tells the customer:** the install has no key service configured; see the install guide.

#### SDV-KEY-002 (proposed): key service unreachable
- **Where:** `VaultTransitKeyProtectionProvider.java:125` (HTTP other than 2xx), `:129-131` (interrupted), `:138` (field missing), `:93`, `:100`, `:105` (unexpected key). Reached from intake, finishing, the viewer, download and redaction.
- **Trigger:** Vault is down, sealed, or answers badly.
- **Today:**
  - upload: 500 generic;
  - finishing: `DURABLE_OBJECT_VALIDATION_FAILED`;
  - viewer: 422 `VIEWER_UNAVAILABLE`;
  - download: 422 `DOWNLOAD_UNAVAILABLE`;
  - redaction: 422 `REDACTION_FAILED`.
  - None says the key service is down; none is logged. Generic: yes.
- **Kind:** outside dependency down.
- **User sees:** "Vero's key service isn't answering, so documents can't be stored or opened right now. Try again in a few minutes. Ref <id>. (SDV-KEY-002)", with 503.
- **IT admin and log:** ERROR with the code, the reference and Vault's HTTP status (never the token or the key reference).
- **Fix:** bring Vault back or unseal it. Operations shows the key service as DOWN.
- **L1 tells the customer:** Vero's key service is unavailable; nothing is lost. Retry once it is back.

#### SDV-KEY-003 (proposed): key service refused Vero's token
- **Where:** `VaultTransitKeyProtectionProvider.java:125` when the HTTP status is 403 (a subcase of KEY-002 today).
- **Trigger:** the Vault token has expired or was revoked. It is read once at start (`VaultCryptoConfiguration.java:36-52`).
- **Today:** as KEY-002. Generic: yes.
- **Kind:** needs a person.
- **User sees:** as KEY-002, with code SDV-KEY-003.
- **IT admin and log:** ERROR with the code, "Vault refused the token (HTTP 403)". Never the token.
- **Fix:** issue a new token into the secret store, then restart Vero.
- **L1 tells the customer:** Vero's key service access has expired. Their IT team renews it and restarts Vero.

#### SDV-KEY-004 (proposed): stored document failed decryption
- **Where:** `SdvEnc001StreamingCrypto.java:122`, `:125`, `:130`, `:136-137`, `:139`, `:140`, `:142`, `:145`, `:153-154`, `:160`, `:161`, `:163`, `:166`, `:179`, `:182`, `:185`, `:187`, `:192`, `:234-235`, `:236-237`.
- **Trigger:** the encrypted file is damaged, truncated or tampered with, or the key doesn't match.
- **Today:** viewer, download, finishing and redaction each give their own generic answer. Not logged, so a possible tampering is invisible. Generic: yes.
- **Kind:** authorised but system failed.
- **User sees:** "This document's stored copy can't be opened safely. Your IT team has been told. Ref <id>. (SDV-KEY-004)"
- **IT admin and log:** ERROR (security) with the code, the reference, the document ID and the check that failed (header, chunk, tag, final marker).
- **Fix:** Vero support. Treat as possible tampering; restore from backup.
- **L1 tells the customer:** escalate to Vero security with the document ID.

#### SDV-KEY-005 (proposed): encryption failed while storing
- **Where:** `SdvEnc001StreamingCrypto.java:68`, `:213-214`, `:274-275`.
- **Trigger:** the key service gave a bad key, or the Java crypto library failed.
- **Today:** upload gets 500 generic. Generic: yes.
- **Kind:** authorised but system failed.
- **User sees (sending system):** "Vero couldn't encrypt the document, so nothing was stored. Send it again later. Ref <id>. (SDV-KEY-005)"
- **IT admin and log:** ERROR with the code and the reference.
- **Fix:** Vero support.
- **L1 tells the customer:** pass the reference to Vero support.

#### SDV-STO-007 (proposed): encrypted size doesn't match at receipt
- **Where:** `DocumentIntakeService.java:202-203`, `:221-223`.
- **Trigger:** the file written doesn't have the size the encryption reported, or the receipt transaction returned nothing.
- **Today:** 500 generic, or 409 `CONFLICT`. The file is deleted (`:202`, `:218`, `:222`). Generic: yes.
- **Kind:** authorised but system failed.
- **User sees (sending system):** "Vero couldn't store the document safely, so nothing was kept. Send it again. Ref <id>. (SDV-STO-007)"
- **IT admin and log:** ERROR with the code, the reference and the volume ID.
- **Fix:** check the storage volume.
- **L1 tells the customer:** a storage fault; the sender re-sends.

### Viewing, search, print and download (DOC, SCH)

#### SDV-DOC-001 (released in task 0043): no such document
- **Released:** now sent; its section is under "Viewing, print and download (DOC)" or "Search (SCH)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DocumentActionService.java:124`, `ControlledDownloadService.java:185`, `DocumentViewerService.java:463`.
- **Trigger:** the document ID names no document of the person's organisation.
- **Today:** 404 `NOT_FOUND` "Document not found." Generic: yes. (Sensitivity, approvals and legal holds already use SEN-016, APR-013 and HLD-004 for this case.)
- **Kind:** not found.
- **User sees:** "This document isn't there any more, or the link is wrong. Open the record again from search. (SDV-DOC-001)"
- **IT admin and log:** INFO.
- **Fix:** none.
- **L1 tells the customer:** open the record from search; old links can point to deleted documents.

#### SDV-DOC-002 (released in task 0043): document not available
- **Released:** now sent; its section is under "Viewing, print and download (DOC)" or "Search (SCH)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DocumentActionService.java:127`, `ControlledDownloadService.java:188`, `DocumentViewerService.java:466`.
- **Trigger:** the document is still being stored, failed, is being corrected, or was deleted.
- **Today:** 409 `CONFLICT` "Only AVAILABLE documents…". Generic: yes.
- **Kind:** reached but refused.
- **User sees:** "This document can't be opened yet: <state in plain words>. (SDV-DOC-002)"
- **IT admin and log:** INFO.
- **Fix:** wait, or see Problems.
- **L1 tells the customer:** the document is still being processed or was removed.

#### SDV-DOC-003 (released in task 0043): viewer can't read the stored copy
- **Released:** now sent; its section is under "Viewing, print and download (DOC)" or "Search (SCH)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DocumentViewerService.java:391`, `:395`, `:399`, `:443-447` (catch-all "cannot be securely reconstructed"), `:532-534` (`unavailable`).
- **Trigger:** storage metadata is missing, the volume is missing, or storage can't be read.
- **Today:** 422 `VIEWER_UNAVAILABLE` "The document cannot be viewed securely right now." One code for every viewer cause, and no log at all. Generic: yes.
- **Kind:** authorised but system failed.
- **User sees:** "Vero couldn't read this document from storage. Try again later. Ref <id>. (SDV-DOC-003)"
- **IT admin and log:** ERROR with the code, the reference, the document ID and the cause.
- **Fix:** check the storage volume.
- **L1 tells the customer:** a storage problem; IT checks Storage on Operations.

#### SDV-DOC-004 (released in task 0043): stored copy failed its check before viewing
- **Released:** now sent; its section is under "Viewing, print and download (DOC)" or "Search (SCH)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DocumentViewerService.java:405` (too big to view safely), `:433`, `:438`, `:592-593`, `:600`, `:609`, `:618`, `:621`, `:625`, `:630`, `:652-653`.
- **Trigger:** the encrypted or decrypted size or fingerprint no longer matches what was stored, or the document is too big for the in-memory viewer.
- **Today:** 422 `VIEWER_UNAVAILABLE`, not logged. Generic: yes.
- **Kind:** authorised but system failed.
- **User sees:** "This document's stored copy didn't pass Vero's check, so it isn't shown. Ref <id>. (SDV-DOC-004)", or, for the size case, "This document is too large to view here."
- **IT admin and log:** ERROR (integrity) with the code, the reference, the document ID and which check.
- **Fix:** Vero support; restore the copy from backup.
- **L1 tells the customer:** escalate with the reference; the copy may be damaged.

#### SDV-DOC-005 (released in task 0043): document can't be drawn
- **Released:** now sent; its section is under "Viewing, print and download (DOC)" or "Search (SCH)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DocumentViewerService.java:193`, `:260`, `:269`, `:490-492`.
- **Trigger:** the PDF can't be loaded or a page can't be drawn as an image.
- **Today:** 422 `VIEWER_UNAVAILABLE`. Generic: yes.
- **Kind:** authorised but system failed.
- **User sees:** "Vero can't draw this page. Download it if your role allows, or tell your IT team. Ref <id>. (SDV-DOC-005)"
- **IT admin and log:** WARN with the code, the reference, the document ID and the page.
- **Fix:** Vero support, with the document ID.
- **L1 tells the customer:** the file is stored, but the viewer can't draw it.

#### SDV-DOC-006 (released in task 0043): viewer request not valid
- **Released:** now sent; its section is under "Viewing, print and download (DOC)" or "Search (SCH)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DocumentViewerService.java:160`, `:162`, `:171`, `:182`, `:236`, `:238`, `:246`, `:291`, `:294`.
- **Trigger:** a page number out of range, a preview count out of range, or an empty or too long search text.
- **Today:** 400 `BAD_REQUEST` with a plain message. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-DOC-006)".
- **IT admin and log:** DEBUG.
- **Fix:** none.
- **L1 tells the customer:** the screen asked for a page that doesn't exist; reload.

#### SDV-DOC-007 (released in task 0043): download can't be verified
- **Released:** now sent; its section is under "Viewing, print and download (DOC)" or "Search (SCH)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `ControlledDownloadService.java:78`, `:80`, `:82`, `:110`, `:113`, `:173-177`, `:285-286`, `:293`, `:304`, `:321`, `:324`, `:328`, `:333`, `:353-354`.
- **Trigger:** the stored copy is missing, unreadable, or fails its size or fingerprint check.
- **Today:** 422 `DOWNLOAD_UNAVAILABLE` "The current document cannot be downloaded securely right now." Audited `DOCUMENT_DOWNLOAD_FAILED` with the exception class. Not logged. Generic: yes.
- **Kind:** authorised but system failed.
- **User sees:** "Vero couldn't verify this document, so it wasn't downloaded. Ref <id>. (SDV-DOC-007)"
- **IT admin and log:** ERROR with the code, the reference, the document ID and which check.
- **Fix:** as DOC-003 or DOC-004.
- **L1 tells the customer:** as DOC-003 or DOC-004.

#### SDV-DOC-008 (released in task 0043): document changed while downloading
- **Released:** now sent; its section is under "Viewing, print and download (DOC)" or "Search (SCH)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `ControlledDownloadService.java:118`.
- **Trigger:** a correction replaced the document between preparing and sending the download.
- **Today:** 409 `CONFLICT`. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** "This document was just changed. Open it again and download the new version. (SDV-DOC-008)"
- **IT admin and log:** INFO.
- **Fix:** none.
- **L1 tells the customer:** someone corrected the document at that moment; download again.

#### SDV-DOC-009 (released in task 0043): download stopped part-way
- **Released:** now sent; its section is under "Viewing, print and download (DOC)" or "Search (SCH)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `ControlledDownloadService.java:125` (the grant is used **before** decryption starts), then `:145-153` (failures after the file has started to be sent).
- **Trigger:** storage or the key service fails during the download, or the browser disconnects.
- **Today:** the person gets a cut-short file or a broken response; the server can't send an error once the file has started. For a Highly Confidential document, the one-time approval is **used up anyway**, so the person must ask again. Audited `DOCUMENT_DOWNLOAD_FAILED`. Generic: yes.
- **Kind:** authorised but system failed.
- **User sees:** "The download didn't finish. (SDV-DOC-009)" The screen should show this when the file is shorter than announced.
- **IT admin and log:** ERROR with the code, the reference, the document ID and whether a grant was used.
- **Fix:** fix task: mark the grant used only after the last byte is sent. Until then, a Business Admin approves again.
- **L1 tells the customer:** the download was interrupted. For Highly Confidential, ask for approval again and mention the reference.

#### SDV-DOC-010 (released in task 0043): tag or comment not valid
- **Released:** now sent; its section is under "Viewing, print and download (DOC)" or "Search (SCH)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `DocumentActionService.java:66`, `:67`, `:86`, `:87`.
- **Trigger:** empty or too long.
- **Today:** 400 `BAD_REQUEST`. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-DOC-010)".
- **IT admin and log:** DEBUG.
- **Fix:** none.
- **L1 tells the customer:** shorten the text.

#### SDV-SCH-001 (released in task 0043): search criteria not valid
- **Released:** now sent; its section is under "Viewing, print and download (DOC)" or "Search (SCH)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:**
  - `BusinessExperienceService.java:243`, `:250`, `:253`, `:259`, `:262`, `:265`, `:268`, `:494`, `:498`, `:501`, `:506`;
  - `BusinessRecordRepository.java:152`, `:166`;
  - `SearchValueNormalizer.java:11`, `:12`;
  - `BusinessExperienceController.java:102`.
- **Trigger:** text too short or too long, page out of range, no criteria, dates reversed, too many fields, or a field that isn't searchable.
- **Today:** 400 `BAD_REQUEST`, naming the field key (never the value). Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-SCH-001)".
- **IT admin and log:** DEBUG.
- **Fix:** none.
- **L1 tells the customer:** change the search as the message says.

#### SDV-SCH-002 (released in task 0043): record not found or link not valid
- **Released:** now sent; its section is under "Viewing, print and download (DOC)" or "Search (SCH)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `BusinessExperienceService.java:462`; `BusinessRecordRefCodec.java:44`, `:48`, `:55`, `:63`, `:70`, `:79-80`, `:87`; `RecordReferences.java:37` (swallowed into "not found"); `RecordKey.java:32`, `:34`, `:42`, `:57`, `:84`, `:88`, `:91`, `:95`, `:99`, `:101-102`.
- **Trigger:** a record reference that is malformed, from another tenant, or no longer has documents.
- **Today:** 404 `NOT_FOUND` "Business record not found.", or 400 `BAD_REQUEST` "Business-record reference is invalid." Generic: yes.
- **Kind:** not found.
- **User sees:** "This record isn't there any more, or the link is wrong. Search for it again. (SDV-SCH-002)", the same for 400 and 404.
- **IT admin and log:** INFO.
- **Fix:** none.
- **L1 tells the customer:** search for the record again; old links can break.

#### SDV-SCH-003 (released in task 0043): recent records request not valid
- **Released:** now sent; its section is under "Viewing, print and download (DOC)" or "Search (SCH)" above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `BusinessExperienceService.java:380`, `:384`.
- **Trigger:** the limit is out of range.
- **Today:** 400 `BAD_REQUEST`. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-SCH-003)".
- **IT admin and log:** DEBUG.
- **Fix:** none.
- **L1 tells the customer:** reload.

### Corrective redaction (RED)

#### SDV-RED-001 (proposed): redaction request not valid
- **Where:** `CorrectiveRedactionService.java:78`, `:393`, `:396`, `:402`, `:403`; `RedactionRegion.java:5`, `:7`, `:12`, `:15`; `PdfSanitizingRedactor.java:44`.
- **Trigger:** no confirmation, a reason missing or of the wrong length, no regions, too many regions, a region outside the page, or a page that doesn't exist.
- **Today:** 400 `BAD_REQUEST`. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-RED-001)".
- **IT admin and log:** DEBUG.
- **Fix:** none.
- **L1 tells the customer:** follow the message; nothing was changed.

#### SDV-RED-002 (proposed): another correction is in progress
- **Where:** `CorrectiveRedactionService.java:94`, `:98`, `:168`.
- **Trigger:** a different correction of the same document is running, or a recovery holds it.
- **Today:** 409 `CONFLICT`. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** "Someone else is correcting this document right now. Try again in a few minutes. (SDV-RED-002)"
- **IT admin and log:** INFO.
- **Fix:** none.
- **L1 tells the customer:** wait for the other correction to finish.

#### SDV-RED-003 (proposed): document can't be corrected now
- **Where:** `CorrectiveRedactionService.java:91` ("incomplete corrective-redaction state requiring operational recovery", 422), `:109` (409).
- **Trigger:** the document isn't available, or an earlier correction was left half done.
- **Today:** 409 `CONFLICT` or 422 `REDACTION_FAILED`. Generic: yes.
- **Kind:** needs a person (half-done correction), or reached but refused.
- **User sees:** "This document can't be corrected right now: <reason>. (SDV-RED-003)"
- **IT admin and log:** WARN with the code, the reference and the document ID.
- **Fix:** the recovery job (every few minutes) finishes the earlier correction.
- **L1 tells the customer:** wait for Vero to finish the earlier correction, then try again.

#### SDV-RED-004 (proposed): correction failed safely
- **Where:**
  - `CorrectiveRedactionService.java:118`, `:120`, `:122`, `:148`, `:181-186`, `:247`, `:252`, `:260-261`, `:268`, `:275`, `:316`, `:326`, `:337`, `:341`, `:354`, `:357-358`, `:485`, `:488`, `:492`, `:496`, `:497`, `:498`, `:499`, `:511`;
  - `PdfSanitizingRedactor.java:62`, `:107`, `:111-112`.
- **Trigger:** storage, the key service, verification or PDF rewriting failed. The document is unchanged.
- **Today:** 422 `REDACTION_FAILED` "Corrective redaction could not complete safely." Audited `DOCUMENT_REDACTION_FAILED` with the exception class. Not logged. Generic: yes.
- **Kind:** authorised but system failed.
- **User sees:** "The correction couldn't be made, and the document is unchanged. Try again; if it fails again, give your IT team this reference: Ref <id>. (SDV-RED-004)"
- **IT admin and log:** ERROR with the code, the reference, the document ID and the step (read, rewrite, write, verify, switch, clean-up).
- **Fix:** as the step's own cause (STO or KEY).
- **L1 tells the customer:** nothing was changed; pass the reference to Vero support if it repeats.

#### SDV-RED-005 (proposed): document changed during the correction
- **Where:** `CorrectiveRedactionService.java:230`, `:235`, `:237`, `:278`, `:282`, `:284`; `JdbcDocumentRedactionRepository.java:162`, `:184`, `:197`, `:210`, `:219`.
- **Trigger:** the document's stored copy or state changed between staging and switching.
- **Today:** 409 `CONFLICT` with internal text, or 404. Generic: yes.
- **Kind:** authorised but system failed.
- **User sees:** "The document changed while it was being corrected. Open it again and redo the correction. (SDV-RED-005)"
- **IT admin and log:** WARN with the code, the reference and the document ID.
- **Fix:** none.
- **L1 tells the customer:** redo the correction on the current version.

#### SDV-RED-006 (proposed): correction recovery job failed
- **Where:** `RedactionRecoveryScheduler.java:34-35` (WARN with stack trace, no code, no reference); `CorrectiveRedactionService.java:206` (per document; counted and audited with the class name); `:194` (limit).
- **Trigger:** a half-done correction can't be finished by the recovery job.
- **Today:** WARN "Corrective-redaction recovery sweep failed safely…"; per document, only a count. Generic: yes.
- **Kind:** needs a person (if it repeats).
- **User sees:** nothing directly; the document stays as it was.
- **IT admin and log:** ERROR with the code, a job reference and the document ID per failure.
- **Fix:** as RED-004.
- **L1 tells the customer:** escalate if Operations keeps showing the same document.

### Sensitivity, approvals, legal holds and duplicates (SEN, APR, HLD)

The released SEN, APR and HLD codes cover these flows (see "Points that already send a released code"). Gaps:

#### SDV-APR-016 (proposed): a request in a bulk decision failed
- **Where:** `AccessRequestService.java:417-418`.
- **Trigger:** a request in a bulk decision fails for a reason that is not a refusal (for example the database).
- **Today:** the item reads `FAILED` with "This request couldn't be decided. Try it on its own." There is no code, and the cause is dropped. Generic: yes.
- **Kind:** authorised but system failed.
- **User sees:** today's text, plus "Ref <id>. (SDV-APR-016)".
- **IT admin and log:** ERROR with the code, the reference, the request ID and the exception class.
- **Fix:** decide it on its own; if that fails, see the cause.
- **L1 tells the customer:** decide that one request alone; pass the reference if it fails.

#### SDV-SEN-020 (proposed): approval couldn't be checked
- **Where:** `DocumentAccessGuard.java:122-125` (a grant that can't be read counts as no grant; then SEN-009).
- **Trigger:** the database fails while reading or using the person's approval.
- **Today:** 403 **SDV-SEN-009**: the person is told they have no approval when they do. Safe (fail closed), but misleading. Generic: no (a released code), but the wrong one.
- **Kind:** authorised but system failed.
- **User sees:** "Vero couldn't check your approval just now. Try again in a minute. Your approval is not used up. (SDV-SEN-020)"
- **IT admin and log:** ERROR with the code, the reference and the document ID.
- **Fix:** restore the database.
- **L1 tells the customer:** a passing database problem; try again.

Duplicates (task 0031) are covered by INT-017 and SEN-003. A duplicate delivery is answered 200 with `duplicate: true` and is not a failure.

### Reports (RPT)

The released RPT codes cover the reports. Gaps: internal checks in the Sensitivity report (`JdbcSensitivityReportRepository.java:77-78`, `:270`, `:291`, `SensitivityReportService.java:156`, `:162`, `:263`) are under SYS-016. The export audit is under SYS-017.

### Admin screens (ADM, IDP, AUD, RET)

#### SDV-ADM-001 (proposed): sending-system settings not valid
- **Where:** `IngestionSourceAdminService.java:83`, `:109`, `:110`, `:133`, `:138`, `:143`, `:144`, `:145`, `:146`, `:147`.
- **Trigger:** a missing or malformed key, name, status, sign-in type or limit.
- **Today:** 400 `BAD_REQUEST`. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-ADM-001)".
- **IT admin and log:** DEBUG.
- **Fix:** none.
- **L1 tells the customer:** correct the field the message names.

#### SDV-ADM-002 (proposed): sending-system key already used
- **Where:** `IngestionSourceAdminService.java:87`.
- **Trigger:** another sending system of the tenant has this key.
- **Today:** 400 `BAD_REQUEST`; it should be 409. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** "Another sending system already uses this key. Choose another. (SDV-ADM-002)"
- **IT admin and log:** DEBUG.
- **Fix:** none.
- **L1 tells the customer:** keys must be unique.

#### SDV-ADM-003 (proposed): no such sending system
- **Where:** `IngestionSourceAdminService.java:72`, `SourceOperationsService.java:22`, `MetadataFieldAdminService.java:271`, `BusinessExperienceService.java:847`.
- **Trigger:** the source ID names no sending system of the tenant.
- **Today:** 404 `NOT_FOUND`. Generic: yes.
- **Kind:** not found.
- **User sees:** "No sending system has this ID. Reload Integrations. (SDV-ADM-003)"
- **IT admin and log:** INFO.
- **Fix:** none.
- **L1 tells the customer:** reload.

#### SDV-ADM-004 (proposed): field settings not valid
- **Where:** `MetadataFieldAdminService.java:99`, `:104`, `:136`, `:138`, `:141`, `:147`, `:149`, `:200`, `:202`, `:204`, `:205`, `:207`, `:237`, `:238`, `:239`, `:240`, `:276`, `:281`, `:282`, `:283`, `:284`, `:285`, `:287`, `:290`. (Identifier changes while records have a level: SDV-SEN-007.)
- **Trigger:** a missing label or setting, a bad key, a wrong order list, record-title limits, or a normalization that doesn't fit the type.
- **Today:** 400 `BAD_REQUEST`. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-ADM-004)".
- **IT admin and log:** DEBUG.
- **Fix:** none.
- **L1 tells the customer:** correct the setting the message names.

#### SDV-ADM-005 (proposed): field not defined yet
- **Where:** `MetadataFieldAdminService.java:211`.
- **Trigger:** a Business Admin changes a field IT hasn't defined.
- **Today:** 404 `NOT_FOUND`, with a good message. Generic: yes.
- **Kind:** needs a person.
- **User sees:** today's message, plus "(SDV-ADM-005)".
- **IT admin and log:** INFO.
- **Fix:** IT defines the field first.
- **L1 tells the customer:** IT must add the field before it can be set up for search.

#### SDV-ADM-006 (proposed): Roles & permissions request malformed
- **Where:** `RoleCapabilityAdminController.java:49`, `:99`; `CapabilityPolicyService.java:73`. (Rule refusals: SDV-ROL-001 to 003.)
- **Trigger:** no `roles`, or a role name Vero doesn't know.
- **Today:** 400 `BAD_REQUEST`, "Unknown role: <name>". Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-ADM-006)".
- **IT admin and log:** DEBUG.
- **Fix:** none.
- **L1 tells the customer:** the screen or script sent a role name Vero doesn't have.

#### SDV-IDP-001 (proposed): sign-in connection draft not valid
- **Where:** `EnterpriseIdentityAdminController.java:28`, `:47`; `JdbcOidcConnectionBindingRepository.java:178`, `:179`.
- **Trigger:** the draft has no body, or no issuer or required value, or the issuer isn't a URI (`URI.create` gives Java's raw message).
- **Today:** 400 `BAD_REQUEST`. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** "The sign-in connection is missing <field> or it isn't a web address. (SDV-IDP-001)"
- **IT admin and log:** DEBUG.
- **Fix:** none.
- **L1 tells the customer:** correct the field.

#### SDV-IDP-002 (proposed): nothing to test or activate
- **Where:** `EnterpriseIdentityAdminController.java:30`, `:34`; `JdbcOidcConnectionBindingRepository.java:66`, `:67`, `:78`.
- **Trigger:** no draft has been saved, or it is in the wrong state.
- **Today:** 400 `BAD_REQUEST`. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-IDP-002)".
- **IT admin and log:** DEBUG.
- **Fix:** none.
- **L1 tells the customer:** save and test a draft first.

#### SDV-IDP-003 (proposed): activation not allowed yet
- **Where:** `EnterpriseIdentityAdminController.java:33`, `:35`; `JdbcOidcConnectionBindingRepository.java:80`, `:83`, `:84`, `:85`.
- **Trigger:** a missing confirmation word, a different draft, no successful test in the last 15 minutes, no active connection, or already active.
- **Today:** 400 or 409, with good messages. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-IDP-003)".
- **IT admin and log:** INFO.
- **Fix:** test again, then activate.
- **L1 tells the customer:** follow the message; the test must be recent.

#### SDV-IDP-004 (proposed): sign-in connection changed meanwhile
- **Where:** `JdbcOidcConnectionBindingRepository.java:52`, `:89`, `:92`, `:110`, `:113`.
- **Trigger:** someone else changed the connection at the same moment.
- **Today:** 409 `CONFLICT`, "Reload and try again". Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-IDP-004)".
- **IT admin and log:** INFO.
- **Fix:** none.
- **L1 tells the customer:** reload and repeat.

#### SDV-IDP-005 (proposed): roll-back not allowed
- **Where:** `EnterpriseIdentityAdminController.java:40`; `JdbcOidcConnectionBindingRepository.java:101`, `:103`, `:105`, `:106`.
- **Trigger:** a missing confirmation word, or the target isn't the previous known-good connection.
- **Today:** 400 or 409. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-IDP-005)".
- **IT admin and log:** INFO.
- **Fix:** none.
- **L1 tells the customer:** only the previous working connection can be restored.

#### SDV-IDP-006 (proposed): connection test failed (outbound)
- **Where:** `OidcDiscoveryTester.java:25`, `:28`, `:31`, `:32`, `:35-43`, `:46` (returned as a failed test, not an error).
- **Trigger:** the issuer's discovery address can't be reached, answers non-200, names another issuer, or lacks an endpoint.
- **Today:** a failed test with a sentence. `:46` adds the **raw exception text** (up to 300 characters), which can include internal host names. Generic: yes (no code; the cause is free text).
- **Kind:** outside dependency down, or needs a person.
- **User sees:** "The test couldn't reach the sign-in service at <host>: <cause in plain words>. (SDV-IDP-006)"
- **IT admin and log:** INFO with the code, the reference, the host and the cause class.
- **Fix:** correct the issuer, or open the network path.
- **L1 tells the customer:** Vero couldn't reach their sign-in service from its server; check the address and the firewall.

#### SDV-IDP-007 (released in task 0038): more than one active sign-in connection
- **Retired in task 0041:** platform-core allows one ACTIVE login connection per customer and product (unique index), and a sign-in that matches more than one entity asks the person to choose (SDV-AUT-010). The code is no longer sent.
- **Released:** now sent (task 0038); its section is above. The notes below are what task 0034 found, kept for the list of lines.
- **Where:** `JdbcOidcConnectionBindingRepository.java:28` (read at every sign-in, from `EnterpriseJwtAuthenticationConverter.java:160`).
- **Trigger:** data changed by hand so that two connections are active for one issuer.
- **Today:** `IllegalStateException`. The sign-in converter catches only `IllegalArgumentException`, so this is likely a 500 at every sign-in, with Spring's body. Generic: yes.
- **Kind:** authorised but system failed.
- **User sees:** "Vero can't sign you in because its sign-in setup is inconsistent. Your IT team has been told. (SDV-IDP-007)"
- **IT admin and log:** ERROR with the code, the tenant and the issuer.
- **Fix:** disable one connection (recovery runbook).
- **L1 tells the customer:** escalate to Vero operations.

#### SDV-AUD-001 (proposed): audit search not valid
- **Where:** `AuditQueryService.java:65`, `:102`, `:106`, `:172`, `:177`, `:193`, `:215`.
- **Trigger:** limit out of range, too many action codes, a bad action code, dates reversed, or text too long.
- **Today:** 400 `BAD_REQUEST`. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-AUD-001)".
- **IT admin and log:** DEBUG.
- **Fix:** none.
- **L1 tells the customer:** change the filters.

#### SDV-AUD-002 (proposed): audit page link not valid
- **Where:** `AuditQueryService.java:229`, `:233`, `:235-236`.
- **Trigger:** the "more" cursor was changed or is from another list.
- **Today:** 400 `BAD_REQUEST` "audit cursor is invalid." Generic: yes.
- **Kind:** reached but refused.
- **User sees:** "This list changed. Start again from the newest events. (SDV-AUD-002)"
- **IT admin and log:** DEBUG.
- **Fix:** none.
- **L1 tells the customer:** reload the audit list.

#### SDV-AUD-003 (proposed): audit export too large
- **Where:** `AuditQueryService.java:109`.
- **Trigger:** more than 10,000 events match.
- **Today:** 422 `EXPORT_TOO_LARGE`, with a good message. Generic: no (own code, not in SDV form).
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-AUD-003)".
- **IT admin and log:** DEBUG.
- **Fix:** none.
- **L1 tells the customer:** export a shorter period.

#### SDV-RET-001 (proposed): retention policy not valid
- **Where:** `RetentionPolicyService.java:59`, `:62`, `:65`.
- **Trigger:** name or description too long, or days not positive.
- **Today:** 400 `BAD_REQUEST`. Generic: yes.
- **Kind:** reached but refused.
- **User sees:** today's message, plus "(SDV-RET-001)".
- **IT admin and log:** DEBUG.
- **Fix:** none.
- **L1 tells the customer:** correct the value.

#### SDV-RET-002 (proposed): a document couldn't be deleted at its time
- **Where:** `RetentionDeletionService.java:69` (counted only), `:83`, `:88`, `:99`, `:103`, `:117-124`; `JdbcRetentionRepository.java:239`. Audited `DOCUMENT_RETENTION_DELETE_FAILED`, with the exception class and **no reference** (`:157`).
- **Trigger:** storage, the database or the state check fails for one document.
- **Today:** WARN with counts only (`RetentionScheduler.java:44`). Generic: yes.
- **Kind:** needs a person (if it repeats).
- **User sees:** nothing; the document stays until the next run.
- **IT admin and log:** ERROR per document with the code, a run reference, the document ID and the cause class.
- **Fix:** as the cause (STO or SYS-014); the next daily run retries.
- **L1 tells the customer:** Vero will try again tomorrow; if Operations keeps showing it, escalate.

#### SDV-RET-003 (proposed): daily cleanup failed
- **Where:** `RetentionScheduler.java:52-55` (WARN "reasonCode=RETENTION_SWEEP_FAILED", **without the cause**).
- **Trigger:** the whole run fails, for example because the database is down.
- **Today:** WARN, and Operations shows that the last run failed. Generic: yes (no cause).
- **Kind:** outside dependency down, or authorised but system failed.
- **User sees:** Operations: "Last cleanup failed at <time>. (SDV-RET-003)"
- **IT admin and log:** ERROR with the code, the run reference, the exception class and the stack trace.
- **Fix:** as the cause; the next run retries.
- **L1 tells the customer:** expired documents are kept until a cleanup succeeds; nothing is lost.

### Screens (front end)

These are the points where the screen turns a failure into words. Fix tasks belong to the UI.

| Where | Today | Problem | Proposal |
|---|---|---|---|
| `api.ts:67`, `:86` | Uses the body's `message`, or `HTTP <status>` when there is none | Spring's own errors (SYS-013, SYS-015, AUT-*) have no `message`, so people can see "HTTP 500" | Map the status to a plain sentence when there is no code; always keep the reference. |
| `CardError.tsx:19`, `:41`, `:58` | "Vero couldn't reach the server just now…" with Ref | Said for **every** failure, including refusals and 403s, so the cause is wrong | Show the coded message when there is a code; use "couldn't reach" only for network errors. |
| `Sensitivity.tsx:39-40` | The server's message, or "Something went wrong. Try again." | An uncoded server message (developer text, "HTTP 500") is shown raw; the fallback is generic | Use `CardError` wording with Ref for uncoded failures. |
| `AuditLog.tsx:100`, `Reports.tsx:113`, `:293`, `Notices.tsx:131` | "The export didn't work. Try again…" with no Ref | No reference to give IT | Add the Ref. |

### Points that already send a released code

| Code | Where |
|---|---|
| SDV-SEN-001, 002, 011 | `SensitivityLevel.java:41` (and its callers) |
| SDV-SEN-003 | `DocumentIntakeService.java:263`, `:537` |
| SDV-SEN-004 | `SubmissionService.java:401` |
| SDV-SEN-005 | `RecordSensitivityGuard.java:16` |
| SDV-SEN-006 | `SubmissionController.java:89` |
| SDV-SEN-007 | `MetadataFieldAdminService.java:74` |
| SDV-SEN-008 | `DocumentAccessGuard.java:97` |
| SDV-SEN-009 | `DocumentAccessGuard.java:128` |
| SDV-SEN-010 | `SensitivityLevelController.java:126` |
| SDV-SEN-012 | `AccessRequestService.java:623` (shared reason check) |
| SDV-SEN-013 | `SensitivityLevelService.java:116`, `:299` |
| SDV-SEN-014 | `SensitivityLevelService.java:146`, `:213` |
| SDV-SEN-015 | `SensitivityLevelService.java:307` |
| SDV-SEN-016 | `SensitivityLevelService.java:112`, `:238`, `:277`, `:286` |
| SDV-SEN-017 | `SensitivityLevelService.java:113`, `:279`, `:326` |
| SDV-SEN-018 | `SensitivityLevelController.java:118` |
| SDV-SEN-019 | `AuditQueryService.java:274` |
| SDV-ROL-001, 002, 003 | `CapabilityPolicyService.java:92`, `:97`, `:103` |
| SDV-ROL-004, 005 | `RoleSeparationGuard.java:42`, `:46-47`, `:59`; answered at `EnterpriseJwtAuthenticationConverter.java:139-140`, `LocalHeaderAuthenticationFilter.java:118-119` |
| SDV-HLD-001 | `LegalHoldController.java:83` |
| SDV-HLD-002 | `LegalHoldService.java:173` |
| SDV-HLD-003 | `LegalHoldService.java:78` |
| SDV-HLD-004, 007 | `LegalHoldService.java:103`, `:130`, `:180`, `:184`, `:186-187` |
| SDV-HLD-005, 006, 008 | `LegalHoldService.java:109`, `:105`, `:132` |
| SDV-APR-001, 015 | `AccessRequestController.java:72` |
| SDV-APR-002 | `AccessRequestService.java:623` |
| SDV-APR-003, 014 | `AccessRequestService.java:150`, `:146` |
| SDV-APR-004 | `AccessRequestService.java:196` |
| SDV-APR-005 | `ApprovalsController.java:92` |
| SDV-APR-006, 008, 009 | `AccessRequestService.java:438`, `:441`, `:445` |
| SDV-APR-007, 013 | `AccessRequestService.java:142`, `:436`, `:629`, `:634`, `:636-637` |
| SDV-APR-010 | `AccessRequestService.java:408`, `:617` |
| SDV-APR-011 | `AccessRequestService.java:492`, `:516` |
| SDV-APR-012 | `AccessRequestService.java:138`, `:600` |
| SDV-INT-001 | `IntakeNoticeController.java:86` |
| SDV-INT-002 | `StewardNoticeService.java:81` |
| SDV-INT-003 | `StewardNoticeService.java:75`, `IntakeNoticeController.java:76-77` |
| SDV-RPT-001 | `ReportsController.java:108` |
| SDV-RPT-002 | `IntakeReportService.java:206`, `SensitivityReportService.java:229` (and the range checks) |
| SDV-RPT-003 | `IntakeReportService.java:193`, `SensitivityReportService.java:216` |
| SDV-RPT-004 | `TenantZone.java:22-23` |
| SDV-RPT-005 | `IntakeReportService.java:222` |
| SDV-RST-001 | `RestrictionsController.java` (`admin()`) |
| SDV-RST-002 | `RestrictionsService.java` (`cursor()`) |

### Points swallowed on purpose

These failures are caught and not passed on. Each is safe as it is, but most leave **no log line**, so nobody can see that they happen.

| Where | What is swallowed | Effect | Proposal |
|---|---|---|---|
| `DocumentAccessGuard.java:192`, `AuthorizationService.java:56`, `AccessRequestService.java:552` | The audit of a refusal | The refusal stands; the audit misses it | WARN with the reference. |
| `IntakeRefusals.java:61`, `:75` | Recording an intake refusal (5xx failures are never recorded, `:61`) | The Daily intake report misses it | WARN; record 5xx failures as `INTAKE_FAILED`. |
| `ControlledDownloadService.java:226`, `CorrectiveRedactionService.java:443`, `RetentionDeletionService.java:158` | The audit of a failure | The failure is not audited | WARN with the reference. |
| `DocumentIntakeService.java:627`, `CorrectiveRedactionService.java:472` | Deleting an unused encrypted file | An orphan file stays on the volume | WARN with the volume and the object key (the key is opaque). |
| `CorrectiveRedactionService.java:465` | Releasing a recovery claim | Recovery waits for the 10-minute lease | DEBUG. |
| `AccessRequestService.java:244`, `:345` | The record name and position for the requests list | Shown without them | DEBUG. |
| `OperationsService.java:52`, `:93`, `:152`, `:170`, `:186`, `:195`, `:222`, `ProblemRecordResolver.java:70`, `StorageVolumeAdminService.java:179`, `StoragePlacementService.java:56` | A health or list section can't be read | The screen shows the section as degraded or unknown | WARN with the section and the reference. |
| `VaultTransitKeyProtectionProvider.java:69`, `:72` | Key-service health check | Operations shows the key service DOWN | DEBUG; the cause class in the detail. |
| `RoleSeparationGuard.java:54` | Recording that a role conflict ended | Retried at the next sign-in | DEBUG. |
| `SessionNamedAuditEventPort.java:56` | The sending system's name for an audit event | Written without the name | DEBUG. |
| `PdfPageCount.java:19` | Counting pages | Page count left empty | DEBUG. |
| `AuditSentences.java:159` | Reading audit details | The sentence is shown without details | DEBUG. |
| `SecurePdfViewerController.java:110` | A malformed viewing ID | Treated as none | none. |
| `CapabilityPolicyService.java:122`, `SdvGroupRoleMapper.java:47` | An unknown capability or group name | Ignored (then refused, or not a role) | none. |
| `LocalFilesystemVolumeProbe.java:65`, `:72`, `:80` | A folder check | The check says "can't write here" | none. |
| `DocumentIntakeService.java:616`, `CorrectiveRedactionService.java:387` | Overflow in a size estimate | The largest value is used | none. |

### Outside dependencies

| Dependency | Used by | Checked at start? | How, and what is missing |
|---|---|---|---|
| PostgreSQL (`SDV_DB_URL`) | Everything | **Yes, once.** | `DatabaseSchemaCheck` connects and reads Liquibase's table. If it fails, Vero serves only health until restarted (SYS-001 to 003). The local profile runs Liquibase instead; a failure stops the start. **Missing:** no re-check after start; readiness doesn't ping the database (SYS-014); `RestrictedStewardMappingWatcher` ignores the check (SYS-012). |
| Vault Transit, the key service (`SDV_VAULT_BASE_URL`) | Upload, finishing, viewer, download, redaction | **Settings only.** | `VaultCryptoConfiguration` checks the address and the token settings. **Not reachability.** With `SDV_KEY_PROVIDER` at its default `disabled`, Vero starts and every document action fails (KEY-001). Operations probes `sys/health` on demand. Not in readiness. |
| Vault token (env or token file, in the secret store) | Key service | **Read once.** | Read at start; empty or unreadable stops the start. Never re-read, so an expired token fails every document action until restart (KEY-003). |
| Storage volumes (local folders) | Upload, finishing, viewer, download, redaction, retention | **No.** | Checked per upload (`StoragePlacementService`), on Operations and Storage, and by the capacity banner. Not in readiness. |
| Sign-in hub: issuer and JWKS (`SDV_AUTH_JWT_*`, Keycloak `lexa-keycloak`) | Every signed-in request | **Settings only.** | `EnterpriseJwtConfiguration` checks the form of the settings (HTTPS, UUID). JWKS is first fetched at the first sign-in (AUT-004). Task 0038: readiness fetches the keys (signInKeys), and a sign-in while they can't be fetched is 503 AUT-004. With `SDV_AUTH_MODE` at its default `disabled`, Vero starts and refuses everyone (SYS-006). |
| Issuer discovery (`/.well-known/openid-configuration`) | Identity settings, Test | No | Only when IT presses Test (IDP-006). |
| Install settings (approvals, retention time, default time zone, recovery) | Start | **Yes.** | Each stops the start when wrong (SYS-007 to 011). |
| Bundled changelog | Schema check | **Yes.** | SYS-004. |
| Watch folder, polling | n/a | n/a | Not in this repository. |

No other outbound calls exist. The only HTTP clients are in `VaultTransitKeyProtectionProvider.java` and `OidcDiscoveryTester.java`, plus Spring's JWKS client. There is no e-mail and there are no webhooks.

### Rules check (task 0034, item 4)

- **Secrets.** No message or log line found holds a password, key or token.
  - Vault messages give the HTTP status only (`VaultTransitKeyProtectionProvider.java:125`).
  - The token file is named, never read out (`VaultCryptoConfiguration.java:47`, `:50`).
  - `SchemaStep.java:66` refuses a password on the command line.
  - Watch points:
    - `DatabaseSchemaCheck.java:53` logs PostgreSQL's message, which can name the database user and host (not the password);
    - `OidcDiscoveryTester.java:46` returns raw exception text to IT (IDP-006).
- **Document content and redacted values.** None found in messages.
  - Metadata and search messages name the field key, never the value (`MetadataValueValidator`, `BusinessExperienceService.java:494-506`).
  - The one producer value repeated is a `sourceDocumentId` (`SubmissionService.java:276`).
  - The redaction region and the redaction reason are never in messages.
- **Sign-in messages.** Vero never sees passwords; the sign-in hub decides whether an account exists. Vero's 401 descriptions don't say whether an account exists. But they show internal set-up to the browser ("No ACTIVE persisted OIDC connection matches…", "no mapped SDV role"). AUT-005 to 007 replace them with coded plain sentences.
- **Reference ID.**
  - `CorrelationIdFilter.java:31-33` gives every response an `X-Correlation-Id` header and puts it in the log pattern. The screens show it as "Ref" (`CardError.tsx:12`).
  - **Gaps:**
    1. The JSON error body never carries it (`FoundationExceptionHandler.java:114`, `SchemaGateFilter.java:43`, Spring's `/error`), so programs that read only the body lose it.
    2. **Vero itself logs almost none of its failures.** The main code has only six WARN or ERROR lines (`DatabaseSchemaCheck.java:36`, `:53`, `RedactionRecoveryScheduler.java:35`, `RetentionScheduler.java:44`, `:54`, `OidcKnownGoodRecovery.java:39`). So for viewer, download, redaction, finishing, storage and key-service failures, the reference matches no log line. Some of them match an audit event; many match nothing.
    3. Scheduled jobs have no reference: the log shows `no-correlation`, and retention failure audits have `correlationId` null (`RetentionDeletionService.java:157`).
    4. Start-up failures have none.
