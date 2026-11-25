# 0092: gaps in the install guides

Read on 2026-11-24 as a customer's IT team, for a new Windows and a new Linux server with their own PostgreSQL, Vault and
login system. Read only: no step was run against a server. Severity: **B** blocks the install, **S** causes a support call,
**C** cosmetic. Guides: RB = install runbook, SU = SETUP.md, IT = IT admin guide, BA = Business Admin guide, BK = backup runbook,
EC = error codes, VO = Vault deployment options, PP = platform packages.

## Fixed in this pull request

| # | Where | What was missing | Sev. |
|---|---|---|---|
| 1 | RB | No list of prerequisites for today's version (versions, ports, accounts, certificates, files); only the frozen 1.0.0's | B |
| 2 | RB | No command to start Vero, no service example, no list of what a stopped start means | B |
| 3 | RB "Keys for a customer" | Windows script only: nothing for Linux or a Vault run from elsewhere. Now the same policies and AppRole as `vault` commands | B |
| 4 | RB, SU | Setup's files must be in Vero's working folder; not said, so the first start finds no customer ID | B |
| 5 | RB | Order: "start" came before "setup", but Vero starts only with the licence | B |
| 6 | RB | A company's own certificate authority: Java's trust store (Vault, signing keys) is separate from `PLATFORM_OUTBOUND_PRIVATE_CA_FILE` | B |
| 7 | RB "Two database users" | "Superuser or the owner": the owner can't create roles. No check, and not said that the default privileges must come first | S |
| 8 | RB "Two database users" | PostgreSQL 14 and earlier: `vero_owner` couldn't create tables | S |
| 9 | RB "Keys for a customer" | The set-up token was shown while typed, and stayed in the window. No Linux form, no check, no failures | S |
| 10 | RB | The login file's address counts before `SDV_KEY_SERVICE_ADDRESS`; the service account can't read the file on Windows | S |
| 11 | RB "The licence" | Windows `set` only | S |
| 12 | RB "Sign-in in the browser" | Not said to keep `/actuator/` private, or that the proxy's own size limit applies; no example | S |
| 13 | SU sections 1 and 3 | Key service not named as a prerequisite; no check after `apply`; unknown licence key not named | S |
| 14 | SU section 4 | Entity activation without a command | S |
| 15 | IT "Upgrades" | Not said to replace all parts together; no way back; settings and key service not in the backup | S |
| 16 | BK | Load balancer told to use `/actuator/health`, which stays 200 while Vero can't work; setup files not in the recovery set | S |
| 17 | PP | platform-core version 0.6.1 (it is 1.0.0) | C |
| 18 | EC | Areas `ENT`, `QUA`, `AUD`, `RST`, `FLD`, `LIC` not in the list of areas | C |

## Open

| # | Where | What is missing | Sev. | Suggestion |
|---|---|---|---|---|
| 1 | Licence | A release build trusts no licence key yet, so no customer's setup passes | B | Task 0093, then the owner's key |
| 2 | Package | No script makes a customer's package (jars, scripts, `dist`, `product-roles.json`, checksums); the two package scripts make a source archive and require a file of version 1.0.0. RB speaks of "the application package" | B | A task: `New-SDV-CustomerPackage.ps1` with SHA-256 sums |
| 3 | RB | The frozen 1.0.0 part and the owner's "Remote testers" part are in the customer's runbook; its first half doesn't apply | S | Split: a customer install guide, the 1.0.0 record, the testers' guide (moves text that the main session also edits, so not done here) |
| 4 | BK, VO | BK section 7 and VO still describe settings gone since 0076; VO has only a note on top | S | Rewrite both with 0080 |
| 5 | BK | The erase log isn't there: after a restore, erased documents would come back | S | Task 0080 (in its scope) |
| 6 | BK | No commands: which `pg_dump`, which folders, which Vault snapshot, in which order, how a restore is tested | S | With 0080 |
| 7 | SU section 4 | The first entity needs a Business Admin's access token by hand; a customer has no easy way to get one, and PKCE-only clients make it hard | B for a customer alone | The activation screen, or an activation option in setup |
| 8 | EC | platform-core's codes (`IDN_…`, `LIC_…`, `LICENCE_…`, `CRYPTO_…`, `SETUP_…`, `OUT_…`, `FLD_…`) that IT meets at install aren't in Vero's guides; only those in RB "The first start" are | S | Ship platform-core's code list with the package |
| 9 | RB | No sizing: processors, memory, disk for documents and scratch (only 320 MB for each PDF read at once) | S | Owner: figures from a load test |
| 10 | RB | Windows service: no wrapper chosen or tested | S | Owner decides; then exact steps |
| 11 | RB | The new Linux service file, `vault` commands and trust store steps are from the scripts and code, not run on a server | S | Prove in the clean install test of 0082 or later |
| 12 | RB | More than one Vero process: BK says it is supported; no steps for a second node (shared document folder; a Windows network share is refused, `SDV-STO-008`, a mounted folder on Linux is not) | S | Say what is supported on Windows; add the steps |
| 13 | RB | AppRole secret ID: no end date and no rotation steps (`-NewSecretId` only after moving the file away) | S | Add rotation steps |
| 14 | RB | The log: where it goes, rotation, how long to keep; no file is written by default | S | Add with the service steps |
| 15 | SU | No worked example for one login system from start to end (the client for people, the API audience, the groups claim, a client for a sending system) | S | One page for Okta, Entra ID and Keycloak each |
| 16 | RB | The proxy's password is a reference in a secret store (`platform.outbound.proxy.password-secret`); which store Vero has isn't said | S | Say it, or refuse it |
| 17 | RB, SU, IT | Task numbers ("task 0057") mean nothing to a customer | C | Remove in the split (3) |
| 18 | BA | "Your installer" activates entities; who that is at the customer isn't said | C | With 7 |
| 19 | RB | `secret/data/platform/*` in the `-app` policy isn't explained | C | Say what for, or leave out |

No real host name, password, token or key is in a guide: the addresses are examples (`db01`, `login.example.com`,
`<Vault address>`). The default file name of the owner's computer (`C:\dev\sdv\secrets\…`) stays where the script has it.
