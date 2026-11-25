# Report 0091: a replaced version is bound to its version (VSEC-21)

Date: 2026-11-24
Branch: `fix/version-binding` (from `release/vero-r7` after 0090)
Outcome: **DONE.** VSEC-21 closed.

## What was done

1. Header version 2 inside the ciphertext: the document's version (four bytes) after the kind; 0 for a sealed original. A header of
   version 1, or another version's, is a failed check. Nothing is handed out before.
2. Changeset 047: `object_version` on `document_crypto_metadata`, 1 for what is there. A redaction writes the replacement for the next
   number, and the switch raises the number in the same statement that brings in the new key, only from exactly one less, in the
   switch's transaction. Reading takes the number from the row.

## Checks

| Check | Result |
|---|---|
| `.\Build-SDV-All.ps1` | 43 + 72 + 2 + 693 + 10 = 820 tests, 0 failures, 28 skipped |
| `.\Test-SDV-CleanInstall.ps1` | PASS, 176 s |
| `.\Test-SDV-SchemaUpgrade.ps1 -Forks 1` | PASS (passed in 506 s); one run before failed on a wrong cast in the new test |
| `npm run ui:smoke`, scratch database and key service | 7 passed |

Tests: every version against every other; header 1; the fixed bytes of header 2; all boundaries of 0076 for the new length (61);
on PostgreSQL: the number rises with each redaction, never falls, and an earlier version's ciphertext with its own key put in place
of the current one is refused (`SDV-DOC-004`).

## Tests whose assertions changed

`BoundStreamCryptoTest` (the header's bytes and length), `ChangesetChecksumBaselineTest` (one new line). The schema upgrade check leaves
`document_crypto_metadata` out of its unchanged-tables list and checks its new column instead.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Decisions taken

- **A trigger keeps the number from falling.** Without it, whoever can write the app's tables could put the old number back with the
  old ciphertext and key. The trigger stops the app's database user; the database's owner can still remove it. It is in the one
  approved changeset.
- Documents stored by 0076 builds (header 1; only scratch data so far) don't open any more, as the task says.
- The version in preparation needs no column in the redaction's own table: it is the document's number plus one, and the document can't
  change while a redaction is staged.
