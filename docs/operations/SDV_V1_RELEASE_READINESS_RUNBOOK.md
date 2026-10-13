# SDV V1 Release Readiness Runbook

## Frozen result

The V1 release gate is complete.

- Units 0–25: **VERIFIED / FROZEN**
- latest migration: **010**
- coordinated Unit 25 acceptance: **PASS**
- final independent freeze review: **PASS**
- SDV V1: **RELEASE BASELINE / FROZEN**

The historical Unit 25 orchestrator remains in the repository as release evidence/regression tooling.

## GA cut

Generate the final 1.0.0 source distribution from the exact Unit-25-passing workspace:

```powershell
.\New-SDV-V1-GAPackage.ps1 -OutputDirectory .\release
```

The GA cut produces:

- `release\sdv-v1-1.0.0-source.zip`
- `release\sdv-v1-1.0.0-source.zip.sha256`
- `release\sdv-v1-ga-manifest.json`

The packager is pinned to `1.0.0`, verifies the frozen migration ceiling and Unit 21 auth isolation guard, requires the validated frontend lock, applies the frozen security exclusion policy, verifies the completed archive/checksum/manifest, and extracts the archive as an integrity check.

## Required clean-install proof

A GA source cut is not pilot-ready until that **exact archive/hash** passes the clean-install acceptance on a fresh Windows machine or VM.

Follow:

`docs/operations/SDV_V1_GA_CLEAN_INSTALL_RUNBOOK.md`

Do not replace the exact verified archive with a later rebuild unless the new archive repeats checksum and clean-install validation.
