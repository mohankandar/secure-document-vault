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

**Changing the sign-in** is the installer's, with the setup command (SETUP.md). Since platform-core 1.2.0 a new login
connection replaces the one in use only after a test sign-in **with a token** of the new one (`IDN_TEST_SIGN_IN_REQUIRED`
otherwise); people keep signing in through the one in use meanwhile. To go back to the previous connection, the installer runs
`setup identity rollback-login-connection` (`SETUP_NO_PREVIOUS_CONNECTION` when there is none).

- People's roles come from their groups. **How long a removed right keeps working:**
  - removed from a group in the login system: until the person's token ends, at most 2 hours (the life your login system
    gives it; 15 to 60 minutes advised). Ending the person's session there stops new tokens, not the one in use.
  - a changed role mapping or grant in Vero: up to 30 seconds (Vero remembers grants that long).
  - a person's own removed group therefore ends with their token; keep the token's life short if that matters to you.
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
- A metadata value is at most 512 characters; a longer one is refused (`SDV-INT-009`). Dates with a time carry their offset from UTC.
- Names, addresses and other sensitive fields are stored encrypted and found by their whole value only. They need the key service: without it lists go on without names, and deliveries with such a field are refused (`SDV-KEY-002`).

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
again by themselves. A sending system that asks more often than `SDV_INTAKE_REQUESTS_PER_MINUTE` gets it too. If it lasts, see the runbook (`SDV_DB_POOL_MAX`, `SDV_PDF_*`). Under heavy PDF work a readiness answer
can take a few seconds: give probes a time-out of 10 seconds or more.

System information (`/api/v1/system/info`) needs sign-in; only IT Admin and Platform Operations see profiles and the
database's state there. Without sign-in, use readiness and the start-up log.

Requests are limited in size (`413 SDV-SYS-019`); uploads have the sending system's own limit.

**Paused by the erase log:** readiness is `503 SDV-ERS-001` with the log's code, and nothing but health answers. Usually the database was
restored: backup runbook, "Restore". On Health, "erase-log" shows an erasure that can't be finished (IDs and a code only).

## The licence

**About** shows the licence, this entity's use this month and **Export usage**. New documents stop only 30 days after the licence
ended (`SDV-LIC-001` to sending systems); nothing else stops. Renewal and clock reset: the runbook, "The licence".

## Notifications

**IT › Notifications** connects the channels and chooses the technical notifications for the whole customer.

- **Channels** come from the install config (runbook, "Notifications"): the mail server and each Teams or Slack channel, each
  secret by the path where the key service keeps it. This page shows those paths and each channel's health (working, having
  problems, down, not used yet, with a code and its reason), never a secret. Give the **address people open Vero on** and
  save: messages link under it, and nothing is sent before it is saved.
- **Technical notifications:** documents that could not be stored, deliveries still missing documents, storage nearly full
  or full, unidentified intake attempts, the key service not reachable, and the licence's technical events. For each: on or
  off, how often (Immediately, every hour, every 4 hours, once a day at a time), by e-mail and to which channels. Ones that
  say something is down are always on, Immediately or every hour. A problem that lasts is told once it has lasted 15 minutes
  (5 for the key service), again every 4 hours while it lasts, and "Recovered" once it is gone.
- **IT Admins who get e-mail:** the list for the whole customer (user ID and address). Addresses are kept here only, never
  in the audit or a log; the audit says who changed the list and whose entry.
- **The customer's time zone:** every entity without its own follows it, for daily summaries, the times in messages and the
  reports' days.

A message says the event, counts, times and a link into Vero. It never says a title, a name, a file name, a reason or a
document's level. Teams and Slack messages never name a record.

## Checks and the diagnostic bundle

**Problems** lists what Vero depends on: database, key service, login system and its keys, licence, storage, intake. A check
that isn't working says why and what to do, with a code. **Download diagnostic bundle** saves a zip for support; it holds no
document, name, value or secret, and the download is in the audit.

A start while the database can't be reached stops again and the log says why; set the service to restart by itself.

## Problems

Documents that could not be stored, deliveries still missing documents, and requests to the intake addresses Vero couldn't
link to a sending system (last 7 days). Each has a reference: the same one is in Vero's log. References (`R-` and 12 characters) are always Vero's
own; a caller's `X-Correlation-Id` is logged beside it.

The **Audit report** tab makes a sealed copy of the audit, as for the Business Admin.

## Platform packages

Building Vero needs a read-only token for the platform packages: [PLATFORM_PACKAGES.md](../install/PLATFORM_PACKAGES.md).
Running it does not.

## Upgrades

1. Read the release's notes and the settings in the [runbook](../operations/SDV_V1_GA_CLEAN_INSTALL_RUNBOOK.md).
2. Back up the database, the storage, the key service and the settings together ([backup runbook](../operations/SDV_BACKUP_RESTORE_HA_RUNBOOK.md)).
   Going back means restoring that set: an older Vero refuses a newer database.
3. Stop Vero; your database team runs the **schema step** (`status`, `sql`, `update`).
4. Replace the application jar, the setup jar and the screens together. Start Vero; readiness turns 200. Run setup again when the release says so.

Before this version in particular:
- **platform-core 1.3.0** (task 0099): the schema step adds identity V6 and fields V3. **Stop every instance first**: a Vero of
  the earlier version does not start on them, and this one does not start without them. Teams and Slack now go through the
  outbound settings (runbook, "Notifications").
- **Time zones are identity's** (task 0081): `SDV_DEFAULT_TIME_ZONE` is gone. Before the upgrade, set the customer's zone
  to the value it had (IT › Notifications, or the setup file's `timeZone`); an entity's own zone moves by itself. The schema step adds the
  notifications tables (runbook, "Notifications").
- **`SDV_ERASE_LOG_FOLDER` is required:** an absolute folder outside the documents and the database's storage, backed up on its
  own (runbook, "The erase log"). After a restore, the erase replay comes before any work (backup runbook, "Restore").
- `SDV_STORAGE_ALLOWED_ROOTS`, if new volumes will be outside `data`. Existing volumes stay usable.
- `SDV_PDF_ACCEPT`, for systems that send protected PDFs or PDFs with active content.
- People who hold Business Admin with Document Steward, or IT Admin with a document role: those roles are now withheld.
- `SDV_AUTH_JWT_MAX_TOKEN_LIFETIME_SECONDS=0` is refused; tokens, also sending systems', live at most 2 hours by default and need
  an issue time. Tokens with `nonce` or `at_hash` are refused.
- Releasing a legal hold needs two Business Admins. Probes that read `/api/v1/system/info` must use readiness instead.
- Screenshots can't be stopped: printed Highly Confidential pages carry the person's name and the time.
- The release jar has no local profile and refuses to start with one.

Licensing is on: see "The licence".
