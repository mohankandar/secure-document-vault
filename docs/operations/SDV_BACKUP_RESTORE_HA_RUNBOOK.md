# SDV V1 Backup, Restore and HA Runbook

## Purpose

This runbook defines the V1 operational recovery contract for Secure Document Vault. It is provider-neutral and intentionally does not prescribe one vendor-specific backup product.

V1 is **single-region, HA-ready**. Multi-region active-active is not part of V1.

## 1. Recovery set

A usable SDV recovery point consists of four coordinated parts:

| Component | Required recovery content |
|---|---|
| PostgreSQL | SDV schema/data, audit, document/storage/crypto references, tenant/source/config state, Liquibase history |
| Encrypted storage | Every protected object needed by the selected recovery point |
| Key management | Key-service state required to unwrap the protected data-key references stored in PostgreSQL |
| Deployment configuration | Application artifact/version plus non-secret topology/config and separately managed secrets/credentials |

Do not declare recovery successful if any one component is unavailable.

## 2. Backup policy ownership

- Tenant-managed/on-prem deployment: Tenant IT owns schedule, retention, media/location, encryption, off-site copy, RPO/RTO and restore drills.
- Operator-hosted deployment: Platform Operations owns those controls.

SDV does not silently copy plaintext PDFs into a backup area. Protected storage remains ciphertext.

## 3. Consistency expectations

Prefer a coordinated snapshot/recovery point that can establish a known relationship among:

- PostgreSQL commit state;
- storage-object state;
- key-management state;
- deployment configuration/version.

Acceptable mechanisms depend on infrastructure. Examples include quiesced backups, storage snapshots coordinated with a database recovery point, database PITR/WAL plus versioned object storage, or vendor-supported backup orchestration.

After recovery, validate referenced objects and keys rather than assuming a timestamp-aligned backup is complete.

## 4. PostgreSQL

Production guidance:

- use PostgreSQL-supported physical backup/PITR or a tested logical-backup strategy appropriate to the deployment;
- retain WAL/transaction-log material when PITR is required;
- protect backup credentials and backup media separately;
- test restore into an isolated environment;
- verify `databasechangelog` and application compatibility before serving traffic.

The Unit 22 local acceptance uses `pg_dump`/`pg_restore` only as a deterministic developer validation mechanism.

## 5. Encrypted storage

Back up the storage volume/object namespace with exact byte preservation.

For each restored document selected for validation:

1. read its `provider_id`, `storage_volume_id`, `object_key`, `encrypted_size_bytes` and `integrity_value` from PostgreSQL;
2. verify the volume mapping resolves to the intended restored storage location;
3. confirm the object exists;
4. verify size and SHA-256/integrity value before attempting decryption.

For multi-instance deployments, every active application node must see the same volume contents. A node-private local disk is not a valid shared HA volume.

## 6. Key management

Protect and restore key-management state using the key provider's supported production mechanism.

For Vault Transit, prefer the supported backup/snapshot/DR mechanism for the actual Vault storage topology and organizational policy. Do not enable plaintext Transit key backup as a routine production shortcut merely because Unit 22 uses it in an isolated test fixture.

The Unit 22 local acceptance temporarily sets both `allow_plaintext_backup=true` and `exportable=true` on its **disposable dev-Vault tenant key** because Vault requires those key-policy settings for the per-key `/transit/backup` fixture. Both settings are irreversible for that key. Never apply this acceptance technique to production tenant keys merely to simplify DR.

Key backups are security-sensitive and require controls at least as strong as the protected content they can unlock.

## 7. Configuration and secrets

Retain versioned deployment configuration sufficient to restore:

- database endpoint and pool settings;
- storage-provider/volume mappings;
- key-provider endpoint, Transit mount and tenant-key prefix;
- enterprise authentication issuer/JWKS/audience/claim mapping;
- retention scheduler settings;
- application artifact/container version;
- TLS/ingress/runtime configuration.

Do not place database passwords, Vault tokens, OIDC client secrets or private keys into an ordinary recovery manifest. Restore those through the deployment's approved secret-management channel.

## 8. HA application topology

Application nodes are stateless request processors.

Recommended V1 topology:

- 2+ application instances in one region/availability domain set as appropriate;
- shared/redundant PostgreSQL service;
- shared/redundant encrypted storage visible consistently to all nodes;
- highly available key service;
- load balancer/ingress health checks against `/actuator/health`;
- no sticky-session requirement for correctness.

Before adding another active application node, verify it has identical trusted authentication configuration and equivalent connectivity to PostgreSQL, storage and key service.

## 9. Background destructive work

Retention deletion uses a database-backed `RETENTION_DELETING` lease:

- fresh work cannot be claimed by another application instance for 10 minutes;
- if an instance crashes mid-delete, the row becomes reclaimable after 10 minutes;
- Unit 20 operational monitoring already treats aged in-progress transitions as attention-worthy.

Do not reduce the lease without coordinated performance/resilience testing. Unit 24 owns deeper cadence/concurrency tuning.

## 10. Restore order

A practical restore sequence is:

1. provision isolated target infrastructure;
2. restore key-management service/state;
3. restore PostgreSQL to the selected recovery point;
4. restore/mount encrypted storage;
5. restore non-secret topology/config and separately inject secrets;
6. deploy the matching SDV application version;
7. keep external traffic disabled;
8. run the validation checklist below;
9. enable traffic only after validation passes.

The exact infrastructure order may vary, but SDV must not serve a recovery as successful until the whole set validates.

## 11. Restore validation checklist

For at least one representative document per required tenant/storage/key path:

- [ ] correct tenant/document metadata exists;
- [ ] document status is consistent with expected recovery point;
- [ ] referenced storage volume is configured;
- [ ] encrypted object exists;
- [ ] encrypted object size matches committed metadata;
- [ ] encrypted object integrity SHA-256 matches committed metadata;
- [ ] key service is reachable;
- [ ] protected data key can be unwrapped for the document tenant;
- [ ] authenticated ciphertext decryption succeeds;
- [ ] plaintext content SHA-256 matches the document record;
- [ ] authorized secure viewer can serve metadata/page content;
- [ ] unauthorized/cross-tenant access still fails;
- [ ] application/database/key/storage health is green or intentionally understood;
- [ ] audit remains append-capable after restore.

## 12. Failure behavior

Expected fail-closed outcomes:

- DB restored but object missing -> viewer/download unavailable;
- DB + object restored but key state missing -> viewer/download unavailable;
- wrong tenant/key context -> decryption unavailable;
- ciphertext modified -> integrity/authentication failure;
- incompatible/missing application configuration -> startup or operation fails rather than inventing fallback storage/key identity.

Never bypass integrity or key checks merely to make a restore appear successful.

## 13. DR drill evidence

Retain operator evidence appropriate to policy, such as:

- recovery-point identifier/timestamp;
- application version and migration level;
- database backup identifier;
- storage snapshot/version identifier;
- key-service backup/snapshot identifier;
- validation document IDs (not plaintext content);
- validation timestamps/results;
- exceptions/known limitations;
- approver/operator identity.

Do not put document plaintext, removed redaction text, credentials or raw private key material in ordinary DR evidence.


## 14. Recovery manifest/exportability helper

`tools/recovery/Export-SdvRecoveryManifest.ps1` creates a credential-free manifest that ties together an operator-created PostgreSQL backup, encrypted-storage backup root, key-management backup artifact, and application artifact. It records SHA-256/size evidence for file artifacts and a per-object relative-path/SHA-256/size inventory plus object-count/byte-count evidence for the encrypted storage tree. It does **not** create plaintext document copies and does not read/store DB passwords, Vault tokens, OIDC secrets, or key plaintext.

Validate the same material before a drill with `tools/recovery/Test-SdvRecoveryManifest.ps1`. The validator fails closed on missing artifacts, checksum changes (including same-size encrypted-object corruption), path escape attempts, or storage inventory mismatch. The scripts are compatible with Windows PowerShell 5.1 and newer PowerShell versions. These tools do not replace the provider-native backup procedure; they make the coordinated recovery set explicit and portable for acceptance/DR evidence.
