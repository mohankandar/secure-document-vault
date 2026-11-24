# Reading platform-core packages (one-time setup)

Vero's backend uses released platform-core modules (today the identity modules, `platform-identity-core` and
`platform-identity-spring`, through `com.documentplatform.platform:platform-bom`). They are published to GitHub
Packages at `https://maven.pkg.github.com/mohankandar/platform-core`. GitHub Packages needs a token even to read, so
every machine that builds Vero needs this setup once.

## On a developer machine

1. Create a **classic personal access token** on GitHub with the scope **`read:packages` only**. Give it an expiry
   date and note it.
2. Store it in the user environment variable `GITHUB_PACKAGES_TOKEN`. In PowerShell, without the token appearing in
   the command history:

   ```powershell
   $t = Read-Host 'Token' -AsSecureString
   [Environment]::SetEnvironmentVariable('GITHUB_PACKAGES_TOKEN',
       [Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR($t)), 'User')
   ```

   Open a new terminal afterwards. Check it without printing it: `$env:GITHUB_PACKAGES_TOKEN.Length`.
3. Add this server to `~/.m2/settings.xml` (outside every repository). The file holds only the variable's name:

   ```xml
   <settings>
     <servers>
       <server>
         <id>github-platform-core</id>
         <username>YOUR-GITHUB-LOGIN</username>
         <password>${env.GITHUB_PACKAGES_TOKEN}</password>
       </server>
     </servers>
   </settings>
   ```

   The server id must be `github-platform-core`: it is the repository id in `backend/pom.xml`.
4. Build as usual (`.\Build-SDV-All.ps1`). Maven downloads the platform-core modules once into `~/.m2/repository`.

Never put the token in a repository, a script, a log or a chat.

## In CI (GitHub Actions)

`.github/workflows/ci.yml` uses `.github/maven-settings.xml`, which has the same server entry and reads the same
variable. The workflow fills `GITHUB_PACKAGES_TOKEN` from the repository secret **`PLATFORM_PACKAGES_TOKEN`**
(GitHub refuses secret names that start with `GITHUB_`). Set or renew it from the repository folder:

```powershell
gh secret set PLATFORM_PACKAGES_TOKEN
```

`gh` asks for the value; don't pass it on the command line.

## When the token expires

The build stops with `401 Unauthorized` from `maven.pkg.github.com`. Create a new token (step 1) and renew it in
**both** places: the `GITHUB_PACKAGES_TOKEN` variable on each laptop and the `PLATFORM_PACKAGES_TOKEN` repository
secret.

## Which platform-core version

`platform.core.version` in `backend/pom.xml` (since task 0057: `0.6.1`); only released versions
(`platform-core-vX.Y.Z`), never snapshots. Since 0.3.0 the identity modules also bring `platform-outbound` and
`platform-outbound-spring` (the safe outbound rules the test sign-in uses).
