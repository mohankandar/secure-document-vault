# Report 0088: Keycloak set-up and the real browser sign-in check

Date: 2026-11-24
Branch: `chore/keycloak-check` (from `release/vero-r7`)
Outcome: **STOPPED: owner.** Nothing was built, and Keycloak was not touched.

## What happened

The task needs the `dev-automation` client's secret from `C:\dev\lexavault\local-secrets\`. The session's permission check
refused even listing the names of the files there. As the task says, it was not worked around; no file was opened.

## The owner's step, one of these

1. Allow it: in Claude Code's permission settings, allow reading the one file in `C:\dev\lexavault\local-secrets\` that holds the
   `dev-automation` secret (name it in the task), and running the two scripts of this task. Then set 0088 to `ready` again.
2. Or put the secret into an environment variable of the session yourself before it starts (for example
   `SDV_DEV_KEYCLOAK_SECRET`); the scripts would read only that, and the session never sees the file. Recommended: the smaller
   permission. Then change the task to say so.

Until then the live Keycloak delivery owed by 0065, 0067 and 0070, and any later task that asks for it, stays open.

## No other product's name or behaviour in code: yes

No code.

## Decisions taken

The scripts were not written unproven: they would change a running login system, and their first run would be their first
test.

## Seen on the way

Keycloak answers at `http://localhost:8181` (container port 8080, this computer only), realm `document-platform-local`, as
`dev\setup\signin-vero-demo.json` says.
