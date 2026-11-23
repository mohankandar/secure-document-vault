-- Task 0054 (owner-approved with the task): the Activity report, built from the audit trail. A busy month at 10,000
-- agents is tens of millions of audit events, so each closed day (in the entity's time zone) is counted once in the
-- background and kept here; a report reads these counts, and only the part of today not yet counted from the audit.
-- Counts only: kinds, role names, levels and counts; a person only by sign-in name, for opens (the busiest people).

-- A day's counts by kind, roles and level (a few dozen rows a day).
create table report_activity_counts (
    tenant_id uuid not null references tenants(tenant_id),
    day date not null,
    kind text not null,
    roles text not null,
    level text null,
    events bigint not null,
    constraint ck_report_activity_counts_kind check (kind in ('OPENED', 'PRINTED', 'DOWNLOADED', 'REFUSED_LEVEL',
        'REFUSED_QUARANTINE', 'REQUESTED', 'APPROVED', 'DECLINED', 'EXPIRED', 'GRANT_USED', 'GRANT_RETURNED')),
    constraint ck_report_activity_counts_level check (level is null or level in ('STANDARD', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL')),
    constraint ck_report_activity_counts_events check (events > 0)
);
create index idx_report_activity_counts_day on report_activity_counts(tenant_id, day);

-- A day's opens per person (one row a person a day), for the busiest people.
create table report_activity_people (
    tenant_id uuid not null references tenants(tenant_id),
    day date not null,
    actor_id varchar(300) not null,
    actor_name varchar(300) null,
    roles text not null,
    standard bigint not null,
    confidential bigint not null,
    highly_confidential bigint not null,
    level_not_recorded bigint not null,
    total bigint not null,
    constraint ck_report_activity_people_counts check (standard >= 0 and confidential >= 0 and highly_confidential >= 0
        and level_not_recorded >= 0 and total = standard + confidential + highly_confidential + level_not_recorded and total > 0)
);
create index idx_report_activity_people_day on report_activity_people(tenant_id, day);

-- The days counted, and in which time zone (a change of the entity's time zone counts them again).
create table report_activity_days (
    tenant_id uuid not null references tenants(tenant_id),
    day date not null,
    time_zone text not null,
    counted_at timestamptz not null,
    events bigint not null,
    primary key (tenant_id, day),
    constraint ck_report_activity_days_events check (events >= 0)
);
