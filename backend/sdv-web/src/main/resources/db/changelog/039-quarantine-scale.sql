-- Task 0052 (owner-approved with the task): quarantine at a million documents.
-- 1. Each row keeps the document's level when it was quarantined and its received time, so the lists can page
--    through an incident in order (oldest first) and the counts can be kept by level.
-- 2. The incident keeps its counts (total, released, by level, by level in force, first and last received), updated
--    by a trigger in the same statement as every row written or released, so no screen reads the whole incident.
-- 3. The incident keeps the approved release being carried out, so the whole release runs in batches in the
--    background and continues after a restart.
-- Rows written before this changeset get their received time here; their level is filled in by the background job
-- (the level depends on the record's restriction, which the application works out). Until then they count as a
-- level nobody below the highest clearance can open.

alter table quarantined_documents
    add column level text null,
    add column received_at timestamptz null;
alter table quarantined_documents add constraint ck_quarantined_documents_level
    check (level is null or level in ('STANDARD', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL'));

update quarantined_documents q set received_at = d.ingested_at
  from documents d where d.tenant_id = q.tenant_id and d.document_id = q.document_id;
alter table quarantined_documents alter column received_at set not null;

alter table quarantine_incidents
    add column document_count bigint not null default 0,
    add column released_count bigint not null default 0,
    add column standard_count bigint not null default 0,
    add column confidential_count bigint not null default 0,
    add column highly_confidential_count bigint not null default 0,
    add column standard_in_force bigint not null default 0,
    add column confidential_in_force bigint not null default 0,
    add column highly_confidential_in_force bigint not null default 0,
    add column first_received_at timestamptz null,
    add column last_received_at timestamptz null,
    add column release_request_id uuid null;

-- The counts of the rows already there (levels not known yet: counted in the total only).
update quarantine_incidents qi
   set document_count = n.total, released_count = n.released,
       first_received_at = n.first_received, last_received_at = n.last_received
  from (select tenant_id, incident_id, count(*) as total, count(*) filter (where released_at is not null) as released,
               min(received_at) as first_received, max(received_at) as last_received
          from quarantined_documents group by tenant_id, incident_id) n
 where qi.tenant_id = n.tenant_id and qi.incident_id = n.incident_id;

alter table quarantine_incidents add constraint ck_quarantine_incidents_counts check (
    document_count >= 0 and released_count between 0 and document_count
    and standard_count >= 0 and confidential_count >= 0 and highly_confidential_count >= 0
    and standard_count + confidential_count + highly_confidential_count <= document_count
    and standard_in_force between 0 and standard_count
    and confidential_in_force between 0 and confidential_count
    and highly_confidential_in_force between 0 and highly_confidential_count);
alter table quarantine_incidents add constraint fk_quarantine_incidents_release_request
    foreign key (release_request_id) references quarantine_release_requests (request_id);

-- The review list, the exposure view and the export page through an incident: in force first, then released, each
-- oldest first (quarantined, then received, then document).
create index idx_quarantined_documents_page_in_force
    on quarantined_documents(tenant_id, incident_id, quarantined_at, received_at, document_id) where released_at is null;
create index idx_quarantined_documents_page_released
    on quarantined_documents(tenant_id, incident_id, quarantined_at, received_at, document_id) where released_at is not null;
-- A page's exposure: each document's first quarantine in the incident.
create index idx_quarantined_documents_document on quarantined_documents(tenant_id, document_id, quarantined_at);
-- Rows written before this changeset, still without a level (what the background job fills in).
create index idx_quarantined_documents_level_missing on quarantined_documents(quarantine_id) where level is null;
-- Whole releases being carried out: what the background job looks for.
create index idx_quarantine_incidents_releasing on quarantine_incidents(created_at)
    where release_request_id is not null and status = 'OPEN';
