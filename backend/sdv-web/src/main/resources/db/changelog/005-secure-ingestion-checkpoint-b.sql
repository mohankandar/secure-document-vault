-- SDV Secure Ingestion Checkpoint B (Units 6, 9, 10)
-- Durable document receipt metadata, processing state evidence, and stronger receipt integrity.

alter table documents
    add column content_size_bytes bigint null;

alter table documents
    add constraint ck_document_content_size_bytes
    check (content_size_bytes is null or content_size_bytes >= 0);

alter table documents
    add column available_at timestamptz null;

alter table documents
    add column processing_attempt_count integer not null default 0;

alter table documents
    add constraint ck_document_processing_attempt_count
    check (processing_attempt_count >= 0);

alter table documents
    add column processing_error_code varchar(100) null;

alter table documents
    add constraint ck_document_content_sha256
    check (content_sha256 is null or content_sha256 ~ '^[0-9a-f]{64}$');

alter table documents
    add constraint ck_document_receipt_identity
    check (source_document_id is not null or producer_idempotency_key is not null);

alter table documents
    add constraint ck_document_received_content_metadata
    check (
        status = 'RECEIVING'
        or (
            content_sha256 is not null
            and content_size_bytes is not null
        )
    );

alter table document_storage_refs
    alter column storage_volume_id set not null;

alter table document_storage_refs
    add constraint ck_document_storage_integrity_sha256
    check (integrity_value ~ '^[0-9a-f]{64}$');

create index idx_documents_submission_ingested
    on documents(tenant_id, submission_id, ingested_at, document_id);

create index idx_documents_source_content
    on documents(tenant_id, source_id, content_sha256)
    where content_sha256 is not null;
