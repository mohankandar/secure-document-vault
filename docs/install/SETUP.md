# Vero install guide: sign-in, entities and sending systems (setup)

Vero uses platform-core's identity tables (`idn_…`) for who may sign in, with which role, in which entity, and for
which systems may send documents. You fill them with **Vero's setup command**, from your licence and a few files you
write. Vero never changes them at startup. This page is for the IT team that installs Vero.

> Licensing is on (task 0075): Vero starts only with your licence, so setup runs before its first start. Runbook, "The licence".

## 1. Before you start

Everything in the runbook's "Before you install", and Vault's part of "Keys for a customer": setup refuses without the customer's
key settings (`SETUP_KEY_SERVICE_SETTINGS_MISSING`; `--no-customer-keys` is refused with `SETUP_CUSTOMER_KEYS_REQUIRED`).

1. The schema step has run for this release (runbook "Update the database"). It creates Vero's tables and, in their
   own runs, platform-core's identity tables (history table `idn_schema_history`) and licensing tables
   (`lic_schema_history`). The erase log's table (`erl_schema_history`, task 0080) is the schema step's too. Setup never creates tables. Since task 0067 (platform-core 0.7.0) the identity tables are
   at version 5 and the licensing tables at version 2; on an older database the schema step adds them, and Vero does
   not start until it has (`IDN_VERSION_OLDER`).
2. You have these files:

| File | From | Holds |
|---|---|---|
| Your licence (`licence.json`) | Your account manager | Signed licence: customer (`TEN-…`), entity slots (`ENT-…`), products |
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
*,,Vero Platform Operations,PLATFORM_OPERATIONS_ADMIN
```

- Vero's entity roles: `BUSINESS_USER`, `DOCUMENT_STEWARD`, `RESTRICTED_STEWARD`, `BUSINESS_ADMIN`, `IT_ADMIN`.
- **Platform-level grants (task 0057).** `PLATFORM_OPERATIONS_ADMIN` is a platform role. It is granted once for the
  whole customer and Vero, on a row with `*` as its `entity_id` (customer + product + group → role): the group's
  members hold it in **every activated entity**, beside the roles their entity grants give there. Give it only to the
  small group that runs Vero for the whole organisation. Setup refuses the whole file when a `*` row names an entity
  role (`SETUP_ENTITY_ROLE_PLATFORM_WIDE`) or an entity row names the platform role (`SETUP_PLATFORM_ROLE_ON_ENTITY`).
  The plan shows `platform grant SDV (every entity): group "…" -> PLATFORM_OPERATIONS_ADMIN`; a `*` row taken out of
  the file is kept unless you run setup with `--remove-unlisted-grants`. Changes are in the grant history (scope
  `PLATFORM`). Needs identity tables at version 4.
- **Separation rules hold whatever the file says, platform-level grants included.** A person whose groups give
  Restricted Steward together with Business Admin, IT Admin or the Platform Operations Admin (from an entity row or a
  `*` row) in one entity gets neither (the other roles stay); the conflict is recorded for the IT Admin and audited
  when it starts and when it ends. Keep such groups apart.
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
.\setup.ps1 plan  -Licence licence.json -Signin signin.json
.\setup.ps1 apply -Licence licence.json -Signin signin.json
```

Linux: `./setup.sh plan --licence licence.json --signin signin.json`.

The licence keys Vero trusts are inside its jars (task 0067). No file, setting or option adds one, and
`SDV_TRUSTED_KEYS_FILE` stops the start. This release has no release keys yet: ask your account manager.

- The wrapper fills in Vero's product (`--product SDV`) and `product-roles.json`; every other option goes to
  platform-core's setup command (its install guide lists them: `--remove-unlisted-grants`, `--replace-connection`,
  `--run-log`, `--config-out`).
- **Customer's keys** (task 0076): `apply` makes them in the key service. Give it Vault's address, mount and a short-lived token
  with the set-up policy in the environment (runbook, "Keys for a customer"). `plan` only says whether they are there.
- Run it **in Vero's working folder**: `apply` writes `platform-tenant.properties` there (or at `--config-out`), and Vero reads it.
- **Check it:** `plan` again: every item `unchanged`, exit code 0. A refusal starts with a `SETUP_…` code and says what to do;
  nothing was changed. `LICENCE_UNKNOWN_KEY_ID`: the licence isn't signed with a key of this build.
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

For example, with the Business Admin's access token in a file only you can read (one line: `header = "Authorization: Bearer …"`;
delete it afterwards):

```sh
curl -i -K token.txt -X POST 'https://<vero>/api/v1/admin/entities/ENT-…/activate' -H 'Content-Type: application/json' -d '{"name": "Acme Mortgage"}'
```

Vero checks the stored licence (read only, with the keys of its own build), makes
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
| `SDV_AUTH_JWT_MACHINE_CLAIM`, `SDV_AUTH_JWT_MACHINE_CLAIM_VALUE` | Task 0062, recommended: the mark your login system puts on a client's own (client-credentials) token, so that only those count as a sending system's. `{client}` in the value is the client ID. Keycloak: `preferred_username` and `service-account-{client}`. Entra ID: `idtyp` and `app`. Without it, Vero still refuses a bound client's token that has claims of a person's sign-in or that name a person (`SDV_AUTH_JWT_PERSON_CLAIMS`, default `email,upn,given_name,family_name,name,uid`). Also: give a sending system's client no browser, device or password sign-in |
| `SDV_AUTH_JWT_MAX_TOKEN_LIFETIME_SECONDS`, `SDV_AUTH_BROWSER_SCOPE` | Task 0065: see the runbook, "Sign-in in the browser" and "All settings" |
| `SDV_OUTBOUND_INSTALLATION` | Task 0057: `on-premises` (default) or `hosted`, for platform-core's outbound rules that the test sign-in uses |
| `SDV_OUTBOUND_ALLOW_INTERNAL_ADDRESSES` | `true` only if your login system is at an internal address (default `false`: the test sign-in refuses internal and cloud-metadata addresses, `OUT_ADDRESS_BLOCKED`) |
| `PLATFORM_OUTBOUND_PROXY_*`, `PLATFORM_OUTBOUND_PRIVATE_CA_FILE` | Your proxy and private certificate authority, if Vero reaches the login system through them (platform-core's outbound settings) |

Gone since this release, with nothing in their place: `SDV_AUTH_GROUP_*`, `SDV_AUTH_JWT_TENANT_ID`,
`SDV_AUTH_JWT_TENANT_CLAIM`, `SDV_AUTH_JWT_BROWSER_CLIENT_ID`, `SDV_AUTH_JWT_PERSISTENT_CONNECTION_ENABLED`,
`SDV_AUTH_LEGACY_DIRECT_ROLE_CLAIM_ENABLED`, `SDV_AUTH_JWT_ROLES_CLAIM`. The login connection, the grants and the client
bindings come only from setup. The IT Admin's Sign-in tab shows the connection in use, read only.

**Test sign-in (task 0057).** The Sign-in tab has one button, **Test sign-in**. It asks platform-core's identity
module to check the connection in use: it fetches the login system's discovery document and signing keys over
platform-core's outbound rules (https only, no redirects, your proxy and private CA if set) and checks that they name
the same issuer; with a company sign-in it also checks the IT Admin's own sign-in token (signature, issuer, Vero's API
audience, expiry, groups claim). The token is checked and forgotten. The test changes nothing: no draft, no
activation, no rollback. A failure shows platform-core's code, for example `IDN_TEST_ISSUER_UNREACHABLE` (with the
outbound reason such as `OUT_TLS_FAILED`), `IDN_TEST_DISCOVERY_MISMATCH`, `IDN_TEST_KEYS_MISSING`,
`IDN_TEST_AUDIENCE`, or `HTTP_NOT_ALLOWED` for an `http` address that is not a login server on this machine under a
development-signed licence. Vero writes one log line with the code, never the token.

A person who may work in more than one entity chooses one after signing in and can switch in the account menu
(audited); requests carry the choice in `X-SDV-Entity-Id`, which Vero checks against the person's grants.

## 6. Grants for the app's database user

The runbook's default privileges already give Vero's app user `SELECT`, `INSERT`, `UPDATE`, `DELETE` on every table
the owner creates, platform-core's `idn_`, `lic_` and `fld_` tables included. platform-core needs no more (on
`idn_grant_history` the table itself refuses changes). If the rights are short, Vero does not start and names the
table (`IDN_PRIVILEGES_MISSING`).

**Two tables have fewer rights.** The schema step itself takes back `DELETE` on `lic_last_seen` and `UPDATE`, `DELETE` on
`lic_clock_reset` from every role but the owner (task 0073), also on a database that is up to date; nothing to do by hand.

**Upgrading to platform-core 0.7.0 (task 0067).** Check your groups: names count **exactly** as your login system sends them (no
trimming), and a groups claim that is one text is one group. The role mapping file must match character by character.

## 7. On a developer computer

`dev\setup\` holds demo files only (customer `TEN-VERO-DEMO-0001`, slots `ENT-VERO-DEMO-0001` and `-0002`), with a
**development** signing key that no release trusts. `.\Seed-SDV-Canvas-Demo.ps1` runs setup with them
and then Vero's demo step, which activates both demo entities. Only a development build
(`.\Build-SDV-All.ps1` without `-Release`) trusts that key. The demo role mapping has one platform-level grant (task 0057):
`*,,PlatformOperations,PLATFORM_OPERATIONS_ADMIN`, for the development login server's group `PlatformOperations`.
