-- Task 0031 (owner-approved with the task): exact duplicates. When the same file (same SHA-256 checksum) arrives again
-- from the same sending system for the same record in a new delivery, Vero keeps no second copy. It answers with the
-- document it already has and records the arrival here, one row per duplicate delivery. One new table; no existing
-- table or row changes.
--
-- * The delivery is named as the sender named it: source_document_id, or producer_idempotency_key when there was none
--   (the same rule as documents). A retry of the same delivery finds its row and gets the same answer again.
-- * existing_document_id is the document Vero already had; content_sha256 and content_size_bytes are the arrival's
--   (the same as that document's when it arrived).
-- * sent_level is the document level the duplicate carried. level_outcome RAISED: it was higher, and the existing
--   document was raised to it; KEPT: it was lower or equal, and was ignored.
-- * A manifest slot the duplicate fills is marked RECEIVED with no received document (the existing document belongs to
--   another submission); this row names the slot (submission_id, source_document_id) and the document it points at.
-- * The Daily intake report counts these rows per day and sending system, and lists them in its CSV.
create table document_duplicate_receipts (
    duplicate_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    source_id uuid not null,
    submission_id uuid not null,
    source_document_id varchar(200) null,
    producer_idempotency_key varchar(200) null,
    existing_document_id uuid not null,
    content_sha256 char(64) not null,
    content_size_bytes bigint not null,
    sent_level text not null,
    level_outcome text not null,
    received_at timestamptz not null,
    constraint fk_duplicate_receipts_submission foreign key (tenant_id, submission_id, source_id)
        references submissions (tenant_id, submission_id, source_id),
    constraint fk_duplicate_receipts_document foreign key (tenant_id, existing_document_id)
        references documents (tenant_id, document_id),
    -- Exactly one of the two names the delivery (true or false, never null).
    constraint ck_duplicate_receipts_identity check ((source_document_id is null) <> (producer_idempotency_key is null)),
    constraint ck_duplicate_receipts_identity_text check (
        coalesce(length(btrim(source_document_id)), 1) > 0 and coalesce(length(btrim(producer_idempotency_key)), 1) > 0),
    constraint ck_duplicate_receipts_sha check (content_sha256 ~ '^[0-9a-f]{64}$'),
    constraint ck_duplicate_receipts_size check (content_size_bytes > 0),
    constraint ck_duplicate_receipts_level check (sent_level in ('STANDARD', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL')),
    constraint ck_duplicate_receipts_outcome check (level_outcome in ('RAISED', 'KEPT'))
);
-- One row per delivery: a retry of the same delivery is the same row.
create unique index uq_duplicate_receipts_source_document
    on document_duplicate_receipts(tenant_id, submission_id, source_document_id) where source_document_id is not null;
create unique index uq_duplicate_receipts_idempotency
    on document_duplicate_receipts(tenant_id, submission_id, producer_idempotency_key) where producer_idempotency_key is not null;
-- The Daily intake report: a tenant's duplicates in a time range, per sending system.
create index idx_duplicate_receipts_received on document_duplicate_receipts(tenant_id, received_at, source_id);
-- Received counts of a submission ("2 of 4 received") add its duplicates.
create index idx_duplicate_receipts_submission on document_duplicate_receipts(tenant_id, submission_id);
-- (Finding duplicates uses the existing idx_documents_fingerprint on documents(tenant_id, content_sha256).)
