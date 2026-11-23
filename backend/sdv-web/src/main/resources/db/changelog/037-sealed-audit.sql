-- Task 0050 (owner-approved with the task): the sealed audit report. One new table, the register of generated
-- reports; no existing table or row changes. The audit trail becomes append-only in 037-sealed-audit-append-only.sql.

-- Every sealed audit report generated: who, when, the range and filters, the row count and the SHA-256 fingerprint of
-- its CSV. The CSV itself is kept, so the PDF and later downloads are made from exactly the sealed rows.
create table audit_reports (
    report_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    generated_by varchar(300) not null,
    generated_by_display_name varchar(300) null,
    generated_at timestamptz not null,
    range_from timestamptz not null,
    range_to timestamptz not null,
    filters jsonb not null default '{}'::jsonb,
    row_count integer not null,
    fingerprint varchar(64) not null,
    csv bytea not null,
    constraint ck_audit_reports_generated_by check (length(generated_by) > 0),
    constraint ck_audit_reports_range check (range_from < range_to),
    constraint ck_audit_reports_row_count check (row_count >= 0),
    constraint ck_audit_reports_fingerprint check (fingerprint ~ '^[0-9a-f]{64}$'),
    constraint ck_audit_reports_csv check (length(csv) > 0)
);
create index idx_audit_reports_tenant on audit_reports(tenant_id, generated_at desc);
create index idx_audit_reports_fingerprint on audit_reports(tenant_id, fingerprint);
