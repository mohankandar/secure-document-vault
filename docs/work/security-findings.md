# Security findings: independent review of Vero (task 0061)

Reviewed 2026-11-23 from the code. This is the plain-terms list with what became of each finding (2026-11-24). The full
review is kept outside the repository.

| Critical | High | Medium | Low | Info |
|---|---|---|---|---|
| 0 | 0 | 10 | 17 | 8 |

No way was found to read another entity's data, to sign in without a valid token in OIDC mode, or to open a document
above one's level.

## Medium

| ID | In plain terms | Status |
|---|---|---|
| VSEC-1 | How a sending system's own token is told from a person's | Fixed, PR #34 |
| VSEC-2 | Permanent redaction of a document on legal hold needs no second approval | **Open**: task 0066 |
| VSEC-3 | Reading through the API and the exposure and activity reports | Fixed, PR #36 |
| VSEC-4 | Limits on the work of showing and checking PDFs | Fixed, PR #36 (a load test is in report 0063; drawing in a separate process was not done) |
| VSEC-5 | Size limit on request bodies | Fixed, PR #34 |
| VSEC-6 | The redaction reason in the audit | Fixed, PR #34 |
| VSEC-7 | Where an IT Admin may place storage folders | Fixed, PR #34 |
| VSEC-8 | Local development start limited to this computer and kept out of releases | Fixed, PR #37 |
| VSEC-9 | Local development services: published addresses and passwords | Fixed in the files, PR #37. **Open** on the development computer until the owner's steps in report 0064 |
| VSEC-10 | Library versions with published advisories; none was found reachable | **Open**: Maven is watched since PR #37; the Spring Boot 4.2 move is task 0070 |

## Low

| ID | Topic | Status |
|---|---|---|
| VSEC-11 | Token lifetime and type rules | Open |
| VSEC-12 | Connection to the key service | Open |
| VSEC-13 | Binding of ciphertext | Open |
| VSEC-14 | Strength of the sealed report and audit trail | Open |
| VSEC-15 | Print approval | Open |
| VSEC-16 | Role separation | Fixed, PR #34 |
| VSEC-17 | Quarantine release approval | Open |
| VSEC-18 | Error texts | Fixed, PR #34 |
| VSEC-19 | Request references | Fixed, PR #34 |
| VSEC-20 | PDF checks at intake | Fixed, PR #36, without the malware-scan hook (it waits for platform-core) |
| VSEC-21 | Data under a replaced version | Open |
| VSEC-22 | Browser hardening | Open |
| VSEC-23 | Metadata value limits | Fixed, PR #36 |
| VSEC-24 | CI package token | Fixed, PR #37 |
| VSEC-25 | Release packages | Fixed, PR #37 |
| VSEC-26 | How quickly a removed grant takes effect | Open |
| VSEC-27 | platform-core version | Open: task 0067 |

## Info

VSEC-28 to VSEC-35 are notes on design and installation, without a fix planned here. VSEC-29 (browser sign-in) is task 0065.
