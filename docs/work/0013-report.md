# Report 0013: A separate schema step for customer installs

Date: 2026-11-18
Branch: `feature/install-1`, taken from `feature/admin-5` (with the 0012 follow-up commit `b8c43ed`)
Outcome: DONE. Three points for the owner to confirm (below); none blocks.

## What was done

**1. Startup no longer migrates outside local mode**
- `application.yml`: `spring.liquibase.enabled: false`. `application-local.yml`: `enabled: true`, context `local`, so local mode is exactly as before (migrations plus the local seed).
- The `SDV_LIQUIBASE_CONTEXTS` override is gone; the context is the literal `production`.

**2. Startup checks the schema instead** (`backend/sdv-web/.../web/schema/`)
- `SchemaVersion`: reads `databasechangelog` with plain SQL and compares it with the changelog bundled in the jar, by changeset id and author. It never writes. Liquibase's own status call is not used, because it creates its tracking tables when they are missing.
  - **Older:** a production changeset hasn't been applied. **Newer:** the database has a changeset this version doesn't ship (this wins if both are true). **Unreachable:** the database couldn't be read.
- `DatabaseSchemaCheck`: runs once while the app starts, before Tomcat takes requests. It logs one plain ERROR line, for example: "The database is older than this Vero version. Ask your database team to run the schema step (install guide, "Update the database"). Vero is serving only its health and system information until it is restarted against a matching database." The missing or unknown changesets follow on an INFO line.
- `SchemaGateFilter`: while the database doesn't match, every request gets 503 `{"code":"DATABASE_OLDER","message":"…"}`, before sign-in is looked at. Only `/actuator/health`, `/actuator/info`, `/api/v1/system/info` and `/error` stay open.
- `DatabaseSchemaHealthIndicator`: `/actuator/health` is DOWN (503).
- `/api/v1/system/info` has a new `databaseSchema {state, message}`; `status` is `DOWN` while it doesn't match.
- **Background work stays off:** the cleanup and redaction-recovery jobs return at once; the OIDC connection bootstrap skips; an explicitly enabled OIDC recovery stops the start instead of being skipped silently.
- **Health page** (`Operations.tsx`): a red notice "The database doesn't match this Vero version" with the same sentence, plus "Vero checks this when it starts. After the schema step, start Vero again." It replaces the generic "couldn't check" notice and hides the cleanup panel, whose "Check now again" advice would be wrong.

**3. The schema step**
- `SchemaStep` (main class in the app jar; Spring is not started): `status`, `sql --output FILE`, `update`.
  - The context is always `production`; `--context`/`--contexts` are refused.
  - The password comes only from `SDV_SCHEMA_DB_PASSWORD` or a prompt; `--password` is refused. URL and user come from `SDV_SCHEMA_DB_URL`/`SDV_SCHEMA_DB_USERNAME` or options. PostgreSQL only.
  - `sql` won't overwrite a file and changes nothing. `update` checks the result afterwards. A newer database exits 3.
  - Liquibase 4.31's anonymous analytics are switched off for the step (`liquibase.analytics.enabled=false`).
- `Update-SDV-Schema.ps1` and `update-sdv-schema.sh` run it from the application jar, through Spring Boot's `PropertiesLauncher` (in the jar already). No new dependency. The PowerShell script asks for the password with `Read-Host -AsSecureString` and hands it to Java only through its own environment.

**4. Two database users**
- Documented and tested: the owner runs the schema step; the app user has connect and `SELECT/INSERT/UPDATE/DELETE` on tables only, given ahead of time with `ALTER DEFAULT PRIVILEGES`.
- Checked: SDV has no sequences, functions or triggers, and creates nothing at runtime. Its one `LOCK TABLE storage_volumes IN SHARE ROW EXCLUSIVE MODE` is allowed by the UPDATE right. The app ran as such a user, and its jobs read tables without errors.

**5. Install guide:** `docs/operations/SDV_V1_GA_CLEAN_INSTALL_RUNBOOK.md`, new section "Update the database": the two users (with the SQL), the step and its options, first install and upgrade (stop, status, review, update, start), running the SQL yourselves (no single transaction, because of 018), and the test script.

**LexaVault's `LOGIN_CUSTOMER_ENTITY_DESIGN.md`** is not in this repository, so there was no schema-step shape to follow.

## Commands run and results

| Command | Result |
|---|---|
| `mvn -pl sdv-web -am test -Dtest=Schema*Test,LiquibaseSeedContextTest` | PASS |
| `.\stop-local.cmd`, `.\Build-SDV-All.ps1` | **PASS**: BUILD SUCCESS, 0 failures; sdv-web 101 tests (81 before, +20 new); frontend built |
| `.\Test-SDV-CleanInstall.ps1` | **PASS** (timings below) |
| `.\start-local.cmd` | Started; health UP, `databaseSchema` CURRENT, profile `local` |
| `npm run typecheck`, `npm run build` (after the last edit) | PASS |
| `npm run ui:capture` | 1st run: 16 passed, 2 skipped. After the last edit: 15 passed, 1 failed, 2 skipped. Rerun unchanged: **16 passed, 2 skipped** |

**The one failed tour run:** the Document Steward's search for 100000000001 showed "This source has no field the search box can use". It is in the steward's record search, which this task doesn't touch, and it passed on the runs before and after. It looks like a clash with state another tour test made; worth watching if it comes back.

**New tests:**
- `SchemaVersionTest` (7): empty, current, local DB with the seed, one missing, unknown (newer), same id by another author, exact sentences.
- `SchemaGateFilterTest` (4): 503 with the sentence, newer, open paths, pass-through.
- `SchemaStepTest` (6): environment and options, password and context refused, unclear requests refused, exit 2 without connecting.
- `LiquibaseSeedContextTest` (+3): **a production start has `spring.liquibase.enabled=false`**, local has `true`, and the context can't be overridden.
- Tour: Health with an older database (simulated in the browser): the notice and sentence, no generic failure, no cleanup panel. Screenshot `health-database-older`.

## Clean-install check (`Test-SDV-CleanInstall.ps1`)

It uses `sdv_install_test` and two scratch roles (`sdv_install_owner`, `sdv_install_app`, random passwords held in memory only) inside `sdv-postgres`, and drops all three at the end, also on failure. It doesn't touch the `sdv` database or any container. Vero runs on port 8291 without a profile, with sign-in `disabled` and the key service `disabled`.

| Step | Seconds | Result |
|---|---|---|
| Create scratch database, owner and app user | 0.3 | |
| 1. Start on the empty database | 7.2 | refuses: health 503, API 503 `DATABASE_OLDER`, the log line, **0 tables created** |
| 2a. `status` | 1.5 | 20 changesets would run; the local seed isn't listed |
| 2b. `sql` | 2.0 | 40 KB file with the 018 `CREATE INDEX CONCURRENTLY`; database unchanged |
| 2c. `update` | 2.4 | 20 changesets applied, no seed row, every index valid |
| App user rights | 0.0 | `CREATE TABLE` denied, `ALTER TABLE` denied, select/delete allowed |
| 3. Start after the update | 7.5 | health 200, CURRENT, **`tenants` empty**, the jobs read tables as the app user, no errors |
| 4. Start again | 7.6 | Spring start 6.6 s; changelog unchanged (20 rows); `status` says up to date (1.6 s) |
| Drop scratch database and users | 0.3 | dropped |

Whole check: 39 s. Each start's time is mostly the JVM and Spring; the check itself is one small query. Logs and the SQL file are in `.runtime/clean-install/<run>/`.

## Files changed

- **New:**
  - `backend/sdv-web/src/main/java/com/sdv/platform/web/schema/`: `SchemaVersion`, `DatabaseSchemaCheck`, `SchemaGateFilter`, `DatabaseSchemaHealthIndicator`, `SchemaStep`
  - `backend/sdv-web/src/test/java/com/sdv/platform/web/schema/`: three test classes
  - `Update-SDV-Schema.ps1`, `update-sdv-schema.sh`, `Test-SDV-CleanInstall.ps1`
- **Changed:**
  - `application.yml`, `application-local.yml`
  - `SystemInfoController`, `RetentionScheduler`, `RedactionRecoveryScheduler`, `OidcConnectionBootstrap`, `OidcKnownGoodRecovery`
  - `LiquibaseSeedContextTest`
  - `frontend/sdv-ui/src/r7/Operations.tsx`, `liveApi.ts`, `e2e/sdv-screen-tour.spec.ts`
  - `docs/operations/SDV_V1_GA_CLEAN_INSTALL_RUNBOOK.md`
- **Untouched:** `Start-SDV-Local.ps1`. No schema change, no new dependency.

## Commits

One commit on `feature/install-1` (hash in the commit log). Not pushed or merged.

## Not done, and why

- **Customer creation:** the clean database stays without tenants; the later shared setup command creates the customer, as the task says.
- **Rest of the runbook:** the older parts still describe the frozen V1.0.0 source archive and migration 010. Only the new section was added.

## Questions for the owner (answered: see the end)

1. **A mismatched database keeps Vero running but closed** (health and system information only) instead of exiting. That is what lets Health show the sentence. Is that right, or should Vero exit after logging the line?
2. **The check runs once, at start-up.** After the schema step, Vero has to be started again (the guide and the Health notice say so). Fine, or should it re-check by itself?
3. **Liquibase analytics in local mode:** Liquibase 4.31 has anonymous usage analytics. The schema step turns them off. Local-mode startup still runs Liquibase through Spring without that setting. Should I add `liquibase.analytics.enabled=false` there too? It is a one-line change.

Also for your information:
- The check created two scratch login roles at server level in `sdv-postgres` for the run and dropped them.
- To confirm that local mode still migrates, I read one count from the `sdv` database's `databasechangelog` (21 rows, as before). Nothing was written there.

## Owner's answers and follow-up (2026-11-18)

0013 is approved.

1. **Keep Vero up but closed, and report "not ready"**: done.
   - `/actuator/health/readiness` (new) answers 503 `OUT_OF_SERVICE` while the database doesn't match, and `/actuator/health` does too (before: `DOWN`).
   - `/actuator/health/liveness` (new) stays 200 `UP`, so a platform doesn't restart Vero in a loop.
   - **How:** probes are switched on, and the readiness group holds `readinessState` and `databaseSchema`. The schema indicator reports `OUT_OF_SERVICE`, and Vero sets Spring's readiness to `REFUSING_TRAFFIC` once it has started.
   - **Open without sign-in:** the two probe addresses, in `SecurityConfiguration` and the schema gate. All other `/actuator/health/…` addresses stay closed.
   - **Guide:** the runbook says which address to use for what.
2. **Restart after the schema step, no self re-check**: kept as built. Nothing changed.
3. **Liquibase analytics off in every mode**: done.
   - `UsageReporting.switchOff()` sets `liquibase.analytics.enabled=false`. It runs first in `SdvApplication.main` (the running app and local mode) and in the schema step.
   - Liquibase reads this setting from Java system properties, not from `application.yml`, which is why it is set in code.
   - A test reads the value back through Liquibase's own setting (`AnalyticsArgs.ENABLED`).

**Nothing else in the app sends usage data out.** What I checked:
- **Libraries in the app jar** (all 73): none reports usage. Micrometer is there for the health endpoint, but no metrics exporter is bundled, and only `health` and `info` are exposed. The only library that did report usage was Liquibase.
- **Outbound calls in the code:**
  - `VaultTransitKeyProtectionProvider` calls the configured key service.
  - `OidcDiscoveryTester` calls the configured sign-in server, and only from the draft test endpoint that the UI no longer uses.
  - The JWT validation library fetches the configured sign-in server's keys.
  - The shared identity library (`enterprise-identity-parent`, 32 source files) makes no outbound calls.
  - The PostgreSQL driver connects to the configured database.
- **Frontend:**
  - It depends only on `react`, `react-dom` and `lucide-react`. The HTML loads no outside script.
  - The built bundle has no tracker.
  - Its only outside call is the configured sign-in server (`auth.ts`).
  - The other outside addresses in the bundle aren't calls. They are prototype text in `data.ts`, a placeholder, and React's error-message link.
- **At runtime:** `Test-SDV-CleanInstall.ps1` now records every connection the Vero process opens to anything other than this computer. It saw none over its three starts (18 samples). A separate watch of a `start-local.cmd` start, where Liquibase runs, saw none in 144 samples.

**Commands run for the follow-up:**

| Command | Result |
|---|---|
| `.\stop-local.cmd`, `.\Build-SDV-All.ps1` | PASS: BUILD SUCCESS, 0 failures; sdv-web 108 tests (+4 health indicator, +3 usage reporting) |
| `.\Test-SDV-CleanInstall.ps1` | PASS in 43 s. Step 1: health and readiness 503 `OUT_OF_SERVICE`, liveness 200 `UP`. Step 3: readiness 200. No outside connection |
| `.\start-local.cmd` | Started (UP, CURRENT, local); no outside connection while it started |
| `npm run ui:capture` | 1st run: 14 passed, 2 failed, 2 skipped. Rerun unchanged: **16 passed, 2 skipped** |

**The failed tour run (not fixed: it is outside this task):**
- **Symptom:** both failures were the Business User's and the Document Steward's search for record 100000000001. The Records page had a source with no search field selected ("This source has no field the search box can use"). This is the same failure as the one earlier in this task.
- **Not caused by this change:** the Records page and the tour's search steps are unchanged.
- **What I found:**
  - A tour run at 13:16 today left a paused source, "Tour test system 20261118181639", with no fields. Business users still see it in the search schema.
  - The schema sorts sources by name, so LexaVault comes first, and the tour runs one test at a time. I haven't found what makes the page select the other source.
- **Suggested follow-up:**
  - remove that leftover source (your call: it is development data);
  - have the tour pick LexaVault explicitly before searching.

**Files:**
- **New:** `UsageReporting.java`, `UsageReportingTest.java`, `DatabaseSchemaHealthIndicatorTest.java`, `SchemaStepAccess.java` (test helper).
- **Changed:**
  - `SdvApplication.java`, `SchemaStep.java`
  - `DatabaseSchemaHealthIndicator.java`, `SchemaGateFilter.java`, `SchemaGateFilterTest.java`
  - `application.yml`, `SecurityConfiguration.java` (sdv-security)
  - `Test-SDV-CleanInstall.ps1`, the runbook

## Suggested next step

Test the clean-install check and an upgrade on a real customer-like PostgreSQL with the team's own users. Point a load balancer at `/actuator/health/readiness` and a restart policy at `/actuator/health/liveness`.
