-- Task 0028 (S5 + S6): level changes and approvals. Owner-approved with the task. No existing row changes.
--
-- 1. Carry-over 0.3: who placed and who released a legal hold, by name, filled at the time of the action from the
--    signed-in session (as retention_policy_versions.created_by_display_name). Nullable and without a default:
--    holds placed before this change keep no name, and screens fall back to "a Business admin".
alter table legal_holds add column if not exists placed_by_display_name varchar(300) null;
alter table legal_holds add column if not exists released_by_display_name varchar(300) null;

-- 2. Access requests: a Restricted Steward's request to print or download one Highly Confidential document once, and
--    a Steward's request to lower a document's or a record's sensitivity level. A Business Admin approves or declines.
--    * document_id is set for the document kinds; a record (LOWER_RECORD_LEVEL) is named as record_sensitivity_levels
--      names it: the sending system and the record key (see RecordKey in sdv-application), with the key's SHA-256.
--    * expires_at: while PENDING, when the request expires undecided (7 days by default); once a PRINT or DOWNLOAD
--      request is APPROVED, when its one-use grant ends (24 hours after approval by default).
--    * Nobody decides their own request: decided_by can never equal requested_by.
--    * The requester's and decider's names are filled at the time of the action, like the holds' names above.
create table access_requests (
    request_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    kind text not null,
    document_id uuid null,
    source_id uuid null,
    record_key text null,
    record_key_sha256 char(64) null,
    requested_level text null,
    reason varchar(500) not null,
    requested_by varchar(300) not null,
    requested_by_display_name varchar(300) null,
    requested_at timestamptz not null,
    status text not null,
    decided_by varchar(300) null,
    decided_by_display_name varchar(300) null,
    decided_at timestamptz null,
    expires_at timestamptz not null,
    used_at timestamptz null,
    constraint fk_access_requests_document foreign key (tenant_id, document_id) references documents (tenant_id, document_id),
    constraint fk_access_requests_source foreign key (tenant_id, source_id) references ingestion_sources (tenant_id, source_id),
    constraint ck_access_requests_kind check (kind in ('PRINT', 'DOWNLOAD', 'LOWER_LEVEL', 'LOWER_RECORD_LEVEL')),
    constraint ck_access_requests_status check (status in ('PENDING', 'APPROVED', 'DECLINED', 'USED', 'EXPIRED')),
    -- A document request names a document and no record; a record request names a record and no document.
    -- (Every part is written so it is true or false, never null: a check that comes out null lets the row in.)
    constraint ck_access_requests_target check (
        (kind <> 'LOWER_RECORD_LEVEL' and document_id is not null
            and source_id is null and record_key is null and record_key_sha256 is null)
        or (kind = 'LOWER_RECORD_LEVEL' and document_id is null
            and source_id is not null and record_key is not null and record_key_sha256 is not null
            and length(record_key) > 0
            and record_key_sha256 = encode(sha256(convert_to(record_key, 'UTF8')), 'hex'))),
    -- Only a lowering names a level, and then always one of the three.
    constraint ck_access_requests_requested_level check (
        (kind in ('LOWER_LEVEL', 'LOWER_RECORD_LEVEL') and requested_level is not null
            and requested_level in ('STANDARD', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL'))
        or (kind in ('PRINT', 'DOWNLOAD') and requested_level is null)),
    -- 3 to 500 characters, not counting spaces at either end (the service trims before saving).
    constraint ck_access_requests_reason check (char_length(btrim(reason)) between 3 and 500 and reason = btrim(reason)),
    constraint ck_access_requests_requested_by check (length(requested_by) > 0),
    -- Decided means both who and when, never before the request; nobody decides their own request.
    constraint ck_access_requests_decision check ((decided_by is null) = (decided_at is null)),
    constraint ck_access_requests_decided_status check (
        (status in ('APPROVED', 'DECLINED', 'USED')) = (decided_at is not null)
        or (status = 'EXPIRED')),
    constraint ck_access_requests_not_own check (decided_by is null or decided_by <> requested_by),
    constraint ck_access_requests_decided_after check (decided_at is null or decided_at >= requested_at),
    -- Used only once, only a print or download grant, only after its approval.
    constraint ck_access_requests_used check ((status = 'USED') = (used_at is not null)),
    constraint ck_access_requests_used_kind check (used_at is null or kind in ('PRINT', 'DOWNLOAD')),
    constraint ck_access_requests_used_after check (used_at is null or (decided_at is not null and used_at >= decided_at)),
    constraint ck_access_requests_expires check (expires_at > requested_at)
);
-- The Business Admin's pending list, oldest first; also what the pending expiry looks for.
create index idx_access_requests_pending on access_requests(tenant_id, requested_at) where status = 'PENDING';
-- A print or download grant for one person, one document and one kind.
create index idx_access_requests_grants on access_requests(tenant_id, document_id, requested_by, kind) where status = 'APPROVED';
-- The requester's own requests, newest first.
create index idx_access_requests_requester on access_requests(tenant_id, requested_by, requested_at desc);
-- At most one pending request per person for the same document and kind, or the same record.
create unique index uq_access_requests_pending_document on access_requests(tenant_id, requested_by, kind, document_id)
    where status = 'PENDING' and document_id is not null;
create unique index uq_access_requests_pending_record on access_requests(tenant_id, requested_by, source_id, record_key_sha256)
    where status = 'PENDING' and kind = 'LOWER_RECORD_LEVEL';
