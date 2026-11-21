# Report 0034: Failure catalog (analysis only)

Date: 2026-11-21
Branch: `docs/failure-catalog` (from `feature/reports-2` at `92ea7da`)
Outcome: DONE. Analysis only: no code was changed. The full catalog is in `docs/api/error-codes.md`, under "Failure catalog (task 0034)".

## What was done

1. **Every failure point in the backend's main code, flow by flow.** That is every `throw`, `catch` and `orElseThrow` (686 throw/catch lines and 98 `orElseThrow`). I also covered:
   - what Spring does before Vero's code runs: security, MVC errors and `/error`;
   - the start-up runners and scheduled jobs;
   - the outbound calls;
   - how the screens turn failures into words.
2. **For each point** the catalog records:
   - where it is (file and line) and its flow;
   - what triggers it;
   - today's answer and code;
   - its kind;
   - a proposed `SDV-<AREA>-<NNN>` code;
   - the message the user sees;
   - the cause for the IT Admin and the log;
   - the fix;
   - what L1 support tells the customer;
   - whether it is generic today.

   Points with the same cause and the same user action share one code, and the block lists all their lines.
3. **Released codes are unchanged** (all 55). New codes continue each area's numbering (SEN-020, ROL-006, APR-016, INT-004 to 018), and 12 new areas are proposed: SYS, AUT, STO, KEY, PRC, DOC, SCH, RED, RET, AUD, ADM and IDP.
4. **Proposed codes use `####` headings.** The build test `ErrorCodesDocumentedTest` requires every `### SDV-…:` heading to be a code the product sends. A proposed code moves up to `###` when a fix task implements it.
5. **The document also has:**
   - a table of points that already send a released code (74 lines);
   - the points swallowed on purpose (37 lines);
   - the outside dependencies, with what start-up checks;
   - the rules check: secrets, content, sign-in messages, reference ID.

**The watch folder and polling intake are not in this repository.** Sending systems deliver only through the API, so the intake flow covers the API only.

## Counts per flow

A point is one file and line. Internal checks from every flow are counted under start-up (SYS-016, SYS-017), so that row is large.

| Flow | Points with a proposed code | Proposed codes | Generic today | Points already coded |
|---|---|---|---|---|
| Start-up, setup, whole server (SYS) | 146 | 17 | 14 | 0 |
| Sign-in (AUT, ROL) | 38 | 10 | 10 | 8 (ROL) |
| Intake API (INT) | 58 | 15 | 13 | 4 (INT) |
| Processing, storage, encryption (PRC, STO, KEY) | 58 | 17 | 16 | 0 |
| Viewing, search, print, download (DOC, SCH) | 93 | 13 | 13 | 0 |
| Corrective redaction (RED) | 56 | 6 | 6 | 0 |
| Sensitivity, approvals, legal holds, duplicates | 2 | 2 | 1 | 56 (SEN 25, APR 20, HLD 11) |
| Reports (RPT) | 0 new (internal checks are under SYS) | 0 | 0 | 7 |
| Admin screens (ADM, IDP, AUD, RET) | 99 | 19 | 18 | 0 |
| Screens (front end) | 10 | n/a | 10 | n/a |
| **Total** | **560 + 37 swallowed** | **99** | **91 codes + 10 screen points** | **74** |

## Every generic one

91 of the 99 proposed codes replace a generic answer today. That means no stable code (`CONFLICT`, `BAD_REQUEST`, `NOT_FOUND`, `FORBIDDEN`, `UNAUTHORIZED`, Spring's body or "HTTP 500"), or one code that covers several causes. The other 8 already have a code of their own, but not in SDV form, or have the wrong released code: SYS-001, SYS-002, SYS-003, INT-014, INT-015, PRC-004, AUD-003 and SEN-020.

- **SYS:**
  - 004 changelog unreadable
  - 005 sign-in settings
  - 006 sign-in switched off
  - 007 key service settings
  - 008 approval settings
  - 009 retention settings
  - 010 default time zone
  - 011 sign-in recovery
  - 012 start-up work needs the database
  - 013 unexpected failure
  - 014 database failed during a request
  - 015 request malformed before Vero
  - 016 internal check failed
  - 017 audit couldn't be written
- **AUT:**
  - 001 not signed in
  - 002 wrong kind of sign-in
  - 003 token not accepted
  - 004 sign-in hub's keys unreachable
  - 005 claims not usable
  - 006 no connection for issuer
  - 007 no Vero role
  - 008 local headers
  - 009 organisation not found or not active
- **ROL:** 006 role doesn't include this action.
- **INT:**
  - 004 no submission
  - 005 sending system unknown or paused
  - 006 submission incomplete
  - 007 document list
  - 008 metadata field
  - 009 metadata value
  - 010 field rules broken
  - 011 submission retry differs
  - 012 upload identity
  - 013 upload headers
  - 016 submission full
  - 017 document retry differs
  - 018 can't finish again
- **PRC:** 001 stored copy missing; 002 copy doesn't match; 003 key service down while finishing; 005 other.
- **STO:**
  - 001 no storage
  - 002 write failed
  - 003 safety check
  - 004 provider not installed
  - 005 volume settings
  - 006 no volume
  - 007 size mismatch at receipt
- **KEY:**
  - 001 encryption not set up
  - 002 key service unreachable
  - 003 token refused
  - 004 decryption failed
  - 005 encryption failed
- **DOC:**
  - 001 no document
  - 002 not available
  - 003 can't read stored copy
  - 004 failed check
  - 005 can't draw
  - 006 viewer request
  - 007 download can't be verified
  - 008 changed while downloading
  - 009 download stopped part-way
  - 010 tag or comment
- **SCH:** 001 criteria; 002 record or link; 003 recent records.
- **RED:**
  - 001 request
  - 002 another correction
  - 003 can't correct now
  - 004 failed safely
  - 005 changed meanwhile
  - 006 recovery job
- **APR:** 016 a bulk item failed.
- **ADM:**
  - 001 sending-system settings
  - 002 key already used
  - 003 no sending system
  - 004 field settings
  - 005 field not defined
  - 006 Roles & permissions request
- **IDP:**
  - 001 draft
  - 002 nothing to test
  - 003 activation
  - 004 changed meanwhile
  - 005 roll-back
  - 006 connection test
  - 007 two active connections
- **AUD:** 001 search; 002 page link.
- **RET:** 001 policy; 002 one document not deleted; 003 daily cleanup failed.
- **Screens:**
  - `api.ts:67`, `:86`: "HTTP <status>";
  - `CardError.tsx:19`, `:41`, `:58`: "couldn't reach the server" for every failure;
  - `Sensitivity.tsx:39-40`: raw server text, or "Something went wrong";
  - `AuditLog.tsx:100`, `Reports.tsx:113`, `:293`, `Notices.tsx:131`: no reference.

## Top risks

1. **Vero logs almost none of its own failures.** The main code has six WARN or ERROR lines in all. Failures in these areas leave no log line, so the "Ref" shown to people often matches nothing:
   - the viewer, download and redaction;
   - finishing a received document;
   - storage and the key service.

   Some match an audit event, which holds only the exception class.
2. **No last-resort error handler.** Some failures reach no handler: storage, Vault and database failures, and Spring's own refusals (a bad UUID, bad JSON, a missing header, a wrong content type). These go out with Spring's body: no code, no message, and the screen shows "HTTP 500". For example, an upload while Vault is down gets a bare 500, and the Daily intake report doesn't count it.
3. **Internal faults are sent as 409 `CONFLICT` with developer text.** About 50 internal checks throw `IllegalStateException`, which the handler maps to 409. That tells the sender to change their request, and intake counts it as a refusal. "No storage space" is also a 409 (STO-001).
4. **A key service outage is misreported.** Finishing records it as a storage integrity failure (`DURABLE_OBJECT_VALIDATION_FAILED`), and the viewer says it "cannot be viewed securely". Two defaults are worse: `SDV_KEY_PROVIDER=disabled` and `SDV_AUTH_MODE=disabled` let Vero start without warning and then fail every document action or every sign-in (KEY-001, SYS-006).
5. **Possible tampering is invisible.** A decryption authentication failure (KEY-004) or a storage safety failure (STO-003, PRC-004) raises no log line and no alert.
6. **A failed Highly Confidential download uses up its one-time approval.** The grant is marked used before decryption starts (`ControlledDownloadService.java:125`). If storage or the key service fails mid-way, the person must ask again (DOC-009). This comes from reading the code; a test should confirm it.
7. **Database at start-up:**
   - Unreachable at start, Vero refuses until restarted, even after the database returns.
   - Readiness doesn't watch the database after start.
   - `RestrictedStewardMappingWatcher` skips the schema check, so with `enterprise-jwt` a missing database likely stops the start with a raw stack trace (SYS-003, SYS-012, SYS-014).
8. **Sign-in answers:**
   - A person's token on an intake address (or the reverse) gets 401, not 403 (AUT-002).
   - "Tenant not found" (404) and "Tenant is not active" (409) are different answers (AUT-009).
   - Two active connections for one issuer likely make every sign-in a 500 (IDP-007).
9. **On screen, every failure reads "Vero couldn't reach the server just now"** (`CardError.tsx:19`), including refusals, so people are told the wrong cause.

## Proposed fix tasks, by flow

Each fix task carries its own tests. The first one unlocks most of the others.

1. **Whole server (SYS): error plumbing.** Backend.
   - a last-resort handler with `{code, message, reference, timestamp}`;
   - the reference in every error body;
   - `DataAccessException` to 503 (SYS-014);
   - Spring's MVC errors to SYS-015;
   - an `InternalCheckFailed` type (500) in place of `IllegalStateException` for internal checks (SYS-016, SYS-017);
   - one ERROR log line with the reference for every 5xx.
2. **Start-up (SYS):**
   - re-check an unreachable database;
   - a database ping in readiness;
   - a schema guard in `RestrictedStewardMappingWatcher`;
   - a WARN when sign-in or encryption is disabled;
   - codes in the start-up failure messages (SYS-001 to 012).
3. **Sign-in (AUT, ROL):**
   - AUT-001 to 009 and ROL-006;
   - 403 for the wrong kind of sign-in;
   - the same answer for an unknown and a suspended tenant;
   - INFO or WARN logs with the reason;
   - the sign-in hub's keys in the health checks.
4. **Intake (INT, STO-001):**
   - INT-004 to 018;
   - record Spring-level and 5xx intake failures for the Daily intake report;
   - "no storage space" as 503.
5. **Processing, storage, keys (PRC, STO, KEY):**
   - split key-service failures from storage failures when finishing;
   - KEY and STO codes with logs;
   - a security log for KEY-004, STO-003 and PRC-004.
   - The UI's `problemWords.ts` maps today's three processing codes, so it changes with this task.
6. **Viewing, print, download (DOC, SCH):**
   - split `VIEWER_UNAVAILABLE` and `DOWNLOAD_UNAVAILABLE` by cause;
   - DOC and SCH codes with logs;
   - DOC-009, after the owner's decision (question 4).
7. **Redaction and retention (RED, RET):** codes, and per-document logs with a run reference.
8. **Admin screens (ADM, IDP, AUD):**
   - codes;
   - no raw exception text from the connection test;
   - a duplicate sending-system key as 409.
9. **Sensitivity and approvals:** APR-016, and SEN-020 so a database failure isn't reported as "no approval".
10. **Screens (UI only):**
    - wording by kind (refusal, not authorised, server failure, network);
    - show the code when there is one;
    - a reference on the export errors;
    - never the raw server text.

## Commands run and results

- `mvn -q -pl sdv-web -am test -Dtest=ErrorCodesDocumentedTest -Dsurefire.failIfNoSpecifiedTests=false` (in `backend`): **passed**, 3 tests, 0 failures. The released codes are still all listed, and the proposed `####` blocks are not read as codes.
- `.\Build-SDV-All.ps1` (full build with tests): **passed** (next section).

## Tests: counts, and any failures with their output

- `ErrorCodesDocumentedTest`: 3 run, 0 failed.
- Full build (`.\Build-SDV-All.ps1`): **BUILD SUCCESS**.
  - Backend: 402 tests, 0 failures, 0 errors, 5 skipped. That is 24 + 50 + 2 + 4 + 322 by module, the same count as report 0033.
  - Frontend production build: passed.

No test was added or changed. This task changes no code.

## Files changed

- `docs/api/error-codes.md`: one sentence added to the introduction, and the failure catalog appended. Released sections are unchanged.
- `docs/work/0034-report.md`: this report.
- `docs/work/0034-task.md`: committed with its report, as the work routine says.

Not touched:
- `Start-SDV-Local.ps1` (local changes kept);
- `docs/work/README.md` (has an uncommitted change that isn't mine; left out of the commit);
- `docs/work/0035-task.md` (untracked; later task).

## Commits (hash and message), or "not committed"

- `2f82fc1` docs: failure catalog from the code, with proposed codes (task 0034)
- A second commit adds this hash to the report.

On `docs/failure-catalog` only; not pushed, not merged.

## Not done, and why

- **No code changed,** as the task says. Four statements come from reading the code, not from running it, and are marked "likely" in the catalog. A fix task should confirm each with a test:
  - SYS-012: the watcher stops the start;
  - AUT-004: how Spring answers when the sign-in hub's keys can't be fetched;
  - IDP-007: two active connections give a 500;
  - DOC-009: a failed download uses up the grant.
- **The front end was read only for how it turns failures into words,** not screen by screen.

## Questions for the owner

1. **New areas and numbering.** Are these area names approved: SYS, AUT, STO, KEY, PRC, DOC, SCH, RED, RET, AUD, ADM and IDP? Or would you rather have fewer areas?
2. **Where proposed codes live.** They are in `error-codes.md` under `####` headings, below the released ones, because the build test allows only sent codes as `###`. Is that right, or should they be in a separate file until they are sent?
3. **Today's own codes.** Should `DATABASE_*`, `PDF_TOO_LARGE`, `INVALID_PDF`, `EXPORT_TOO_LARGE`, `VIEWER_UNAVAILABLE`, `DOWNLOAD_UNAVAILABLE`, `REDACTION_FAILED` and the three processing codes be replaced by SDV codes, or sent alongside them for a while? The OpenAPI file names none of them. The UI's `problemWords.ts` and `auditWords.ts` read some of them.
4. **DOC-009.** When should a Highly Confidential download's one-time approval count as used: when decryption starts (today), or when the last byte is sent? Starting means part of the file may already have left Vero, so today's rule may be intended.
5. **Status changes for senders.** Several fixes change the HTTP status a sending system sees:
   - 409 to 503 for no storage;
   - 409 to 500 for internal checks;
   - 401 to 403 for the wrong kind of sign-in;
   - 404 or 409 to 403 for the tenant.

   Do you approve? Producer systems may branch on these.

## Suggested next step

Fix task 1 (error plumbing: last-resort handler, reference in the body, logs for every 5xx). It is backend only and needs no schema change. Most of the other fix tasks build on it.
