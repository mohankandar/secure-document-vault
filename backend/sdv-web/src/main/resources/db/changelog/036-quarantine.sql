-- Task 0048 (owner-approved with the task): quarantine. Three new tables; no existing table or row changes.
-- While a document is quarantined nobody opens, prints or downloads it except its reviewers, and the retention cleanup
-- leaves it (as for a legal hold). Reasons are kept here, never copied into audit_events.

-- An incident: every quarantine belongs to one (a single document is an incident of one).
create table quarantine_incidents (
    incident_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    name varchar(200) not null,
    reason varchar(500) not null,
    scope text not null,
    created_by varchar(300) not null,
    created_by_display_name varchar(300) null,
    created_at timestamptz not null,
    status text not null,
    closed_at timestamptz null,
    constraint uq_quarantine_incidents_tenant unique (tenant_id, incident_id),
    constraint ck_quarantine_incidents_name check (char_length(btrim(name)) between 1 and 200 and name = btrim(name)),
    constraint ck_quarantine_incidents_reason check (char_length(btrim(reason)) between 3 and 500 and reason = btrim(reason)),
    constraint ck_quarantine_incidents_scope check (scope in ('DOCUMENT', 'FILTER')),
    constraint ck_quarantine_incidents_created_by check (length(created_by) > 0),
    constraint ck_quarantine_incidents_status check (status in ('OPEN', 'RELEASED')),
    constraint ck_quarantine_incidents_closed check ((status = 'RELEASED') = (closed_at is not null))
);
create index idx_quarantine_incidents_tenant on quarantine_incidents(tenant_id, created_at desc);

-- A document in an incident. At most one quarantine in force per document; released ones stay as history.
create table quarantined_documents (
    quarantine_id uuid primary key,
    tenant_id uuid not null,
    incident_id uuid not null,
    document_id uuid not null,
    quarantined_by varchar(300) not null,
    quarantined_by_display_name varchar(300) null,
    quarantined_at timestamptz not null,
    released_by varchar(300) null,
    released_by_display_name varchar(300) null,
    released_at timestamptz null,
    release_reason varchar(500) null,
    release_request_id uuid null,
    constraint fk_quarantined_documents_document foreign key (tenant_id, document_id) references documents (tenant_id, document_id),
    constraint fk_quarantined_documents_incident foreign key (tenant_id, incident_id) references quarantine_incidents (tenant_id, incident_id),
    constraint ck_quarantined_documents_by check (length(quarantined_by) > 0),
    constraint ck_quarantined_documents_release check ((released_by is null) = (released_at is null)
        and (released_at is null or release_reason is not null)),
    constraint ck_quarantined_documents_release_reason check (release_reason is null
        or (char_length(btrim(release_reason)) between 3 and 500 and release_reason = btrim(release_reason))),
    constraint ck_quarantined_documents_times check (released_at is null or released_at >= quarantined_at)
);
-- One quarantine in force per document; also what every document path and the retention cleanup look up.
create unique index uq_quarantined_documents_active on quarantined_documents(tenant_id, document_id) where released_at is null;
create index idx_quarantined_documents_incident on quarantined_documents(tenant_id, incident_id, quarantined_at);

-- Releasing a whole incident: asked by a Steward or Restricted Steward, decided by a Business Admin (Approvals).
create table quarantine_release_requests (
    request_id uuid primary key,
    tenant_id uuid not null,
    incident_id uuid not null,
    reason varchar(500) not null,
    requested_by varchar(300) not null,
    requested_by_display_name varchar(300) null,
    requested_at timestamptz not null,
    status text not null,
    decided_by varchar(300) null,
    decided_by_display_name varchar(300) null,
    decided_at timestamptz null,
    expires_at timestamptz not null,
    constraint fk_quarantine_release_requests_incident foreign key (tenant_id, incident_id) references quarantine_incidents (tenant_id, incident_id),
    constraint ck_quarantine_release_requests_reason check (char_length(btrim(reason)) between 3 and 500 and reason = btrim(reason)),
    constraint ck_quarantine_release_requests_status check (status in ('PENDING', 'APPROVED', 'DECLINED', 'EXPIRED')),
    constraint ck_quarantine_release_requests_decided check ((status in ('APPROVED', 'DECLINED')) = (decided_at is not null)
        and (decided_by is null) = (decided_at is null)),
    -- Nobody approves their own request.
    constraint ck_quarantine_release_requests_not_own check (decided_by is null or decided_by <> requested_by),
    constraint ck_quarantine_release_requests_times check (expires_at > requested_at
        and (decided_at is null or decided_at >= requested_at))
);
-- One request waiting per incident.
create unique index uq_quarantine_release_requests_pending on quarantine_release_requests(tenant_id, incident_id) where status = 'PENDING';
create index idx_quarantine_release_requests_tenant on quarantine_release_requests(tenant_id, status, requested_at);
