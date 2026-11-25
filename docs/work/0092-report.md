# Report 0092: Install guides read as a customer's IT would

Date: 2026-11-24
Branch: `docs/install-review` (from `release/vero-r7` at `2b3da8b`)
Outcome: DONE (docs only; no app, container or database was started)

## What was done

- Read the install runbook, SETUP.md, both admin guides, the backup runbook, Vault options, platform packages and the error
  codes against the scripts, `application.yml` and platform-core 1.0.0's guides (at its tag).
- `docs/work/0092-install-gaps.md`: 18 gaps fixed here, 19 open.
- Runbook: new "Before you install" and "The first start"; Vault's part as `vault` commands; checks and failures for the
  database users and the customer's keys; the set-up token is no longer shown while typed. Smaller fixes in the other guides.

## No other product's name or behaviour in code: yes (guard test passed; no code changed)

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o -q -pl sdv-web -am test "-Dtest=ProductNameGuardTest,ErrorCodesDocumentedTest"` | passed (exit 0) |

## Tests: the two above. None added: no code changed.

## Files changed

The runbook, SETUP.md, PLATFORM_PACKAGES.md, IT admin guide, backup runbook, error codes (list of areas), and this task's files.

## Not done, and why

- The new Linux service file, `vault` commands, trust store and `curl` lines come from the scripts and the code. They were
  **not run** on a server (side-session rule): open gap 11.
- Splitting the runbook (open gap 3): the main session edits the same file.

## Questions for the owner

1. Open gap 2: a script that makes the customer's package. Recommended: yes, before the first customer.
2. Open gap 7: activating the first entity needs an access token by hand. Recommended: the activation screen.

## Suggested next step

0093 (release licence keys), which is open gap 1.
