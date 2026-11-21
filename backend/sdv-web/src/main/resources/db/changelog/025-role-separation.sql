-- Task 0025 (S2): role storage for the Restricted Steward separation rules. Owner-approved with the task (role and
-- capability storage only). New tables only; no existing table or row is changed.

-- Rule 1: a sign-in whose roles can never be held together. One row per person, source and set of held roles; later
-- sightings update last_seen_at and occurrences. Read by the Tenant IT Admin.
create table role_conflict_problems (
    problem_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    subject varchar(300) not null,
    display_name varchar(300) null,
    resolved_from varchar(40) not null check (resolved_from in ('LOGIN_GROUPS', 'LOCAL_SIGN_IN')),
    held_roles varchar(800) not null check (held_roles <> ''),
    withheld_roles varchar(800) not null check (withheld_roles <> ''),
    broken_rules varchar(800) not null check (broken_rules <> ''),
    first_seen_at timestamptz not null,
    last_seen_at timestamptz not null,
    occurrences bigint not null check (occurrences >= 1),
    constraint uq_role_conflict_problems unique (tenant_id, subject, resolved_from, held_roles),
    constraint ck_role_conflict_problems_seen check (last_seen_at >= first_seen_at)
);
create index idx_role_conflict_problems_tenant_seen on role_conflict_problems(tenant_id, last_seen_at desc);

-- Rule 3: the login groups last seen for a watched role (comma-separated, sorted; empty for none). The server's
-- settings hold the mapping; this is only what was recorded at the last start, to detect a change.
create table role_group_mappings_seen (
    role_name varchar(80) primary key,
    login_groups text not null,
    recorded_at timestamptz not null
);

-- Rule 3: one notice per tenant for its Business Admin when the groups changed. groups_before is null when nothing
-- was recorded before.
create table role_mapping_notices (
    notice_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    role_name varchar(80) not null,
    groups_before text null,
    groups_after text not null,
    created_at timestamptz not null
);
create index idx_role_mapping_notices_tenant_time on role_mapping_notices(tenant_id, created_at desc);
