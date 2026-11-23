# Vero install guide: sign-in, entities and sending systems (setup)

Vero uses platform-core's identity tables (`idn_…`) for who may sign in, with which role, in which entity, and for
which systems may send documents. You fill them with **Vero's setup command**, from your licence and a few files you
write. Vero never changes them at startup. This page is for the IT team that installs Vero.

> **Until the app turns licensing on, the licensing tables and the stored licence exist, but nothing checks the
> allowance. Licensing is not live.**

## 1. Before you start

1. The schema step has run for this release (runbook "Update the database"). It creates Vero's tables and, in their
   own runs, platform-core's identity tables (history table `idn_schema_history`) and licensing tables
   (`lic_schema_history`). Setup never creates tables.
2. You have these files:

| File | From | Holds |
|---|---|---|
| Your licence (`licence.json`) | Your account manager | Signed licence: customer (`TEN-…`), entity slots (`ENT-…`), products |
| `trusted-keys.json` | The Vero release | The licence signing keys. Not in this release yet: ask your account manager for it |
| `product-roles.json` | The Vero release | Vero's roles (product `SDV`). Shipped; you don't edit it |
| Sign-in file (`signin.json`) | You write it | Your login system, Vero's browser client and API audience, the sending systems |
| Role mapping file (`role-mapping-SDV.csv`) | You write it | Which group of your login system gets which Vero role in which entity |
| Client-binding file (`client-bindings-SDV.csv`) | You write it, if systems send to Vero | Which client may send to which entity, as which sending system |

3. Database settings, in the environment only, for this run only: `PLATFORM_SETUP_DB_URL`
   (`jdbc:postgresql://dbhost:5432/vero`), `PLATFORM_SETUP_DB_USER`, `PLATFORM_SETUP_DB_PASSWORD`. Vero's everyday
   database user is enough.

## 2. The files you write

**Sign-in file** (`platform-signin/1`, no secrets):

```json
{
  "format": "platform-signin/1",
  "tenantId": "TEN-…",
  "mode": "direct",
  "loginSystem": { "type": "OIDC", "issuer": "https://login.example.com/oauth2/default", "providerId": "okta" },
  "products": {
    "SDV": {
      "browserClientId": "…",
      "apiAudience": "api://vero",
      "roleMappingFile": "role-mapping-SDV.csv",
      "clientBindingsFile": "client-bindings-SDV.csv",
      "sources": [ { "key": "loan-system", "name": "Loan servicing system" } ]
    }
  },
  "timeZone": "America/New_York"
}
```

- `issuer` and `apiAudience` must be the values Vero checks (`SDV_AUTH_JWT_ISSUER`, `SDV_AUTH_JWT_AUDIENCE`), and
  `providerId` the value of `SDV_AUTH_JWT_PROVIDER_ID` (default `customer-oidc`).
- `sources`: one entry per sending system. The `key` is lower case (letters, digits, `.`, `_`, `-`). In Vero, the IT
  Admin creates each sending system with the same key (Integrations).

**Role mapping file**: the complete list of grants for Vero.

```
# Format: platform-role-mapping/1
# Product: SDV
entity_id,entity_name,group,role
ENT-…,,Finance Readers,BUSINESS_USER
ENT-…,,Records Stewards,DOCUMENT_STEWARD
ENT-…,,Vero Admins,BUSINESS_ADMIN
ENT-…,,Vero IT,IT_ADMIN
```

- Vero's roles: `BUSINESS_USER`, `DOCUMENT_STEWARD`, `RESTRICTED_STEWARD`, `BUSINESS_ADMIN`, `IT_ADMIN`.
  `PLATFORM_OPERATIONS_ADMIN` is a platform role: no entity grant can give it, so nobody holds it until platform-core
  offers platform-level grants.
- **Separation rules hold whatever the file says.** A person whose groups give Restricted Steward together with
  Business Admin or IT Admin in one entity gets neither (the other roles stay); the conflict is recorded for the IT
  Admin and audited when it starts and when it ends. Keep such groups apart.
- Leave `entity_name` empty: the Business Admin names each entity when activating it.

**Client-binding file**, only if other systems send to Vero (first line exactly as shown):

```
client_id,entity_slot,source,description
0oa1b2c3d4E5f6G7h8i9,ENT-…,loan-system,Loan system for Finance
```

- `client_id`: the sending system's client ID in your login system, copied exactly. Never a secret.
- The binding, not a token claim, tells Vero the entity and the sending system. The sending system's token needs no
  custom claim (sender's guide, `docs/api/README.md`).
- Removing a line switches that client off (kept, never deleted). Moving a client: remove its line and run setup, then
  add the new line and run setup again.

## 3. Running it

Windows (`setup.cmd` works the same):

```
.\setup.ps1 plan  -Licence licence.json -Signin signin.json -Keys trusted-keys.json --no-customer-keys
.\setup.ps1 apply -Licence licence.json -Signin signin.json -Keys trusted-keys.json --no-customer-keys
```

Linux: `./setup.sh plan --licence licence.json --signin signin.json --keys trusted-keys.json --no-customer-keys`.

- The wrapper fills in Vero's product (`--product SDV`) and `product-roles.json`; every other option goes to
  platform-core's setup command (its install guide lists them: `--remove-unlisted-grants`, `--replace-connection`,
  `--run-log`, `--config-out`).
- `--no-customer-keys`: Vero keeps no customer keys yet (`"customerKeys": "optional"`); they come when Vero uses
  platform-crypto.
- `plan` changes nothing and lists every item (`create`, `unchanged`, `switch off`, …). `apply` writes the licence,
  the customer, the entity slots (not activated), the login connection, the grants and the client bindings in one
  transaction. Running it again is safe. Exit codes: 0 done, 1 failed, 2 refused (nothing changed), 3 done except
  items that need a flag.

## 4. Activating entities

Setup stores each entity slot as not activated. A person whose groups give **Business Admin** on a slot activates and
names it (they can sign in before any entity is active):

```
POST /api/v1/admin/entities/ENT-…/activate      {"name": "Acme Mortgage"}
```

Vero checks the stored licence (read only, with the release's trusted keys: setting `SDV_TRUSTED_KEYS_FILE`), makes
its own row for the entity and audits `ENTITY_ACTIVATED`. Refusals: `SDV-ENT-001` (not in the licence, already
activated, name not usable) and `SDV-ENT-002` (not a Business Admin of that slot). The activation screen comes in a
later release.

## 5. Vero's sign-in settings

| Setting | |
|---|---|
| `SDV_AUTH_MODE` | `enterprise-jwt` |
| `SDV_AUTH_JWT_ISSUER`, `SDV_AUTH_JWT_JWK_SET_URI`, `SDV_AUTH_JWT_AUDIENCE` | Your login system and Vero's API audience, as in the sign-in file |
| `SDV_AUTH_JWT_PROVIDER_ID` | The sign-in file's `providerId` (default `customer-oidc`) |
| `SDV_AUTH_JWT_GROUPS_CLAIM` | The claim with the person's groups (default `groups`) |
| `SDV_AUTH_JWT_CLIENT_ID_CLAIMS` | Where a sending system's client ID is (default `azp,client_id,cid`) |
| `SDV_TRUSTED_KEYS_FILE` | The release's `trusted-keys.json`, for activation |

Gone since this release, with nothing in their place: `SDV_AUTH_GROUP_*`, `SDV_AUTH_JWT_TENANT_ID`,
`SDV_AUTH_JWT_TENANT_CLAIM`, `SDV_AUTH_JWT_BROWSER_CLIENT_ID`, `SDV_AUTH_JWT_PERSISTENT_CONNECTION_ENABLED`,
`SDV_AUTH_LEGACY_DIRECT_ROLE_CLAIM_ENABLED`, `SDV_AUTH_JWT_ROLES_CLAIM`. The login connection, the grants and the client
bindings come only from setup. The IT Admin's Sign-in tab shows the connection in use, read only.

A person who may work in more than one entity chooses one after signing in and can switch in the account menu
(audited); requests carry the choice in `X-SDV-Entity-Id`, which Vero checks against the person's grants.

## 6. Grants for the app's database user

The runbook's default privileges already give Vero's app user `SELECT`, `INSERT`, `UPDATE`, `DELETE` on every table
the owner creates, platform-core's `idn_` and `lic_` tables included. platform-core needs no more (on
`idn_grant_history` the table itself refuses changes). If the rights are short, Vero does not start and names the
table (`IDN_PRIVILEGES_MISSING`).

## 7. On a developer computer

`dev\setup\` holds demo files only (customer `TEN-VERO-DEMO-0001`, slots `ENT-VERO-DEMO-0001` and `-0002`), with a
**development** signing key that no release trusts. `.\Seed-SDV-Canvas-Demo.ps1` runs setup with them
(`.\setup.ps1 … -Development`) and then Vero's demo step, which activates both demo entities. The development key file
is never part of a release.
