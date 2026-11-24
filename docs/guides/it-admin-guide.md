# IT admin guide

For the customer's IT Admin. You run the technical side; you never see a document, a title, a file name or a person's
case data. **Never screen-share a Highly Confidential document with IT**, and don't ask anyone to: a code and a reference
are enough to help.

An IT Admin never also holds a role that opens documents; Vero withholds it if groups give both.

## Sign-in

Sign-in is set by the installer ([SETUP.md](../install/SETUP.md)): the login system, Vero's API audience, the role
mapping and the sending systems' clients. **IT › Sign-in** shows it read only.

**Test sign-in** checks that Vero reaches the login system and that its keys and issuer fit, and with a company sign-in
also your own token. It changes nothing. A failure shows a code such as `IDN_TEST_ISSUER_UNREACHABLE`.

- People's roles come from their groups. After a group change, a person keeps what their token says until it ends; a
  changed role mapping takes effect within about a minute.
- **In the browser:** people go to your login system and come back. Vero keeps nothing of it in the browser's storage, so a
  reload or a new tab passes through the login system again, usually without a question. How long people stay signed in is
  your login system's setting (its session and token lifetimes); Vero accepts tokens of at most 2 hours.
- Signing out ends Vero in all tabs and goes to the login system's sign-out, when it has one.
- What to set up there, and the headers of the web server: the runbook, "Sign-in in the browser".
- `SDV-AUT-014`: not set up. `SDV-AUT-015`: can't be reached (after the login page: the web origin is missing there).
  `SDV-AUT-016`: the person waited too long or used Back; start again. `SDV-AUT-017`: the login system refused; look in its log.

## Sending systems (Integrations)

Add a system with its key (it can't change later), its fields and its limits. It shows "Waiting for the first delivery"
until something arrives. Pausing it refuses its deliveries; it must send them again later.

- Which client may send for which entity and system is a **client binding**, set by setup, not here.
- A client's own (client-credentials) token is needed: give it no browser, device or password sign-in, and set
  `SDV_AUTH_JWT_MACHINE_CLAIM` ([SETUP.md](../install/SETUP.md) section 5).
- **PDFs Vero refuses** (nothing is stored): unreadable (`SDV-INT-019`), encrypted or with a password (`SDV-INT-020`),
  with scripts, actions or attached files (`SDV-INT-021`), more pages than the limit (`SDV-INT-022`). The installer can
  let one system send the first three kinds with `SDV_PDF_ACCEPT`.
- A metadata value is at most 4,000 characters.

Which field identifies a record is the Business Admin's setting (Search fields).

## Storage

Adding a volume: name and folder, Vero writes a test file there, then In use or Standby. The folder can't change later.

- **Only folders the installer allowed** (`SDV_STORAGE_ALLOWED_ROOTS`; empty: `data` under Vero's working folder). Network
  shares, device paths and links are refused (`SDV-STO-008`), also a folder another volume uses: the answer is the same.
- Documents are encrypted before they are written. Give Vero's service account rights to these folders only.
- When no volume takes documents, deliveries are refused until one does.

## Health, readiness, busy

| Address (no sign-in) | Use |
|---|---|
| `/actuator/health/liveness` | Restart only when this fails |
| `/actuator/health/readiness` | Send traffic only while 200. 503: database version, database, sign-in keys or no free connection |

**Busy** is `503 SDV-SYS-018` with `Retry-After`: no database connection or no place for PDF work just now. Senders try
again by themselves. If it lasts, see the runbook (`SDV_DB_POOL_MAX`, `SDV_PDF_*`). Under heavy PDF work a readiness answer
can take a few seconds: give probes a time-out of 10 seconds or more.

Requests are limited in size (`413 SDV-SYS-019`); uploads have the sending system's own limit.

## Problems

Documents that could not be stored, deliveries still missing documents, and requests to the intake addresses Vero couldn't
link to a sending system (last 7 days). Each has a reference: the same one is in Vero's log. References are always Vero's
own; a caller's `X-Correlation-Id` is logged beside it.

The **Audit report** tab makes a sealed copy of the audit, as for the Business Admin.

## Platform packages

Building Vero needs a read-only token for the platform packages: [PLATFORM_PACKAGES.md](../install/PLATFORM_PACKAGES.md).
Running it does not.

## Upgrades

1. Read the release's notes and the settings in the [runbook](../operations/SDV_V1_GA_CLEAN_INSTALL_RUNBOOK.md).
2. Back up the database and storage, and know your key service's backup.
3. Stop Vero; your database team runs the **schema step** (`status`, `sql`, `update`).
4. Start Vero; readiness turns 200. Run setup again when the release says so.

Before this version in particular:
- `SDV_STORAGE_ALLOWED_ROOTS`, if new volumes will be outside `data`. Existing volumes stay usable.
- `SDV_PDF_ACCEPT`, for systems that send protected PDFs or PDFs with active content.
- People who hold Business Admin with Document Steward, or IT Admin with a document role: those roles are now withheld.
- `SDV_AUTH_JWT_MAX_TOKEN_LIFETIME_SECONDS=0` is refused; tokens, also sending systems', live at most 2 hours by default and need
  an issue time. Tokens with `nonce` or `at_hash` are refused.
- The release jar has no local profile and refuses to start with one.

Licensing is not live.
