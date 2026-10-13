-- SDV Controlled Document Actions Checkpoint B (Units 17-18)
-- Unit 17 adds no schema. Unit 18 adds crash-resumable automatic retention deletion state/evidence.

alter table documents drop constraint documents_status_check;

alter table documents add constraint documents_status_check
    check (status in (
        'RECEIVING','RECEIVED','PROCESSING','AVAILABLE',
        'REDACTION_PROCESSING','RETENTION_DELETING','PROCESSING_FAILED','EXPIRED_DELETED'));

alter table documents
    add column retention_delete_started_at timestamptz null;

alter table documents
    add column deletion_reason varchar(100) null;

alter table documents
    add constraint ck_documents_expired_deleted_tombstone
    check (
        status <> 'EXPIRED_DELETED'
        or (deleted_at is not null and deletion_reason is not null)
    );

create index idx_documents_retention_due
    on documents(expires_at, tenant_id, document_id)
    where expires_at is not null
      and status in ('RECEIVED','PROCESSING','AVAILABLE','PROCESSING_FAILED','RETENTION_DELETING');
