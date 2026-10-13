-- SDV Secure Ingestion Checkpoint A (Units 5, 7, 8):
-- submission-create idempotency fingerprint and tenant-scoped local storage volumes.

alter table submissions
    add column create_request_sha256 char(64) null;

alter table submissions
    add constraint ck_submission_create_request_sha256
    check (create_request_sha256 is null or create_request_sha256 ~ '^[0-9a-f]{64}$');

create table storage_volumes (
    volume_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    provider_id varchar(100) not null,
    display_name varchar(200) not null,
    root_path varchar(1200) not null,
    state varchar(20) not null check (state in ('ACTIVE','READ_ONLY','OFFLINE','DRAINING')),
    priority integer not null default 100 check (priority >= 0),
    warning_percent integer not null check (warning_percent between 1 and 99),
    stop_write_percent integer not null check (stop_write_percent between 2 and 100),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (stop_write_percent > warning_percent),
    unique (tenant_id, volume_id),
    unique (provider_id, root_path)
);

create index idx_storage_volumes_tenant_state_priority
    on storage_volumes(tenant_id, state, priority, volume_id);

alter table document_storage_refs
    add constraint fk_document_storage_volume
    foreign key (tenant_id, storage_volume_id)
    references storage_volumes(tenant_id, volume_id);
