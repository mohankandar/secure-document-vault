-- Task 0051 (owner-approved with the task): quarantine by filter without a limit. The incident keeps its filter and a
-- cut-off; from the moment it is confirmed, every document matching the filter and received up to the cut-off is
-- quarantined, before its own row exists. A background job then writes the per-document rows in batches, keeping its
-- place here (cursor), so it continues after a restart. No existing row changes: incidents made before this changeset
-- have no filter and are not blocking by filter (their rows already exist).

alter table quarantine_incidents
    add column filter_received_from timestamptz null,
    add column filter_received_to timestamptz null,
    add column filter_source_id uuid null,
    add column filter_fields jsonb null,
    add column cutoff_received_at timestamptz null,
    add column expected_count bigint null,
    add column prepared_count bigint not null default 0,
    add column prepared_at timestamptz null,
    add column cursor_received_at timestamptz null,
    add column cursor_document_id uuid null;

-- The filter is all there or not at all, only on a filter incident.
alter table quarantine_incidents add constraint ck_quarantine_incidents_filter check (
    (filter_received_from is null) = (filter_received_to is null)
    and (filter_received_from is null) = (filter_fields is null)
    and (filter_received_from is null) = (cutoff_received_at is null)
    and (filter_received_from is null) = (expected_count is null)
    and (filter_received_from is null or (scope = 'FILTER' and filter_received_to > filter_received_from
        and jsonb_typeof(filter_fields) = 'object' and expected_count >= 1)));
alter table quarantine_incidents add constraint ck_quarantine_incidents_prepared check (
    prepared_count >= 0
    and (prepared_at is null or filter_received_from is not null)
    and (cursor_received_at is null) = (cursor_document_id is null));

-- Open filter incidents: what every document path checks (few rows).
create index idx_quarantine_incidents_filter_open on quarantine_incidents(tenant_id)
    where status = 'OPEN' and filter_received_from is not null;
-- Filter incidents still being prepared: what the background job looks for.
create index idx_quarantine_incidents_preparing on quarantine_incidents(created_at)
    where filter_received_from is not null and prepared_at is null;
-- A release after an incident's confirmation frees the document from that incident's filter.
create index idx_quarantined_documents_released on quarantined_documents(tenant_id, document_id, released_at)
    where released_at is not null;
