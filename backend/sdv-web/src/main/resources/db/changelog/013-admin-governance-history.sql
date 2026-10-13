-- R3 Iteration 7: named/versioned retention policy history.
-- Existing document expiration snapshots are not rewritten.

create table retention_policy_versions (
    policy_version_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    version integer not null check (version > 0),
    policy_name varchar(200) not null,
    description varchar(1000) null,
    retention_days integer null check (retention_days is null or retention_days > 0),
    effective_from timestamptz not null,
    effective_until timestamptz null,
    created_by varchar(300) not null,
    created_at timestamptz not null,
    unique (tenant_id, version),
    check (effective_until is null or effective_until >= effective_from)
);

create unique index uq_retention_policy_current
    on retention_policy_versions(tenant_id)
    where effective_until is null;

create index idx_retention_policy_history
    on retention_policy_versions(tenant_id, effective_from desc, version desc);

-- Reconstruct as much legacy history as the append-only audit permits. The first
-- row captures the policy in force at tenant creation. Each historical update
-- becomes a subsequent immutable version. Legacy rows receive generated names
-- because policy names did not exist before this migration.
with ordered_updates as (
    select a.tenant_id,
           a.audit_event_id,
           a.occurred_at,
           a.actor_id,
           a.safe_details,
           row_number() over (partition by a.tenant_id order by a.occurred_at, a.audit_event_id) as rn,
           lead(a.occurred_at) over (partition by a.tenant_id order by a.occurred_at, a.audit_event_id) as next_at
      from audit_events a
     where a.action = 'RETENTION_POLICY_UPDATED'
       and a.outcome = 'SUCCESS'
), first_updates as (
    select distinct on (tenant_id)
           tenant_id, occurred_at, safe_details
      from ordered_updates
     order by tenant_id, rn
)
insert into retention_policy_versions(
    policy_version_id, tenant_id, version, policy_name, description,
    retention_days, effective_from, effective_until, created_by, created_at)
select md5(t.tenant_id::text || ':retention:initial')::uuid,
       t.tenant_id,
       1,
       'Initial retention policy',
       'Backfilled when named retention-policy history was introduced.',
       case
           when f.safe_details is not null and jsonb_exists(f.safe_details, 'previousDefaultRetentionDays')
               then nullif(f.safe_details ->> 'previousDefaultRetentionDays', '')::integer
           else t.default_retention_days
       end,
       t.created_at,
       f.occurred_at,
       'SYSTEM_MIGRATION',
       t.created_at
  from tenants t
  left join first_updates f on f.tenant_id = t.tenant_id;

with ordered_updates as (
    select a.tenant_id,
           a.audit_event_id,
           a.occurred_at,
           a.actor_id,
           a.safe_details,
           row_number() over (partition by a.tenant_id order by a.occurred_at, a.audit_event_id) as rn,
           lead(a.occurred_at) over (partition by a.tenant_id order by a.occurred_at, a.audit_event_id) as next_at
      from audit_events a
     where a.action = 'RETENTION_POLICY_UPDATED'
       and a.outcome = 'SUCCESS'
)
insert into retention_policy_versions(
    policy_version_id, tenant_id, version, policy_name, description,
    retention_days, effective_from, effective_until, created_by, created_at)
select md5(o.audit_event_id::text || ':retention')::uuid,
       o.tenant_id,
       (o.rn + 1)::integer,
       'Legacy retention policy ' || (o.rn + 1),
       'Backfilled from the existing retention-policy audit history.',
       nullif(o.safe_details ->> 'defaultRetentionDays', '')::integer,
       o.occurred_at,
       o.next_at,
       o.actor_id,
       o.occurred_at
  from ordered_updates o;
