# Persistent local Vault for UI/document-viewer tests

The default `compose.local.yml` runs Vault with `server -dev` (in-memory). Each restart destroys Transit keys. Previously encrypted documents then cannot be decrypted even though their metadata remains in PostgreSQL. Do **not** use dev-mode Vault for persistent document fixtures, and do **not** deploy either local compose definition in production.

## First-time local setup (disposable fixtures only)

1. Stop the SDV backend so it cannot use Vault during migration.
2. Preserve any important encrypted documents and check that none require recovery. Switching from existing dev Vault **does not recover** its lost Transit keys.
3. From repository root, replace only the local Vault container with the persistent configuration:

   ```powershell
   docker compose -f compose.local.yml -f compose.vault-persistent.yml up -d --force-recreate sdv-vault
   docker exec -e VAULT_ADDR=http://127.0.0.1:8200 sdv-vault vault status
   ```

4. If `Initialized: false` (only on a genuinely new persistent volume), initialize once:

   ```powershell
   docker exec -e VAULT_ADDR=http://127.0.0.1:8200 sdv-vault vault operator init -key-shares=1 -key-threshold=1
   ```

   **Safeguard the unseal key and initial root token outside the repo, shell history, logs, or shared chat.** If initialization has already happened, **do not reinitialize**.
5. Unseal after startup. PowerShell prompts for secret input instead of embedding it in command history:

   ```powershell
   docker exec -it -e VAULT_ADDR=http://127.0.0.1:8200 sdv-vault vault operator unseal
   ```

6. Set the actual initial root token in your current PowerShell process and enable Transit once:

   ```powershell
   $env:VAULT_TOKEN = Read-Host "Vault root token"
   docker exec -e VAULT_ADDR=http://127.0.0.1:8200 -e VAULT_TOKEN=$env:VAULT_TOKEN sdv-vault vault secrets enable transit
   ```

   If `transit/` is already enabled, skip this command.

7. Make the restricted application token (a policy for the SDV Transit paths only) and keep it in a file outside the
   repository; the start scripts use that file (`SDV_VAULT_TOKEN_FILE`, default `..\secrets\sdv-app.token`). Never make a
   token with a known ID or the root policy for Vero. Then forget the root token:

   ```powershell
   Remove-Item Env:VAULT_TOKEN
   ```

8. Start backend/frontend with your normal local procedure, **without recreating the Vault container**. `-WithVault` can check an already running, unsealed instance but will not unseal it for you.
9. **Reingest disposable PDFs**; old Vault dev-mode ciphertext cannot be decrypted with new keys. Existing demo-seed scripts skip documents already marked AVAILABLE, so simply rerunning the seed may not replace the old ciphertext. Use a fresh unique demo submission or a separate disposable test database; do not overwrite historical document records or delete production data.

## Verify persistence

1. Ingest a new disposable PDF and confirm the *same document* renders.
2. Stop/restart only Vault: `docker restart sdv-vault`.
3. Unseal with the stored unseal key (regular Vault starts sealed).
4. Confirm Transit mount and the original newly ingested PDF remain decryptable.
5. Run the UI capture; it must pass on desktop and iPad.

**Never** run `docker compose down -v`, `docker volume rm sdv-vault-persistent-data`, or `vault operator init` against an initialized persisted Vault.

## Production follow-up

This local Raft single-node configuration is **not HA or production hardened**. Production readiness requires supported Vault HA topology, separate backups/snapshot-restoration tests, KMS/HSM unseal strategy, least-privilege authentication, TLS, key recovery drills, operational alerting, and eliminating the runtime's opportunistic key-creation behavior. Managed and customer-managed modes have distinct ownership.
