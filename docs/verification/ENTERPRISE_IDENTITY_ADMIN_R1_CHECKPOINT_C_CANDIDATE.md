# SDV Enterprise Identity Administration R1 — Checkpoint C Candidate

Status: IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED

Checkpoint C adds controlled activation and recovery on top of the frozen Checkpoint B draft/test workflow.

## Safety boundaries

- Only the exact current `VALIDATED` configuration version may activate.
- Activation requires a PASSED connection test from the last 15 minutes.
- Activation is transactional: previous ACTIVE -> DISABLED and exact VALIDATED -> ACTIVE.
- Migration 016 enforces at most one ACTIVE connection per tenant.
- Previously ACTIVE tenant-scoped rows remain available as known-good rollback candidates.
- Rollback requires exact version plus immutable history proving the target was previously ACTIVE.
- Every endpoint remains protected by `CONFIGURE_TENANT_AUTH` through `requireTenantIt()`.
- Activation and rollback use explicit confirmation values and safe audit records.
- The pre-C stale-test version guard remains in place; draft save is also version-guarded.
- One-way startup bootstrap now does nothing once any identity administration record exists for the tenant.
- Human OIDC administration does not alter the separate SDV M2M authentication path.

## Emergency recovery

Recovery is disabled by default and intentionally has no browser/API endpoint. Deployment operators can enable a one-shot startup recovery to a known-good, previously ACTIVE connection with:

- `sdv.auth.jwt.recovery.enabled=true`
- `sdv.auth.jwt.recovery.tenant-id=<tenant UUID>`
- `sdv.auth.jwt.recovery.connection-id=<known-good connection UUID>`
- `sdv.auth.jwt.recovery.expected-version=<version>`
- `sdv.auth.jwt.recovery.request-id=<new unique request id>`

The request id is recorded in immutable identity history and cannot be replayed after consumption. Disable recovery after use.

## Deployment coordination

The SDV browser OIDC issuer/client configuration remains deployment-provided. Coordinate those settings before activating a configuration that changes issuer or browser client id. If authentication is lost, use the one-shot recovery mechanism.
