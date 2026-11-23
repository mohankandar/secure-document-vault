-- Task 0053 (owner-approved with the task): the Storage and retention report. Its counts are worked out in the
-- background (every 15 minutes, and the first time the report is opened) and kept here, one row per entity, so a
-- screen load never reads the documents. Counts only: never content, file names, titles or record identifiers.
create table report_storage_counts (
    tenant_id uuid primary key references tenants(tenant_id),
    counted_at timestamptz not null,
    took_ms bigint not null,
    counts jsonb not null,
    constraint ck_report_storage_counts_took check (took_ms >= 0),
    constraint ck_report_storage_counts_object check (jsonb_typeof(counts) = 'object')
);
