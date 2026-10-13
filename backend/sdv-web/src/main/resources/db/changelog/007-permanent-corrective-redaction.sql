-- SDV Unit 16 - Permanent Corrective Redaction
-- Durable staged replacement lets SDV remove the prior ciphertext before switching the current document.

alter table documents drop constraint documents_status_check;

alter table documents add constraint documents_status_check
    check (status in (
        'RECEIVING','RECEIVED','PROCESSING','AVAILABLE',
        'REDACTION_PROCESSING','PROCESSING_FAILED','EXPIRED_DELETED'));

create table document_redaction_operations (
    operation_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    document_id uuid not null,
    source_id uuid not null,
    submission_id uuid not null,
    actor_id varchar(300) not null,
    request_sha256 char(64) not null check (request_sha256 ~ '^[0-9a-f]{64}$'),
    reason varchar(1000) not null,
    regions_json jsonb not null,

    old_provider_id varchar(100) not null,
    old_storage_volume_id uuid not null,
    old_object_key varchar(700) not null,
    old_encrypted_size_bytes bigint not null check (old_encrypted_size_bytes > 0),
    old_integrity_value char(64) not null check (old_integrity_value ~ '^[0-9a-f]{64}$'),
    old_content_sha256 char(64) not null check (old_content_sha256 ~ '^[0-9a-f]{64}$'),
    old_content_size_bytes bigint not null check (old_content_size_bytes > 0),

    new_provider_id varchar(100) not null,
    new_storage_volume_id uuid not null,
    new_object_key varchar(700) not null,
    new_encrypted_size_bytes bigint not null check (new_encrypted_size_bytes > 0),
    new_integrity_value char(64) not null check (new_integrity_value ~ '^[0-9a-f]{64}$'),
    new_crypto_profile varchar(100) not null,
    new_crypto_version integer not null check (new_crypto_version > 0),
    new_key_provider_id varchar(100) not null,
    new_protected_data_key_reference varchar(700) not null,
    new_key_provider_version varchar(100) null,
    new_content_sha256 char(64) not null check (new_content_sha256 ~ '^[0-9a-f]{64}$'),
    new_content_size_bytes bigint not null check (new_content_size_bytes > 0),
    created_at timestamptz not null,

    foreign key (tenant_id, document_id) references documents(tenant_id, document_id),
    foreign key (tenant_id, source_id) references ingestion_sources(tenant_id, source_id),
    foreign key (tenant_id, submission_id) references submissions(tenant_id, submission_id),
    foreign key (tenant_id, old_storage_volume_id) references storage_volumes(tenant_id, volume_id),
    foreign key (tenant_id, new_storage_volume_id) references storage_volumes(tenant_id, volume_id),
    unique (tenant_id, document_id),
    unique (tenant_id, new_provider_id, new_object_key)
);

create index idx_document_redaction_operations_created
    on document_redaction_operations(tenant_id, created_at, document_id);
