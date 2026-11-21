-- 031 (task 0032, owner-approved with the task): indexes for the Daily intake report and for the uncorrected-copy check.
-- Indexes only; no table, column or row changes. Built CONCURRENTLY, as 018, so intake is not blocked while each one
-- builds; this cannot run inside a transaction, so the changeset sets runInTransaction: false. No IF NOT EXISTS on
-- purpose: an interrupted concurrent build leaves an INVALID index, and a rerun must then fail loudly (recovery: drop
-- the invalid index and run the schema step again; see docs/work/0005-report.md).

-- The report: a tenant's deliveries created in a time range, from every sending system (the existing
-- idx_submissions_tenant_source_created needs one sending system).
create index concurrently idx_submissions_tenant_created
    on submissions (tenant_id, created_at, submission_id);

-- The report: a tenant's documents received in a time range, from every sending system.
create index concurrently idx_documents_tenant_ingested
    on documents (tenant_id, ingested_at, document_id);

-- Section 0 of task 0032: a corrected document sent again. The checksum a document had before a permanent corrective
-- redaction is kept only in that redaction's audit event (DOCUMENT_PERMANENTLY_REDACTED, safe detail
-- oldContentSha256); intake looks an arriving checksum up there.
create index concurrently idx_audit_events_redaction_old_sha
    on audit_events (tenant_id, (safe_details ->> 'oldContentSha256'))
    where action = 'DOCUMENT_PERMANENTLY_REDACTED';
