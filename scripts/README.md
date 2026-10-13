# SDV scripts

The repository remains Windows-first for local development and acceptance.

## Day-to-day commands

Build:

```powershell
.\build-all.cmd
```

Start backend/frontend with Vault support:

```powershell
.\Start-SDV-Local.ps1 -WithVault
```

Stop:

```powershell
.\Stop-SDV-Local.ps1
```

R3 UI/static preflight:

```powershell
.\Test-SDV-UI-ADMIN-R2.ps1 -SkipBuild
```

Local demo fixture:

```powershell
.\Seed-SDV-Demo-Records.ps1
```

## Retained regression/release scripts

The root `Test-SDV-*`, Unit 25, and packaging scripts are retained because they are part of the historical V1 acceptance chain and will be used as the basis for the final R3 regression/re-freeze.

They are not disposable historical clutter. Do not remove them until the replacement R3 release gate has been accepted.

Generated logs/evidence belong under ignored runtime/release directories, not in source control.
