# Report 0083: The owner's Vault step (script and guide)

Date: 2026-11-24
Branch: `chore/vault-step` (from `release/vero-r7` at `bd5c9b0`)
Outcome: DONE. The owner's step itself is **not** done.

## What was done

`Setup-SDV-VaultTransit.ps1` and the runbook section "Keys for a customer". No code, dependency or schema change.
`sdv-vault` and `C:\dev\sdv\secrets` were not touched.

**The owner's command**, once, before task 0076, with Vault unsealed:

```
cd C:\dev\sdv\secure-document-vault
.\Setup-SDV-VaultTransit.ps1
```

It asks for a Vault token that may add mounts, policies and an AppRole.

## No other product's name or behaviour in code: yes

## Commands run and results

On a scratch Vault only (container `sdv-vault-scratch-0083`, dev mode, port 18200 on this computer; **still there**, below):

| Step | Result |
|---|---|
| Vault not reachable; wrong token; file in the repository | REFUSED, nothing changed, exit 1 |
| First run | seven things added, exit 0; nothing secret printed |
| Second run | all seven there, "Nothing was changed", exit 0; still one secret ID |
| A key in the old `transit/` mount | unchanged |
| Login from the file | ok. May take a data key (200); may not make or delete a key or read the old mount (403) |
| Setup policy | makes a key (200); may not change its settings or read the old mount (403) |
| File | this Windows user only |
| Role there, file lost | REFUSED; with `-NewSecretId` the file is written again |
| A policy with another text | REFUSED; left as it was |
| Undo (mount) | 204 |
| **Sealed** | REFUSED, exit 1 |

The full build and tests are CI's on this PR (a script and a document changed).

## Tests: counts, and any failures with their output

The first try refused everything: the script read the status after closing the answer. Fixed; the table is from the run after
it. "Not initialised" was not tried (a dev Vault always is); it refuses on Vault's status 501.

The session's permission check refused my first, combined command (branch, queue and proof in one). Nothing of it ran; I ran
the same proof in separate, visible steps, which were allowed.

## Decisions taken

- `vero-transit` for the mount, `platform-crypto-…` for the policies (platform-core's names), role `vero`.
- The file is a properties file with `platform.crypto.vault.*`, so that 0076 can import it. It is never overwritten or opened.
- The script refuses plain http except to this computer, and a file inside the repository.
- The role's token: 1 hour, at most 4; only the app policy. The secret ID doesn't expire; a customer's IT may want it to.
- The third policy (`-offboard`) is made too, as the plan names it; no login gets it.

## Not done, and why

**The scratch container is not removed:** the session's permission check refused `docker rm`, and it was not worked around. It
is sealed, in memory only and holds nothing of value. The owner removes it with

```
docker rm -f sdv-vault-scratch-0083
```

Two files with the scratch Vault's login are left in the session's temporary folder (`scratch-approle.properties` and
`….moved`); they open nothing but that container.

## Open points and questions for the design chat

0076 needs `secret/` (KV version 2) only when notifications come (0081). The script doesn't add it.
