-- SDV Foundation (Units 1-4): core tenant-scoped metadata/state model.

create table tenants (
    tenant_id uuid primary key,
    tenant_key varchar(80) not null unique,
    display_name varchar(200) not null,
    status varchar(20) not null check (status in ('ACTIVE','SUSPENDED')),
    default_retention_days integer null check (default_retention_days is null or default_retention_days > 0),
    max_pdf_bytes bigint not null default 262144000 check (max_pdf_bytes > 0),
    max_pages_per_pdf integer not null default 2000 check (max_pages_per_pdf > 0),
    max_documents_per_submission integer not null default 100 check (max_documents_per_submission > 0),
    max_aggregate_submission_bytes bigint not null default 2147483648 check (max_aggregate_submission_bytes > 0),
    incomplete_attention_hours integer not null default 24 check (incomplete_attention_hours > 0),
    storage_warning_percent integer not null default 90 check (storage_warning_percent between 1 and 99),
    storage_stop_write_percent integer not null default 95 check (storage_stop_write_percent between 2 and 100),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    check (storage_stop_write_percent > storage_warning_percent)
);

create table ingestion_sources (
    source_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    source_key varchar(100) not null,
    display_name varchar(200) not null,
    status varchar(20) not null check (status in ('ACTIVE','DISABLED')),
    auth_type varchar(40) not null check (auth_type in ('OAUTH2_CLIENT_CREDENTIALS','JWT_BEARER','MTLS')),
    credential_reference varchar(300) null,
    max_pdf_bytes bigint null check (max_pdf_bytes is null or max_pdf_bytes > 0),
    max_pages_per_pdf integer null check (max_pages_per_pdf is null or max_pages_per_pdf > 0),
    max_documents_per_submission integer null check (max_documents_per_submission is null or max_documents_per_submission > 0),
    max_aggregate_submission_bytes bigint null check (max_aggregate_submission_bytes is null or max_aggregate_submission_bytes > 0),
    incomplete_attention_hours integer null check (incomplete_attention_hours is null or incomplete_attention_hours > 0),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (tenant_id, source_key),
    unique (tenant_id, source_id)
);
create index idx_ingestion_sources_tenant_status on ingestion_sources(tenant_id, status);

create table metadata_field_definitions (
    definition_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    source_id uuid not null,
    field_key varchar(100) not null,
    version integer not null check (version > 0),
    display_label varchar(200) not null,
    field_type varchar(30) not null check (field_type in ('TEXT','INTEGER','DECIMAL','DATE','DATETIME','BOOLEAN','EMAIL','UUID')),
    required boolean not null default false,
    business_identifier boolean not null default false,
    searchable boolean not null default false,
    normalization varchar(30) not null check (normalization in ('NONE','TRIM','UPPERCASE','LOWERCASE','DIGITS_ONLY')),
    search_mode varchar(20) not null check (search_mode in ('EXACT','PREFIX','PARTIAL')),
    validation_rules jsonb not null default '{}'::jsonb,
    active boolean not null default true,
    effective_at timestamptz not null default now(),
    retired_at timestamptz null,
    created_at timestamptz not null default now(),
    foreign key (tenant_id, source_id) references ingestion_sources(tenant_id, source_id),
    unique (tenant_id, source_id, field_key, version)
);
create unique index uq_metadata_field_active
    on metadata_field_definitions(tenant_id, source_id, field_key)
    where active;
create index idx_metadata_field_current_source
    on metadata_field_definitions(tenant_id, source_id, active, field_key);

create table submissions (
    submission_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    source_id uuid not null,
    source_submission_id varchar(200) not null,
    expected_document_count integer not null check (expected_document_count between 1 and 100000),
    status varchar(20) not null check (status in ('OPEN','RECEIVING','COMPLETE','INCOMPLETE')),
    created_at timestamptz not null default now(),
    last_activity_at timestamptz not null default now(),
    completed_at timestamptz null,
    foreign key (tenant_id, source_id) references ingestion_sources(tenant_id, source_id),
    unique (tenant_id, source_id, source_submission_id),
    unique (tenant_id, submission_id)
);
create index idx_submissions_tenant_source_status on submissions(tenant_id, source_id, status);

create table submission_expected_documents (
    expected_document_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    submission_id uuid not null,
    source_document_id varchar(200) not null,
    state varchar(20) not null check (state in ('EXPECTED','RECEIVED')),
    received_document_id uuid null,
    created_at timestamptz not null default now(),
    received_at timestamptz null,
    foreign key (tenant_id, submission_id) references submissions(tenant_id, submission_id),
    unique (tenant_id, submission_id, source_document_id)
);
create index idx_expected_documents_submission_state on submission_expected_documents(tenant_id, submission_id, state);

create table documents (
    document_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    submission_id uuid not null,
    source_id uuid not null,
    source_document_id varchar(200) null,
    producer_idempotency_key varchar(200) null,
    original_filename varchar(500) not null,
    media_type varchar(100) not null,
    content_sha256 char(64) null,
    status varchar(30) not null check (status in ('RECEIVING','RECEIVED','PROCESSING','AVAILABLE','PROCESSING_FAILED','EXPIRED_DELETED')),
    retention_days_snapshot integer null check (retention_days_snapshot is null or retention_days_snapshot > 0),
    ingested_at timestamptz not null default now(),
    expires_at timestamptz null,
    deleted_at timestamptz null,
    foreign key (tenant_id, submission_id) references submissions(tenant_id, submission_id),
    foreign key (tenant_id, source_id) references ingestion_sources(tenant_id, source_id),
    unique (tenant_id, document_id)
);
create unique index uq_document_source_identity
    on documents(tenant_id, source_id, submission_id, source_document_id)
    where source_document_id is not null;
create unique index uq_document_retry_identity
    on documents(tenant_id, source_id, submission_id, producer_idempotency_key)
    where producer_idempotency_key is not null;
create index idx_documents_submission_status on documents(tenant_id, submission_id, status);
create index idx_documents_fingerprint on documents(tenant_id, content_sha256) where content_sha256 is not null;

alter table submission_expected_documents
    add constraint fk_expected_received_document
    foreign key (tenant_id, received_document_id)
    references documents(tenant_id, document_id);

create table business_metadata_values (
    metadata_value_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    submission_id uuid not null,
    definition_id uuid not null references metadata_field_definitions(definition_id),
    field_key varchar(100) not null,
    definition_version integer not null check (definition_version > 0),
    stored_value text not null,
    normalized_value text null,
    foreign key (tenant_id, submission_id) references submissions(tenant_id, submission_id),
    unique (tenant_id, submission_id, field_key)
);
create index idx_business_metadata_lookup on business_metadata_values(tenant_id, field_key, normalized_value);

create table document_storage_refs (
    document_id uuid primary key,
    tenant_id uuid not null,
    provider_id varchar(100) not null,
    storage_volume_id uuid null,
    object_key varchar(700) not null,
    encrypted_size_bytes bigint not null check (encrypted_size_bytes >= 0),
    integrity_value varchar(300) not null,
    foreign key (tenant_id, document_id) references documents(tenant_id, document_id),
    unique (tenant_id, provider_id, object_key)
);

create table document_crypto_metadata (
    document_id uuid primary key,
    tenant_id uuid not null,
    crypto_profile varchar(100) not null,
    crypto_version integer not null check (crypto_version > 0),
    key_provider_id varchar(100) not null,
    protected_data_key_reference varchar(700) not null,
    key_provider_version varchar(100) null,
    foreign key (tenant_id, document_id) references documents(tenant_id, document_id)
);

create table audit_events (
    audit_event_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    actor_type varchar(40) not null,
    actor_id varchar(300) not null,
    action varchar(120) not null,
    target_type varchar(80) not null,
    target_id varchar(300) not null,
    occurred_at timestamptz not null default now(),
    correlation_id varchar(200) null,
    outcome varchar(20) not null check (outcome in ('SUCCESS','DENIED','FAILED')),
    safe_details jsonb not null default '{}'::jsonb
);
create index idx_audit_events_tenant_time on audit_events(tenant_id, occurred_at desc);
create index idx_audit_events_target on audit_events(tenant_id, target_type, target_id, occurred_at desc);
