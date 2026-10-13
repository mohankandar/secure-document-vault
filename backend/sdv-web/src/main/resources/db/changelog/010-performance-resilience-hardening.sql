-- SDV Unit 24 - Security, Performance and Resilience Hardening
-- Adds only recovery-lease state/indexing for stale corrective-redaction reconciliation.

alter table document_redaction_operations
    add column recovery_claimed_at timestamptz null,
    add column recovery_claim_token uuid null;

alter table document_redaction_operations
    add constraint ck_document_redaction_recovery_claim_pair
    check ((recovery_claimed_at is null and recovery_claim_token is null)
        or (recovery_claimed_at is not null and recovery_claim_token is not null));

create index idx_document_redaction_recovery_due
    on document_redaction_operations(created_at, recovery_claimed_at, operation_id);

create index idx_documents_retention_transition_started
    on documents(tenant_id, retention_delete_started_at, document_id)
    where status='RETENTION_DELETING';
