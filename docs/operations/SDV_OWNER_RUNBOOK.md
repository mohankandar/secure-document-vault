# Vero: the owner's runbook

For the owner only; never in a customer's package (task 0097 moved these sections out of the install runbook).

## Release licence keys

For the owner; a customer never sees this. A release build starts only with a licence signed with a key it trusts (tasks 0073
and 0075). The signing key is made and used by the owner alone, on the owner's computer: **no coding session runs these two
scripts with a real key, opens its folder or sees the key.** Windows PowerShell 5.1, after Vero was built once on this computer.

1. Make a folder outside every repository and outside folders that are copied to the cloud (OneDrive), on an encrypted drive
   (BitLocker): `New-Item -ItemType Directory D:\vero-licence-keys`.
2. Make the key, in the checkout of a new branch from `release/vero-r7`:

   ```powershell
   .\New-SDV-ReleaseLicenceKey.ps1 -KeyFolder D:\vero-licence-keys -KeyId vero-release-2026
   ```

   It writes `vero-release-2026.private-key` (this Windows user only) and `vero-release-2026.public.json` to the folder, and the
   public key to `backend\licence-build\release`. It never replaces a key and prints nothing secret.
3. **Offline copy, at once:** copy the private key file to two encrypted USB sticks (BitLocker To Go), kept in two places, with
   the stick's password kept apart from it. Not by e-mail, not in a chat, a ticket, a cloud folder or a repository. Compare the
   copy: `Get-FileHash` of both files shows the same value.
4. Commit `backend\licence-build\release` (two files, public only) and have it merged. From the next release build on, licences
   of this key are accepted.
5. Sign a licence. Copy `dev\licence\licence-input.example.json` to a folder outside the repository and fill it in: customer,
   entities, dates, storage, people at the same time, named people. A field the script doesn't know, a price for example, refuses the file.

   ```powershell
   .\New-SDV-Licence.ps1 -KeyFolder D:\vero-licence-keys -KeyId vero-release-2026 -InputFile D:\licences\acme-1.json -OutFile D:\licences\acme-1.licence.json
   ```

   `"tenantId": "new"` (version 1) and `"slotId": "new"` make the IDs, which it shows: write them into the input file and keep
   it. A renewal is the same file with the same IDs, the next `version` and new dates. `kind`: `PRODUCTION`, `NON_PRODUCTION`
   (testers: `TEN-VERO-TESTERS-0001` and its two slots) or `INSTALL` (at most 60 days). The licence file isn't secret.

| It says | Do |
|---|---|
| The key is listed, or the file is there, already | Give a new key a new `-KeyId`. Nothing was changed |
| The public key isn't in the release build | Run again with `-PublishOnly` |
| The release build doesn't trust the key | The checkout has no such public key: update it, or `-PublishOnly` |
| The private key isn't the one the release build trusts | Wrong folder or file: take the offline copy |

**Lost key:** make a new one with a new `-KeyId` and ship a release; licences signed before keep working. Never take an old key
out of the release build while a licence signed with it is in use. **Somebody else may have the key:** make a new one, ship a
release without the old one, and sign every customer's licence again, as its next version, before they install that release.

`.\Test-SDV-ReleaseLicenceKey.ps1` checks both scripts with throwaway keys in a temporary folder.


## Remote testers

Task 0089, plan `docs/work/0087-plan.md`. A tester instance on the owner's laptop, reached through a Cloudflare Tunnel by the
listed e-mail addresses only. **Demo documents only:** Cloudflare ends the TLS connection and can see the traffic.

```
Tester's browser -> Cloudflare (Access: listed e-mails) -> tunnel -> Caddy 127.0.0.1:8290
   /  /assets/                      the built screens
   /api/v1/ (never ingestion)       Vero 127.0.0.1:8292
   sign-in of realm vero-testers    tester Keycloak 127.0.0.1:8291
   everything else                  404
```

One web address for all of it, so the login pages are behind Access too. Files: `dev/testers/`. Checks:
`TesterTunnelConfigTest` (every build) and `.\Test-SDV-TestersProxy.ps1` (throwaway Caddy container; run it after any change).

**Rules.** Never local mode: a local start is refused while a tunnel program runs, when `SDV_TRUSTED_PROXIES` is set, or when
it would listen on more than this computer (`SDV-SYS-005`), and a forwarded local sign-in is refused (401). Never install
`cloudflared` as a service: it must stop with the test, and that check may not see a service. Never put Postgres, Vault,
health details or the Keycloak admin behind the tunnel: use `Start-SDV-Testers.ps1 -Tunnel`, never a tunnel file of your own.

### The owner's steps

Do them in this order: the Access policy exists before the address does. Cloudflare may have moved a menu.

1. **Owner only.** Buy a domain (at Cloudflare, or elsewhere with its name servers moved to Cloudflare). Below: `vero.<domain>`.
2. **Owner only.** Cloudflare account: turn on two-step sign-in. Open **Zero Trust**, choose a team name and the free plan
   (check its user limit).
3. **Owner only.** **Zero Trust > Integrations > Identity providers > Add new identity provider > One-time PIN.**
4. **Owner only.** The e-mail list. **Zero Trust > Access controls > Applications > Create new application > Self-hosted and
   private > Add public hostname**: subdomain `vero`, your domain, path empty. Policy: name `Vero testers`, action **Allow**,
   Include **Emails**, each tester's address one by one (no "Emails ending in", no "Everyone"). Login method: One-time PIN only.
   Session duration: 24 hours. **Create.**
5. **Owner only.** One more application the same way: name `Vero sign-in address`, hostname `vero.<domain>`, path
   `realms/vero-testers/.well-known/openid-configuration`, policy action **Bypass**, Include **Everyone**. Vero itself reads
   this one page from the laptop. It lists addresses only, no secret. Never bypass any other path.
6. **Owner only.** In the domain's settings switch off everything that adds scripts: Web Analytics, Rocket Loader, e-mail
   obfuscation. Switch on "Always Use HTTPS".
7. **Owner only.** Install `cloudflared` (Cloudflare's downloads page; name it `cloudflared.exe`, in a folder on PATH) and Caddy
   2.11 (`caddy.exe`, the same). Then, in a PowerShell window:

   ```powershell
   cloudflared tunnel login
   cloudflared tunnel create --credentials-file C:\dev\sdv\secrets\vero-testers-tunnel.json vero-testers
   cloudflared tunnel route dns vero-testers vero.<domain>
   ```

   The first opens the browser: choose the domain. Keep the tunnel ID the second shows; its file is a secret.
8. **Owner only.** `C:\dev\sdv\secrets\vero-testers.env` with three lines, passwords of your own:
   `VERO_TESTERS_HOST=vero.<domain>`, `VERO_TESTERS_KC_DB_PASSWORD=...`, `VERO_TESTERS_KC_ADMIN_PASSWORD=...`
9. **Owner only.** Database `sdv_testers` with owner `vero_testers_owner` and app user `vero_testers_app` ("Two database users"
   above, with these names), in the local server. Say whether a session may run the schema step on it.
10. **Owner only.** The testers' own licence, signed with the development key: customer `TEN-VERO-TESTERS-0001`, slots
    `ENT-VERO-TESTERS-0001` and `-0002`, product SDV. Its own customer gives it its own key; the demo licence would share the
    development one. Keep it at `C:\dev\sdv\testers\licence.json`.

### Settings

Copy `dev\testers\testers.settings.example.psd1` to `C:\dev\sdv\testers\testers.settings.psd1` and fill it in (names and paths
only). `KeySettings`: the key service settings of the build (task 0076), naming the mount `vero-transit` and the **path** of the
login file. The setup command makes the customer's key ("Keys for a customer"). Copy `dev\testers\role-mapping-SDV.csv`,
`client-bindings-SDV.csv` and `signin-vero-testers.template.json` (as `signin.json`, with `__HOST__` filled in) to the same folder.

`.\Start-SDV-Testers.ps1 -CheckOnly` checks the settings and starts nothing.

### Start, accounts, stop

1. Tester Keycloak (its own containers, never the development ones):
   `docker compose --env-file C:\dev\sdv\secrets\vero-testers.env -f dev\testers\compose.testers.yml up -d`.
   The realm is imported at the first start only; after a change to the file, change the realm too.
2. `Build-SDV-All.ps1`; schema step on `sdv_testers`; demo data with the tunnel off; then `setup.ps1 plan` and `apply` with
   the testers' files.
3. **Owner only.** Accounts, one per person. `kcadm.sh` asks for the passwords:

   ```powershell
   docker exec -it vero-testers-keycloak /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user admin
   docker exec -it vero-testers-keycloak /opt/keycloak/bin/kcadm.sh create users -r vero-testers -s username=<name> -s email=<e-mail> -s firstName=<first> -s lastName=<last> -s enabled=true
   docker exec -it vero-testers-keycloak /opt/keycloak/bin/kcadm.sh set-password -r vero-testers --username <name> --temporary
   docker exec -it vero-testers-keycloak /opt/keycloak/bin/kcadm.sh get groups -r vero-testers --fields id,name
   docker exec -it vero-testers-keycloak /opt/keycloak/bin/kcadm.sh get users -r vero-testers -q username=<name> --fields id
   docker exec -it vero-testers-keycloak /opt/keycloak/bin/kcadm.sh update users/<user id>/groups/<group id> -r vero-testers -s realm=vero-testers -s userId=<user id> -s groupId=<group id> -n
   ```

   Groups: `BusinessUsers`, `DocumentStewards`, `RestrictedStewards` (never with an admin group), `BusinessAdmins`,
   `TenantITAdmins`. At the first sign-in the tester changes the password and sets up an authenticator app.
4. `.\Start-SDV-Testers.ps1`, then the local checks of plan 0087 section 7.
5. **Owner only.** `.\Start-SDV-Testers.ps1 -Tunnel`, then the outside checks from a phone on mobile data: a listed e-mail gets
   in, another doesn't; `/actuator/health`, `/admin/`, `/realms/master/`, `/api/v1/ingestion/` answer 404 after the Access
   sign-in; without it, only the sign-in address of the owner's step 5 answers.
6. **Stop:** `.\Stop-SDV-Testers.ps1` (tunnel first; `-TunnelOnly` for just that).
   **Remove a tester:** take the e-mail out of the policy and revoke the Access session, then disable the Keycloak user.

## Local development and checks (moved from the install runbook, task 0097)

- **Local development:** a new database needs `.\Seed-SDV-Canvas-Demo.ps1 -SetupOnly` before Vero's first start.
- **A database of its own for local tests:** a new database `sdv_vero` beside `sdv`, which is kept:
  `docker exec sdv-postgres createdb -U sdv -O sdv sdv_vero`; the schema step on it; the two setup variables above; `.\Seed-SDV-Canvas-Demo.ps1
  -SetupOnly`; start; `.\Seed-SDV-Canvas-Demo.ps1`.
- `Test-SDV-CleanInstall.ps1` checks that Vero runs with the two database users of "Two database users".
- `Test-SDV-CleanInstall.ps1` repeats the whole path against a scratch database `sdv_install_test` inside the local
  `sdv-postgres` server (it never touches the `sdv` database and drops everything it creates): an empty database is
  refused with the "older" message, `status`, `sql` and `update` run, Vero then starts as the read/write user with an
  empty `tenants` table, and a further start applies nothing.
