# Business Admin guide

For the person who sets the rules for documents in Vero. You never see a document, its title or its file name: your pages
show the record's identifier, the received date, the page count and the level.

## Who may do what

People get their roles in your company's sign-in system; Vero only reads them. **Business administration › People &
roles** explains each role.

| | Business User | Document Steward | Restricted Steward | Business Admin | IT Admin | Platform Operations |
|---|---|---|---|---|---|---|
| Open Standard documents | yes | yes | yes | no | no | no |
| Open Confidential | no (locked) | yes | yes | no | no | no |
| Open Highly Confidential | no (locked) | no (locked) | yes | no | no | no |
| Print, download | Standard, when you allow it | Standard, Confidential | and Highly Confidential with your approval | no | no | no |
| Redact, tag, comment | when you allow it, what they can open | what they can open | what they can open | no | no | no |
| Approve requests, legal holds, reports | no | no | no | yes | no | no |

**Roles & permissions** turns print, download, tags, comments and permanent redaction on or off for Business Users and
Document Stewards. Everything else is fixed, and Restricted Steward is fixed completely.

**Roles never held together.** If a person's groups give both, Vero withholds the conflicting roles and reports it:
Restricted Steward with any admin role; Business Admin with Document Steward; IT Admin with any role that opens documents.

## Entities

An entity is a part of your organization with its own records, rules and reports. Your installer sets up the places
(slots); a Business Admin of a slot activates and names it once. There is no screen for it yet: your installer does it
with you. A person who works in several entities chooses one after signing in and switches in the account menu (audited).
Nothing is shared between entities.

## Search fields

IT adds the fields a sending system sends. Under **Search fields** you choose which can be searched, how they match
(exact, starts with, contains) and what a record is called.

**The first identifier field in the order shown is the record's identifier on all admin pages. Put the contract, member,
loan or patient number first.** Otherwise approvals, holds and reports name records by something else.

A change to a field applies to new deliveries; documents already stored keep the values they came with.

## Keeping documents

The rule under **Keeping documents** says how long Vero keeps documents: forever, or a number of days, months or years
(at most 36,500 days). Where no rule was set, Vero keeps documents forever.

- A new rule applies only to documents received after it starts. Documents already stored keep the rule they came with.
- A daily cleanup deletes documents whose time is over, for good, and records each in the audit. Legal holds and
  quarantine stop it.

## Sensitivity levels

Standard, Confidential, Highly Confidential. A record can have a level too; **the higher of the two counts**. Stewards
raise a level at once with a reason; lowering needs your approval. A locked document shows only its level; trying to open
it is refused and audited.

**Restrictions** shows everything restricted on one page: documents and records above Standard and the level changes of
the last 30 days. It is read only and links to the pages where things change.

## Approvals

Requests wait under **Approvals**, grouped by record, oldest first. Every Business Admin sees the same list.

| Request | From | When you approve |
|---|---|---|
| Print or download a Highly Confidential document | Restricted Steward | One use within 24 hours (your installer can change it) |
| Lower a document's level | Steward | At once. A note says when the record keeps it at its level anyway |
| Lower a whole record's level | Steward | Every document of the record, at once. Decided on its own |
| Redact a document on legal hold | Whoever may redact it | Vero replaces what people see; the original is sealed (see Legal holds). Decided on its own |
| Release a whole quarantine incident | Steward | Its documents are available again. Decided on its own |

Nobody decides their own request. A request nobody decides expires (7 days unless your installer changed it), and the
requester is told. **Decided recently** lists the last 30 days.

## Legal holds

Under **Legal holds**, enter the record's identifier and write why. Vero says how many documents the record has and holds
all of them; you don't see which. A held document is never deleted by the daily cleanup. People still open and use it as
before. Holds are released one document at a time.

**Redacting a held document needs your approval.** The redacted version then becomes the one people see. The original is
kept sealed: only a Restricted Steward can read it, it can't be printed or downloaded, and you never see it. When you
release the hold, the sealed original is destroyed for good and the audit says so. Releasing a hold also lets the cleanup
delete the document if its time is over.

## Quarantine

When documents may have been delivered wrongly or leaked, a Steward quarantines them: one document, or (a Restricted
Steward) everything that matches a filter. Everyone else is told only "temporarily unavailable". Stewards review, correct
and release; the exposure list says who opened, read, printed or downloaded before. You decide only the release of a whole
incident, when a Steward asks for it, and see its name, counts, levels and dates, not the reason.

## Reports

Counts only: never contents, titles, names or record numbers. Each has a CSV; its export is audited.

| Report | What it counts | Longest period |
|---|---|---|
| Daily intake | Deliveries, documents, refusals by sending system | 31 days |
| Sensitivity | Documents by level, attempts to open restricted documents, requests and decisions | 366 days |
| Storage and retention | Space, documents by level, what the cleanup will delete | now |
| Activity | Openings, prints, downloads by role and level, busiest people, approvals | 92 days |

Redactions of held documents are not in the Sensitivity report; find them in Approvals and the audit.

## The sealed audit report

**Audit report** makes a sealed copy of the audit for a period (whole days in UTC, at most 10,000 events) to hand to an
auditor: a CSV for machines and a PDF for people. It has document IDs and record numbers only. Nobody can change or remove
a generated report in Vero.

**To verify one:** on the same page, choose the CSV file you were given, or enter the fingerprint printed on the PDF. The
fingerprint is worked out on your computer; the file is not sent. Any change to the file makes it fail. "Sealed" means
the file equals what Vero recorded.

## When something is refused

The screen says what happened and ends with a code. The usual ones:

| Code | What to do |
|---|---|
| SDV-APR-006 | Your own request: ask another Business Admin |
| SDV-APR-008 | Already decided or expired: reload |
| SDV-APR-011, SDV-APR-017 | It no longer applies because something changed: decline it |
| SDV-HLD-005 | The document is on hold already |
| SDV-HLD-008 | The hold was released already: reload |
| SDV-AUT-010 | Choose an entity |
| SDV-SYS-018 | Vero is busy: wait a few seconds and try again |

Anything else: give the code and the reference to support. All codes: [error-codes.md](../api/error-codes.md).
