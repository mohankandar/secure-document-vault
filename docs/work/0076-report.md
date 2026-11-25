# Report 0076: keys per customer and platform-crypto (integration D)

Date: 2026-11-24
Branch: `integration/d-crypto` (from `release/vero-r7` after 0075)
Outcome: **DONE**, except the first run on the owner's local Vault (below).

## What was done

1. `BoundStreamCrypto` over `StreamCipher`; `EnvelopeInfo` in the existing columns. Module `sdv-crypto` is gone.
2. **Binding (VSEC-13):** a fixed header of 57 bytes first inside the encrypted content: `VERODOC`, version 1, document or sealed
   original, and the IDs of entity, document and sealed original. Nothing is handed out before it was authenticated and is exactly the
   expected one. Tests: every other document, entity, kind and sealed ID; missing and short headers; every changed byte;
   another customer; the old format; sizes around every boundary; a fixed-bytes test of the header. Sizes and checksum are the
   document's own.
3. **Sealed originals** are written again under their own ID when sealed (read and checked, written, read back and checked, then the
   switch commits, then the old object is deleted). So neither can be put in the other's place.
4. Settings `platform.crypto.*`; `SDV_VAULT_*` and `SDV_KEY_PROVIDER` are gone. Vero's login may use keys only (VSEC-34); setup makes
   them; `customerKeys: required`.
5. Failures: wrong, missing or switched-off key, changed or foreign ciphertext: a failed check. Anything else of the key service:
   503 as before. No retries of Vero's own; the module's time-out (10 s).
6. No reader for the old format. Check scripts use a scratch Vault (`Use-SdvScratchVault.ps1`), never `sdv-vault`.

## Findings

VSEC-13 and VSEC-34 closed; VSEC-12 closed for what Vero does (AppRole renewed by itself, no redirects, time-outs; https is the
installer's setting). **VSEC-21 only in part:** a sealed original can't take the document's place, but an earlier version of the same
document is bound to the same IDs; that needs a version in the header and where it is stored (a column; not approved here).

## Checks

| Check | Result |
|---|---|
| `.Build-SDV-All.ps1` | 43 + 72 + 2 + 691 + 10 = 818 tests, 0 failures, 27 skipped |
| `npm test`, `npm run typecheck` | 81 passed; pass |
| `.Test-SDV-CleanInstall.ps1` | PASS, 190 s |
| `.Test-SDV-SchemaUpgrade.ps1 -Forks 1` | PASS, 646 s (held redactions, sealing and its rollback included) |
| `.Test-SDV-DemoSeed.ps1` | PASS |
| `npm run ui:smoke`, scratch database and scratch key service | 7 passed |
| `.Test-SDV-KeycloakLive.ps1` (a document opened in a real browser) | 20 of 20 |
| 100 pages, 8 rounds each, alternating builds | before: median 11.2 s (10.4 to 32.3); after: median 11.4 s (9.8 to 22.5): 1.4 % slower, limit 10 %. Single rounds were slow on both builds (busy computer) |

Failed on the way and fixed: three assertions about where a sealed original is, a leftover line in the seed check, and the Maven and
script edits around the removed module. The load comparison used sdv-vault for the old build (as its checks always did) and the
scratch key service for the new one.

## Owner's step: the local key service

`Setup-SDV-VaultTransit.ps1` is done. Vero's keys and the new local database are still to be made:

```
docker exec sdv-postgres createdb -U sdv -O sdv sdv_vero
.\Build-SDV-All.ps1
.\Update-SDV-Schema.ps1 update -Url jdbc:postgresql://localhost:5433/sdv_vero -Username sdv
$env:PLATFORM_SETUP_VAULT_ADDR = 'http://127.0.0.1:8200'; $env:PLATFORM_SETUP_VAULT_TRANSIT_MOUNT = 'vero-transit'
$env:PLATFORM_SETUP_VAULT_TOKEN = (Read-Host 'token with platform-crypto-setup')
.\Seed-SDV-Canvas-Demo.ps1 -SetupOnly
.\start-local.cmd
.\Seed-SDV-Canvas-Demo.ps1
```

Also: `docker rm -f vero-vault-scratch vero-vault-checks` when no longer needed (the first failed to start because of a mistake
of mine; the second is the check scripts' key service and comes back by itself).

## Tests whose assertions changed

- `VeroSetupFilesTest`: keys are required; `VeroSetupPlanPostgresTest` runs on a copy with optional keys (no key service there).
- `HeldRedactionPostgresTest`: the sealed original is a new object with its own key; the old one is gone.
- `SwitchedOffCheckTest`: the setting's new name. The crypto module's own tests went with it; `BoundStreamCryptoTest` replaces them.
- Mocks of the crypto port take the binding instead of the entity.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Decisions taken

- The header also holds the entity: a customer's entities now share a key, so the binding keeps them apart.
- Sealing reads and writes inside the switch's transaction, so a hold that comes late is sealed the same way. It takes a few seconds
  longer. When the delete of the old object fails, what is left can't be read: its key reference is gone.
- `Start-SDV-Local.ps1` is unchanged (CLAUDE.md): what it sets for the old key service is ignored, and the local profile finds the owner's
  login file beside the repository. `scripts/sdv-vault-preflight.py` is not used any more; left (deleting files needs asking).
- The local database's default is `sdv_vero`.
