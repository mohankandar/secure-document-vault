-- 018: indexes for date-only searches and for Recently viewed (task 0005, Part C).
-- Built CONCURRENTLY so writes to these tables are not blocked while each index builds; this cannot run inside a
-- transaction, so the changeset sets runInTransaction: false. No IF NOT EXISTS on purpose: if a concurrent build is
-- interrupted it leaves an INVALID index, and a rerun must then fail loudly instead of silently keeping it
-- (see docs/work/0005-report.md for the recovery steps).

-- Search, Received range: submissions.created_at within one tenant and source, newest first.
create index concurrently idx_submissions_tenant_source_created
    on submissions (tenant_id, source_id, created_at desc, submission_id);

-- Search, Last updated range: submissions.last_activity_at within one tenant and source.
create index concurrently idx_submissions_tenant_source_activity
    on submissions (tenant_id, source_id, last_activity_at desc, submission_id);

-- Recently viewed: one person's own viewer opens in their tenant, newest first.
create index concurrently idx_audit_events_tenant_actor_action_time
    on audit_events (tenant_id, actor_id, action, occurred_at desc);
