# Sensitivity role matrix (from the Lexavault project doc `SDV-Sensitivity-and-Quarantine.md`, sections 3.1–3.7, locked 2026-11-20)

The source of truth for the role-matrix tests. ✓ = allowed · ✗ = not allowed · **A** = only with Business Admin approval · **L** = locked: the record is visible, the document can't be opened.

## 3.1 Documents

| Function | Business User | Steward | Restricted Steward | Business Admin | IT Admin | Platform Ops |
|---|---|---|---|---|---|---|
| Search records, see their search fields | ✓ | ✓ | ✓ | ✗ | ✗ | ✗ |
| Open **Standard** documents | ✓ | ✓ | ✓ | ✗ | ✗ | ✗ |
| Open **Confidential** documents | **L** | ✓ | ✓ | ✗ | ✗ | ✗ |
| Open **Highly Confidential** documents | **L** | **L** | ✓ | ✗ | ✗ | ✗ |
| Redact Standard / Confidential | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ |
| Redact Highly Confidential | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ |
| Print / download Standard | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ |
| Print / download Confidential (audited) | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ |
| Print / download Highly Confidential | ✗ | ✗ | **A** | ✗ | ✗ | ✗ |
| Tag and comment | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ |

Levels are **effective levels**: the higher of the document's own level and its record's level.

## 3.2 Sensitivity level (one document or the whole record)

| Function | Business User | Steward | Restricted Steward | Business Admin | IT Admin | Platform Ops |
|---|---|---|---|---|---|---|
| Raise the level (with reason) | ✗ | ✓ | ✓ | ✗ | ✗ | ✗ |
| Request to lower (with reason) | ✗ | ✓ (from Confidential only) | ✓ | ✗ | ✗ | ✗ |
| Approve lowering | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ |

A Steward may raise beyond what they can open (Vero warns first).

## 3.3 Quarantine — next update, not V1

## 3.4 Approvals and legal holds

| Function | Business User | Steward | Restricted Steward | Business Admin | IT Admin | Platform Ops |
|---|---|---|---|---|---|---|
| Request print / download of Highly Confidential | ✗ | ✗ | ✓ | ✗ | ✗ | ✗ |
| Approve or decline requests | ✗ | ✗ | ✗ | ✓ (never own request) | ✗ | ✗ |
| Place and release legal holds | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ |

## 3.5 Administration (existing, unchanged)

| Function | Business User | Steward | Restricted Steward | Business Admin | IT Admin | Platform Ops |
|---|---|---|---|---|---|---|
| Metadata and search settings, retention, business limits, role permissions | ✗ | ✗ | ✗ | ✓ | ✗ | ✗ |
| Intake sources, login and role mappings, storage keys | ✗ | ✗ | ✗ | ✗ | ✓ | ✗ |
| Technical failures (facts only, no content) | ✗ | ✗ | ✗ | ✗ | ✓ | ✓ |

## 3.7 Documents a person can't open

The record and its search fields stay visible; a lock icon next to the record identifier; the document is a locked item showing only its level; opening it is refused (with an `SDV-SEN-…` code) and audited.

## Separation rules

1. Restricted Steward never combined with Business Admin, Tenant IT Admin or Platform Operations Admin.
2. Permission overrides never give viewing rights to admins or approval rights to stewards.
3. Nobody approves their own request.
4. Admin screens show the record's primary identifier, received date and page count; never the internal document ID, titles, file names, other case fields or content.
5. Restricted Steward mapping changes are audited and notify the Business Admin.
6. Restricted Steward is fixed in Roles & permissions in V1 (owner, 2026-11-20). The legacy direct-role claim never gives it.
7. A role conflict is audited when it starts and when it ends; no repeats while it lasts.
