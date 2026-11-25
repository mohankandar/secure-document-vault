# Report 0077: case field definitions (integration E1)

Date: 2026-11-24
Branch: `integration/e1-definitions` (from `release/vero-r7` after 0091)
Outcome: **DONE**, with one difference from item 5: the old tables are still written, as a copy (below).

## What was done

1. Definitions (type, rules, identifier, sensitivity) are in `platform-fields`, one per entity and key. What a sending system adds (its
   label, required, identifier, search settings, normalisation, order, record title) is an extension, `source.<id>`.
2. Search fields, Integrations and Sources use them through the same API. Roles and audit entries are as before. An identifier is
   primary. Type, identifier and sensitivity lock once values exist; both editors say so before saving (`SDV-FLD-002`).
3. Names and addresses are sensitive by default; the Business admin's switch works until data arrives.
4. Export and import of `platform-fields/1` under Search fields: report, then confirm; audited.
5. Intake checks against the definitions. A test changes the old row directly: what intake reads doesn't change.
6. The schema step runs `fld_` (version 2) after identity and licensing. The start checks tables and rights. At start, fields of an older
   database become definitions; a system that can't be taken over is refused and named in the log.

## Checks

| Check | Result |
|---|---|
| `.\Build-SDV-All.ps1` | 43 + 72 + 2 + 700 + 10 = 827 tests, 0 failures, 35 skipped |
| `npm test`, `npm run typecheck` | 82 passed; clean |
| `.\Test-SDV-CleanInstall.ps1` | PASS, 135 s |
| `.\Test-SDV-SchemaUpgrade.ps1 -Forks 1` | PASS, 716 s |
| `npm run ui:smoke`, scratch database and key service | 7 passed |

Failures on the way, all fixed: the schema check failed four times (a real bug: a new field's first lookup was refused; then two mistakes
of the new test: a superuser-only setting, a wrong version number; then the script's count of its tests). The smoke run failed twice on the new step's own text pattern.
The full tour was not run (0079 does).

## Tests whose assertions changed

`SchemaVersionTest` (the third run), `request-shapes.txt` (two new request records), both schema check scripts (the `fld_` run). None was
weakened.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Decisions taken

- **The old rows are still written, in the same transaction as the definition.** Stored values point to them by a foreign key, and the
  reports join them; dropping that isn't approved here. They give the definition's ID and version number, and the reports' SQL still
  reads labels and identifier flags from the copy; intake, search settings and the editors read the definitions. 0078 ends it.
- Sensitive fields get exact search in the definition. The old search mode stays in the extension, so search works as before until 0078.
- A key has one type in an entity: another system's different type is refused (`SDV-FLD-003`), and its first definition without rules
  takes the field's rules.
- Stricter than before: a pattern on a date, number with decimals, UUID or yes/no field, and a length above 512, are refused
  (`SDV-FLD-001`). An old field with such a rule keeps its whole sending system from being taken over (named in the log).
- A structured address can't come in one text, so a system that sends one is refused until 0078.
- Reading a system's fields makes one more query per field. Not measured.
- Local `sdv` is untouched; the schema step on it is the owner's.
