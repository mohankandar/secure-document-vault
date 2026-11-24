-- Task 0066 (owner decision 2026-11-23): a document on legal hold is permanently redacted only with a Business Admin's
-- approval, and its original is kept sealed until the last hold is released, then destroyed. No existing row changes.

-- 1. A new kind of request: it names one document and no level.
alter table access_requests drop constraint ck_access_requests_kind;
alter table access_requests add constraint ck_access_requests_kind
    check (kind in ('PRINT', 'DOWNLOAD', 'LOWER_LEVEL', 'LOWER_RECORD_LEVEL', 'REDACT_HELD'));
alter table access_requests drop constraint ck_access_requests_requested_level;
alter table access_requests add constraint ck_access_requests_requested_level check (
    (kind in ('LOWER_LEVEL', 'LOWER_RECORD_LEVEL') and requested_level is not null
        and requested_level in ('STANDARD', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL'))
    or (kind in ('PRINT', 'DOWNLOAD', 'REDACT_HELD') and requested_level is null));

-- 2. What the request asks to redact, and the version of the document it was asked for. APPROVED rows are what the
--    background job applies.
create table held_redactions (
    request_id uuid primary key references access_requests(request_id),
    tenant_id uuid not null references tenants(tenant_id),
    document_id uuid not null,
    regions_json jsonb not null,
    content_sha256 char(64) not null,
    state text not null,
    attempts integer not null default 0,
    claimed_at timestamptz null,
    applied_at timestamptz null,
    constraint fk_held_redactions_document foreign key (tenant_id, document_id) references documents (tenant_id, document_id),
    constraint ck_held_redactions_regions check (jsonb_typeof(regions_json) = 'array' and jsonb_array_length(regions_json) > 0),
    constraint ck_held_redactions_sha check (content_sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_held_redactions_state check (state in ('WAITING', 'APPROVED', 'APPLIED', 'FAILED')),
    constraint ck_held_redactions_attempts check (attempts >= 0),
    constraint ck_held_redactions_applied check ((state = 'APPLIED') = (applied_at is not null))
);
create index idx_held_redactions_approved on held_redactions(request_id) where state = 'APPROVED';
create index idx_held_redactions_document on held_redactions(tenant_id, document_id);

-- 3. A redaction with an approved request keeps its original sealed instead of deleting it.
alter table document_redaction_operations add column access_request_id uuid null references held_redactions(request_id);

-- 4. The sealed originals: where the old encrypted file is and how it is read. Its data key is forgotten with the file.
create table sealed_originals (
    sealed_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    document_id uuid not null,
    -- Null: the hold was placed while a redaction was already running.
    request_id uuid null unique references held_redactions(request_id),
    provider_id varchar(100) not null,
    storage_volume_id uuid not null,
    object_key varchar(700) not null,
    encrypted_size_bytes bigint not null,
    integrity_value char(64) not null,
    crypto_profile varchar(100) not null,
    crypto_version integer not null,
    key_provider_id varchar(100) not null,
    protected_data_key_reference varchar(700) null,
    key_provider_version varchar(100) null,
    content_sha256 char(64) not null,
    content_size_bytes bigint not null,
    sealed_at timestamptz not null,
    state text not null,
    destroying_at timestamptz null,
    destroyed_at timestamptz null,
    constraint fk_sealed_originals_document foreign key (tenant_id, document_id) references documents (tenant_id, document_id),
    constraint fk_sealed_originals_volume foreign key (tenant_id, storage_volume_id) references storage_volumes (tenant_id, volume_id),
    constraint uq_sealed_originals_object unique (tenant_id, provider_id, object_key),
    constraint ck_sealed_originals_sizes check (encrypted_size_bytes > 0 and content_size_bytes > 0 and crypto_version > 0),
    constraint ck_sealed_originals_shas check (integrity_value ~ '^[0-9a-f]{64}$' and content_sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_sealed_originals_state check (state in ('SEALED', 'DESTROYING', 'DESTROYED')),
    constraint ck_sealed_originals_destroying check ((state = 'SEALED') = (destroying_at is null)),
    constraint ck_sealed_originals_destroyed check ((state = 'DESTROYED') = (destroyed_at is not null)),
    constraint ck_sealed_originals_key check ((state = 'DESTROYED') = (protected_data_key_reference is null))
);
create index idx_sealed_originals_document on sealed_originals(tenant_id, document_id);
create index idx_sealed_originals_kept on sealed_originals(sealed_at) where state <> 'DESTROYED';
