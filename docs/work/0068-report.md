# Report 0068: Admin guide, IT guide, install runbook

Date: 2026-11-24
Branch: `docs/guides` (from `release/vero-r7` at `2aeed5f`)
Outcome: DONE. Documents only.

## What was done

`docs/guides/`: `README.md` (index), `business-admin-guide.md`, `it-admin-guide.md`. The runbook: a note saying which part
is current, a table of the settings it lacked, redaction on legal hold, "Licensing is not live", the local jar.

## What was found out of date

- **The runbook's first half** (frozen V1.0.0, migration 010): its package and acceptance scripts don't run on this
  version. Kept as the record, marked as such; not rewritten.
- **Settings missing from the runbook:** `SDV_SERVER_PORT`, `SDV_DB_URL`, `SDV_DB_POOL_MIN`, `SDV_KEY_PROVIDER`,
  `SDV_VAULT_*` (5), `SDV_AUTH_MODE` and seven `SDV_AUTH_JWT_*`, `SDV_TENANT_STATUS_CACHE_SECONDS`,
  `SDV_RETENTION_DAILY_AT`, `SDV_RETENTION_CRON`, `SDV_DEFAULT_TIME_ZONE`, `SDV_APPROVALS_*` (4). Added.
- `docs/design/sensitivity/ROLE-MATRIX.md`: section 3.3 says quarantine is not in V1, and the separation rules lack the
  two from task 0062. Not changed: it names itself the locked source for the role tests.
- There is no entity activation screen and no browser sign-in screen; the guides say so.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` (it also reads guides): result below.

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o test -pl sdv-web -am -Dtest=ProductNameGuardTest` | passed |

Statements were checked against the code by reading it; the screens were not run for this.

## Decisions taken

- The guides describe only what a role can do on screens today; support detail stays in `error-codes.md`.
- A probe time-out of 10 seconds or more is advised, from 0063's load test (slowest readiness answer 5.1 s).

## Questions for the owner

1. Should the runbook's frozen V1.0.0 half move to a file of its own?

## Suggested next step

The next ready item in the queue.
