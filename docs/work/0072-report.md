# Report 0072: Integration plan, final

Date: 2026-11-24
Branch: `plan/integration-final` (from `release/vero-r7` at `9f8671b`)
Outcome: DONE. Documents only; nothing was built.

## What was done

1. Read at tag `platform-core-v1.0.0`, through GitHub: the release notes, `switch-over.md`, the guides for identity,
   licensing, crypto, fields, erase log, monitoring and notifications (not the integration or model-governance guides),
   parts of the matching design documents, and the recorded public API. Compared with rc.2: no code changed.
2. Section 13 of the plan rechecked point by point. What differs: a stream isn't bound to one document (Vero does that
   itself); the modules stop a start when the database is away; message identifiers are per event, not per channel; primary
   fields are locked once values exist; usage has no count of documents; intake also stops in two more licence states.
3. The answers in `0069-answers.md` applied (it was on `release/vero-r7` already).
4. Task files `0073` to `0082`. G (integrations) has none: nothing to do. Case fields are three tasks.
5. Four questions left, each with a recommendation (plan section 15).

## No other product's name or behaviour in code: yes

No code. The task files name none.

## Commands run and results

Every platform type imported by Vero's code was looked up in the recorded API: all are there. Only `gh` reads and text.

## Decisions taken

- The task files say "approved when set ready": the design chat approves the schema changes and dependencies by setting
  them ready.
- Order as in the plan, one after another, though B, C and D could swap.
- The schema steps of D and E go to a **new** local database beside `sdv` (answer 2).
- Between 0077 and 0079 nothing is released.
- The report of 0071 got a section on its early merge.

## Open points and questions for the design chat

Plan section 15: a context for streams (7), a start while the database is away (8), the owner's Vault step (9), the seat
rules (10). Also whether a record's fields go with its last document (0080 item 1).
