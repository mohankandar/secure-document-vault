# Report 0098: Activate the first entity from a screen (0092 open gap 7)

Date: 2026-11-27
Branch: feat/first-entity-activation
Outcome: DONE (PR #85, merge 674f0d6; follow-up PR for Q-7's answer: the table)

## What was done

1. **Who gets in.** While no entity of the customer is ACTIVE, a person whose groups give **IT Admin** on a slot now signs in
   without an entity. Before, they were refused with SDV-AUT-007 because only Business Admin slots counted. The
   entity-choice principal carries those `firstStartSlots`; the entity-choice filter lets such a person reach
   `GET /api/v1/first-start` and `POST /api/v1/first-start/activate` (and, as before, the entity list and the Business
   Admin activation).
2. **The endpoint** (`FirstStartController`, `FirstStart`):
   - `GET` gives the slots this person may activate, read fresh from the grants.
   - `POST` (`slotId`, `name`, `licence`: the file's text) runs under one PostgreSQL advisory lock for the whole
     install. It checks that no entity is ACTIVE and that the person is IT Admin of the slot (else SDV-ENT-002).
   - It checks the licence file (else SDV-ENT-001, with the reason's code in the log): it must verify with this build's
     keys as this customer's, be the stored licence (same ID, version not older) and list the slot. Nothing is stored
     from it.
   - It then activates the slot (platform-core `SlotActivation`), makes Vero's row and audits `ENTITY_ACTIVATED` with
     `firstStart: true`.
   - **Once any entity is ACTIVE, both addresses answer exactly as an address with nothing behind it** (404, "nothing is
     at this address"), for everyone.
   - Refused attempts are one WARN line each (who, slot, code; never the file), and since the follow-up PR also a row of
     `first_start_attempts` (changeset 052, answer Q-7). The table is append-only: UPDATE and TRUNCATE are refused, and
     DELETE is refused while no entity is active. At the first activation its rows are copied into the new entity's audit
     as `FIRST_START_REFUSED`, and the table is emptied in the same transaction.
3. **The screen.** With no entity active, the entity chooser asks `GET /api/v1/first-start`. An IT Admin of a slot gets
   **Activate the first entity** (the entity, its name, the licence file, and Activate, which stays off until all three
   are there). A refusal shows the words for its code and the reference. On success the browser chooses the new entity
   and the app starts again in it. Anyone else is told that IT activates the first entity. Screenshot:
   `docs/work/0098-screens/first-start.png`.
4. **Docs.**
   - SETUP.md section 4: the first entity is activated on the screen, and the hand-made token step is gone. Later
     entities: the Business Admin API, as before.
   - Runbook "The first start", IT guide (new "The first start"), business admin guide, and `error-codes.md`
     (ENT-001, ENT-002).
5. **`ui:capture`.** New step "First start: the IT Admin activates the first entity (task 0098)". It shows the screen and
   its button rule, a refused licence with the reference, and the screen gone once an entity is active. The first-start
   state is simulated in the browser (the seed has entities), as other steps do.
6. **A fix found on the way (0102):** `scripts/local/Use-SdvScratchVault.ps1` didn't pass the now-required
   `-VeroAddresses`, so every check that uses the scratch Vault (seed check, tour, owner test with `-ScratchKeyService`)
   stopped at "The scratch Vault could not be prepared". It now passes the container network's gateway. On that scratch
   Vault only, it also makes a pre-0102 role and app policy again; its keys stay.

## Decisions taken

- **Q-7 (what the licence file is for): answered A.** The licence is already stored when the screen can show (Vero
  doesn't start without it, and Vero's licence store is read-only), so the file is a proof of possession besides the
  sign-in. Nothing new writes licences.
- **The audit trail before any entity exists (Q-7, answered: a table, not memory):** PR #85 kept refused attempts in
  memory. The follow-up PR keeps them in `first_start_attempts` (changeset 052), so a restart loses none. "Append-only
  like the audit trail" and "emptied at activation" are both met by refusing DELETE only while no entity is active.
- **Two at once:** a PostgreSQL advisory lock (one key for the install), so it also holds across two Veros of one
  database. The second finds an ACTIVE entity and gets 404.
- **IT Admin, not Business Admin, on the first start** (as the task says). Later entities stay the Business Admin's,
  through the API; they still need a token by hand. That's a follow-up only if wanted.

## No other product's name or behaviour in code: yes

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o -pl sdv-web -am -Dtest=FirstStart*Test,EntityChoiceFilterTest,OidcJwtAuthenticationConverterTest,*BaselineTest` | PASS (FirstStartTest 5, FirstStartControllerTest 3, filter 3, converter 25, baselines) |
| `npm run typecheck`, `npm test` | PASS, 106 tests |
| `Build-SDV-All.ps1` | PASS, 104 s: backend BUILD SUCCESS (sdv-web 794 tests, 0 failures, 56 skipped), frontend built |
| `Test-SDV-DemoSeed.ps1 -WithTour -TourSteps "<first start, role screens, no-access, without a session>"` | First run FAIL in 8 s: the scratch Vault couldn't be prepared (item 6). After the fix: PASS, 352 s, 6 tour steps |

No changeset, so no local schema check. The new `FirstStartLockPostgresTest` (two activations at once run one after the
other across connections) runs in CI's backend job with its own PostgreSQL, and in the schema check, which now lists it.

## Tests: counts, and any failures with their output

New, written first as the task asks:

- `FirstStartTest` (5): an IT Admin activates and it is audited without the file; not IT Admin of the slot (or another
  slot, or not a slot) refused with SDV-ENT-002; every licence problem refused with SDV-ENT-001, with the refusals in
  the audit after; gone for everyone once an entity is active; of two at once only one wins.
- `FirstStartControllerTest` (3).
- `FirstStartLockPostgresTest` (1).
- The converter and filter cases.
- `firstStartWords.test.ts` (2).
- The tour step.

One test of mine failed once on a Mockito stubbing detail and was fixed in the test; no product code changed for it.

## Files changed

Backend: `EntityChoicePrincipal`, `OidcJwtAuthenticationConverter` (lookup and choice), `EntityChoiceFilter`,
`PlatformIdentityReads` (`anyEntityActive`), `PlatformIdentityConfiguration`, `FirstStart`, `FirstStartController` (new),
and tests. Frontend: `EntityChoice.tsx`, `firstStartWords.ts` (+ test), `liveApi.ts`, the tour. Scripts:
`Use-SdvScratchVault.ps1`, `Test-SDV-SchemaUpgrade.ps1` (lists the new Postgres test). Docs: SETUP.md, the runbook, the
IT and business admin guides, `error-codes.md`.

## Commits (hash and message)

See the PR.

## Not done, and why

- A screen for later entities (Business Admin): not in this task.

## Questions for the owner

None.

## Suggested next step

0103 (release candidate), as the queue says.

## Follow-up (answer Q-7): checks

| Command | Result |
|---|---|
| `Build-SDV-All.ps1` | PASS, 128 s (sdv-web 795 tests, 0 failures) |
| `Test-SDV-SchemaUpgrade.ps1 -Forks 1` (`-From 052`) | PASS, 896 s (over 5 minutes: the Postgres tests, as before), incl. "4zd. After 052" and `FirstStartLockPostgresTest` (2) |
| `Test-SDV-CleanInstall.ps1` | PASS, 177 s |
