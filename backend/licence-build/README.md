# Licence keys of the build (task 0067)

platform-core trusts licence keys only from the product's own build: `META-INF/platform/licence-keys.json` and
`licence-build.properties` inside the jar. No file, setting or option of an installation can add a key.

| Folder | Used by |
|---|---|
| `release/META-INF/platform/` | Every build (the Maven default). Empty until Vero has its release licence keys: such a build trusts no licence, so setup and entity activation refuse. The owner fills it with `New-SDV-ReleaseLicenceKey.ps1` (runbook, "Release licence keys"): public keys only, no development key and no `development-keys` |
| `development/META-INF/platform/` | Only a development build: `mvn -Dlicence.build=development` (`Build-SDV-All.ps1` does it unless `-Release`). Trusts the demo licence in `dev/setup`. Never given to a customer; Vero warns at start |
