# Report 0102: Security fixes 7: key-service login, support bundle, advisories, erase-log folder (VSEC-205, 207, 206, 209)

Date: 2026-11-27
Branch: fix/security-7-ops
Outcome: DONE (the owner's steps on this computer's real Vault and the GitHub setting are below)

## What was done

1. **VSEC-205: Vero's key-service login.**
   - `Setup-SDV-VaultTransit.ps1` now requires `-VeroAddresses` (CIDRs; there is no safe default, because behind Docker
     Vault sees the network's gateway, not 127.0.0.1).
   - Vero's AppRole gets a secret ID that lasts 90 days (`-SecretIdHours`, 24 to 8760) and 20,000 logins
     (`-SecretIdUses`). Both the login (`secret_id_bound_cidrs`) and its tokens (`token_bound_cidrs`) are bound to those
     addresses.
   - The app policy reads only `secret/data/platform/vero/*`. The Transit paths were already Vero's own
     (`vero-transit/…/customer-*`).
   - A role from before gets a WARNING and is never changed; the script still changes nothing that is already there.
   - Vero (`VaultSecrets`) reads its notification secrets under `secret/platform/vero/<path>`. For an install from
     before, it still finds a secret at the old `secret/platform/<path>` while its login may read it, with one WARN that
     never names the path or the value. Once the policy is narrowed, the old place counts as "not found", never as a
     failure.
   - Runbook, "Keys for a customer": the required addresses, the narrowed policy and role in the command-line version,
     **"Renewing Vero's secret ID"**, and **"Narrowing Vero's login (an install from before task 0102)"**.
2. **VSEC-207: the support bundle.** Before a bundle leaves Vero, `KeyServiceSettingsFilter` takes out of its
   `configuration.properties` every `platform.crypto.*` and `spring.cloud.vault.*` setting and every setting named like a
   role or secret ID. It rewrites the manifest's checksum of that part, and refuses a bundle it can't read. The test puts
   the login in an imported file with a neutral name (`D:\vero\config\login.properties`). It shows that platform-core's
   bundle carries the role ID (P-2, already in `PLATFORM-ISSUES.md`) and that Vero's filtered bundle doesn't, while
   every other setting and part stays and the manifest still checks.
3. **VSEC-206: advisories in CI.** `.github/ci/advisories.mjs` (plain Node, no new package or action) runs in two jobs:
   - The backend job asks OSV for every compile and runtime Maven library Vero ships (`dependency:list`).
   - The frontend job reads `npm audit --omit=dev`.
   - High or Critical fails, unless `.github/ci/advisories-suppressed.json` accepts it with an id, the package, a reason,
     who reviewed it and an end date. An expired entry fails again. The list is empty today.
   - An advisory without a rating counts as High, and an audit that couldn't run fails (both fail closed). The script's
     rules have their own tests (`node --test`, run in CI first).
4. **VSEC-209: the erase-log folder.** The runbook ("The erase log") gives the exact Windows lines (`icacls … /inheritance:r`,
   the service account with full control, Administrators read-only, and how to check) and the Linux lines
   (`install -d -o vero -g vero -m 700`).
5. `security-findings-2.md`: 205, 206, 207 and 209 are fixed.

## Decisions taken

- **Q-6 (where Vero's secrets live), not answered yet; I went on with my recommendation A.** Vero's own subtree
  `secret/platform/vero/`, with the old place as a fallback during the move, because other apps on platform-core may
  share `platform/`. If the answer differs, only `VaultSecrets`, the policy line and the runbook change.
- `-VeroAddresses` is required, not defaulted (fail closed). A default of 127.0.0.1 would refuse every login behind
  Docker; the proof shows the gateway is what Vault sees.
- The use limit (20,000) is sized for 90 days of hourly tokens on several servers. Renewal is due at 90 days anyway.
- Advisories without a new Maven plugin or GitHub action: OSV is the same database the 0096 review used, and the
  workflow pins every action by commit, so a new action would need its own review. The check runs on every pull request
  and push. It does not run on a schedule (that would run every job weekly); Dependabot alerts, once the owner switches
  them on, cover the time between.

## No other product's name or behaviour in code: yes

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o -pl sdv-web -am -Dtest=VeroMonitoringTest,VaultSecretsTest,…` | PASS (VeroMonitoringTest 8, VaultSecretsTest 3) |
| `node --test .github/ci/advisories.test.mjs` | PASS, 4 tests |
| `node .github/ci/advisories.mjs maven backend/*/target/dependency-list.txt` | PASS: 122 libraries, 0 advisories |
| the same on a scratch list with `log4j-core 2.14.1` (and `junit 4.12` in test scope) | FAIL as it should: 3 blocking advisories; test scope ignored |
| `npm audit --omit=dev --json` + the script | PASS: 0 advisories; a failed audit (no report) fails the check |
| Proof of the setup script on a throwaway dev-mode Vault (`vero-vault-scratch-0102`, port 18202; never `sdv-vault`) | PASS, 11 of 11: no `-VeroAddresses` refused, nothing made; a range wider than /8 refused; first run made everything, nothing secret printed; the role has 90 days, 20,000 uses, bound login and tokens; the policy reads `platform/vero/*` only; login from the bound address 200; Vero's own secret 200; another app's secret under `platform/` 403; second run "Nothing was changed"; a role bound to another address can't log in from here; a role from before gets the WARNING and is unchanged |
| `Build-SDV-All.ps1` | PASS, 100 s: backend BUILD SUCCESS (sdv-web 783 tests, 0 failures, 55 skipped), frontend built |

There is no changeset and no screen change, so there is no schema check or tour (speed rules).

**CI on this PR:** the first run failed in the new advisory step. `dependency:list` alone couldn't resolve Vero's own
modules, which CI doesn't install (`Could not resolve dependencies … sdv-domain`). It now runs together with `compile`
in one Maven call, so the reactor supplies them. This was reproduced locally with Vero's snapshots moved out of the local
Maven repository, then put back. The second run passed, with the check in the log: "maven: 122 libraries checked;
0 advisories" and "npm: 10 libraries checked; 0 advisories".

**The scratch container is not removed:** `docker rm vero-vault-scratch-0102` was refused by this machine's permission
check. It is stopped and holds nothing (dev mode, in memory). **Owner:** `docker rm vero-vault-scratch-0102`.

## Tests: counts, and any failures with their output

New: `VeroMonitoringTest.aKeyServiceLoginInANeutrallyNamedFileNeverLeavesInTheBundle`, `.anUnreadableBundleIsRefused`,
`VaultSecretsTest` (3), and `advisories.test.mjs` (4). On the way, the first proof run failed one check: Vault stores a
`/32` token binding as the bare address, so the proof's check was too strict (the role was right). The check was fixed and
nothing in the script changed.

## Files changed

`Setup-SDV-VaultTransit.ps1`, `VaultSecrets` (+ test), `KeyServiceSettingsFilter` (new), `ChecksController`,
`VeroMonitoringTest`, `application.yml` (comment), `.github/ci/advisories.mjs`, `.github/ci/advisories.test.mjs`,
`.github/ci/advisories-suppressed.json`, `.github/workflows/ci.yml`, the runbook, `security-findings-2.md`.

## Commits (hash and message)

See the PR: task file; VSEC-207; VSEC-206 and 209; VSEC-205; this report.

## Not done, and why

- This computer's real Vault is not changed (task rule). The owner's steps are below.

## Questions for the owner

None. These are the owner's steps:

**A. This computer's real Vault (`sdv-vault`), when Vero's AppRole from 0083/0095 is in use.** Vero's calls reach this
Vault from the Docker network's gateway `172.18.0.1`. Run in a PowerShell window, after deploying this change:

```powershell
docker exec -it -e VAULT_ADDR=http://127.0.0.1:8200 sdv-vault sh
```

Then in that shell (the token is asked for and not shown; nothing is printed or stored):

```sh
vault login
vault read auth/approle/role/vero            # must show token_policies [platform-crypto-app]; stop if not found
vault kv list secret/platform                # Vero's secrets: the paths its settings name (for example notifications/)
# For each path <p> that Vero's settings name (the approval link key: notifications/approval-link-key):
vault kv get -field=value secret/platform/<p> | vault kv put secret/platform/vero/<p> value=-
vault policy write platform-crypto-app - <<'HCL'
path "vero-transit/datakey/plaintext/customer-*" { capabilities = ["update"] }
path "vero-transit/decrypt/customer-*" { capabilities = ["update"] }
path "vero-transit/rewrap/customer-*" { capabilities = ["update"] }
path "vero-transit/hmac/customer-*" { capabilities = ["update"] }
path "vero-transit/keys/customer-*" { capabilities = ["read"] }
path "secret/data/platform/vero/*" { capabilities = ["read"] }
HCL
vault write auth/approle/role/vero secret_id_ttl=2160h secret_id_num_uses=20000 secret_id_bound_cidrs=172.18.0.1/32 token_bound_cidrs=172.18.0.1/32
vault list auth/approle/role/vero/secret-id  # note the accessor(s) of the old secret ID
exit
```

Then, back in PowerShell in the repository: move `C:\dev\sdv\secrets\vero-vault-approle.properties` aside, run
`.\Setup-SDV-VaultTransit.ps1 -VeroAddresses 172.18.0.1/32 -NewSecretId`, restart Vero, check health, and destroy the
old accessor (`vault write auth/approle/role/vero/secret-id-accessor/destroy secret_id_accessor=<old accessor>` in the
container shell). If your mount, role or policy names differ from the defaults, use yours.

**B. GitHub's vulnerability alerts** (the owner's repository setting):

```sh
gh api -X PUT repos/mohankandar/secure-document-vault/vulnerability-alerts
gh api -X PUT repos/mohankandar/secure-document-vault/automated-security-fixes
```

**C.** `docker rm vero-vault-scratch-0102` (the stopped scratch Vault of this task's proof).

## Suggested next step

The open owner steps above; then the next ready item in the queue.
