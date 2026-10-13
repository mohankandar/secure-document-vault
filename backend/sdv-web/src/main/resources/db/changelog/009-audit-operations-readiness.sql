-- SDV Enterprise / Operational Readiness Checkpoint A (Units 19-20)
-- Query/monitoring indexes only. Audit remains append-oriented in application code; no product update/delete API exists.

create index idx_audit_events_tenant_action_time
    on audit_events(tenant_id, action, occurred_at desc, audit_event_id desc);

create index idx_audit_events_tenant_outcome_time
    on audit_events(tenant_id, outcome, occurred_at desc, audit_event_id desc);

create index idx_documents_operations_status_time
    on documents(tenant_id, status, ingested_at, document_id);

create index idx_submissions_operations_status_activity
    on submissions(tenant_id, status, last_activity_at, submission_id);
