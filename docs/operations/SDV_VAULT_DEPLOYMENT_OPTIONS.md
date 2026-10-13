# SDV Vault / Key Provider Deployment Options

SDV keeps the key-provider boundary explicit. `sdv.crypto.key-provider=disabled` is allowed only for flows that do not require protected document crypto. `sdv.crypto.key-provider=vault` enables Vault Transit-backed SDV-ENC-001 processing.

## External / production Vault

Configure `SDV_VAULT_BASE_URL`, Transit mount/key prefix, and exactly one token source:

- `SDV_VAULT_TOKEN_FILE` — recommended for orchestrator-mounted secret files; or
- `SDV_VAULT_TOKEN` — retained for local/dev compatibility.

Startup fails closed when Vault mode is selected but the endpoint or token source is missing, both token sources are configured, or the token file cannot be read. SDV does not fall back to another key provider.

## Bundled development Vault

The repository's local Docker/dev Vault remains an acceptance convenience, not a production dependency. Production/on-prem operators may provide their own supported Vault topology. SDV depends only on the configured Transit API contract and does not own Vault clustering, snapshots, unseal, or DR.

## Secret handling

Do not commit Vault tokens. Mounted token files should be readable only by the SDV runtime identity. Recovery of Vault/key-management state remains part of the coordinated SDV recovery set.
