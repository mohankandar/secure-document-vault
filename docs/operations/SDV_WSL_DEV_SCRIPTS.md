# SDV DEV on Ubuntu / WSL2

This guide applies only to the existing SDV DEV checkout on branch release/vero-r7.
Do not use these commands to create an SDV DEMO or production environment.

## Separation

- Repository: ~/projects/sdv/secure-document-vault
- Logs: ~/projects/sdv/logs/backend/backend.log and logs/frontend/frontend.log
- Runtime PID records: ~/projects/sdv/runtime/run/
- PostgreSQL: existing container sdv-postgres, port 5433
- Persistent Vault: existing container sdv-vault, port 8200
- Backend: port 8280
- Frontend: port 5183

These paths assume the checkout lives directly below ~/projects/sdv.
No generated log or PID file belongs inside the repository.

## Build

Run only when the application is stopped or when rebuilding has been approved:

    cd ~/projects/sdv/secure-document-vault
    bash Build-SDV-WSL.sh

The build runs mvn clean verify (all backend modules), npm ci (locked dependencies),
and npm run build. Use --skip-tests only when explicitly intended.

## Infrastructure is manually controlled

Start Docker Desktop, the existing sdv-postgres and existing sdv-vault containers
separately. Never create or replace either container from this application launcher.
Vault must already be initialized and **unsealed** before application startup.
The script does not use the root token, initialize/unseal Vault, or create a new key.

The default application Vault token file is:
    ~/backups/sdv-dev-vault/sdv-app.token

Keep this token file private (chmod 600). The startup checks that the token
is valid and has Transit encrypt/decrypt capabilities for the configured tenant
key prefix. A failure is a stop condition, not permission to disable encryption.
Use a separately approved manual unseal procedure when the Vault is sealed.

## Start / stop

    cd ~/projects/sdv/secure-document-vault
    bash Start-SDV-WSL.sh
    bash Stop-SDV-WSL.sh

Only backend and frontend processes created by this WSL launcher are stopped.
PostgreSQL, Vault, Docker, LexaVault and SDV DEMO remain untouched.

If existing app ports are occupied but the process is not tracked by the launcher,
startup refuses to take over the processes. Stop the prior manually launched
application with its original mechanism first.

## Rolling logs

- Backend: ../logs/backend/backend.log
- Frontend: ../logs/frontend/frontend.log

Each file rotates by size at 25 MiB and retains 14 numbered backup files
(15 files total per stream, approximately 375 MiB per stream maximum).
The log files remain outside the repository. This is *size-based* rotation,
not daily/date-based rotation.

## Limitations

The Linux scripts have been committed and reviewed from source, but need a
controlled stop/start smoke test on the MSI Ubuntu environment before they can
be considered runtime-verified. Current app processes are not modified by Git
pulls. Vault Transit token capabilities are checked against a synthetic name
under the configured tenant key prefix; policy models that only permit exact
tenant keys may require a reviewed alternative preflight.

Do not use docker compose down -v, regenerate Vault keys, substitute a dev-mode
Vault or automatically disable encryption to recover from an error.
