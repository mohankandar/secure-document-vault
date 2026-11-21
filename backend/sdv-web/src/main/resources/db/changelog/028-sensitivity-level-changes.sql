-- Task 0029, carry-over 0.4 (owner-approved with the task): the reasons for sensitivity level changes are kept here,
-- not in the audit. IT Admins read the audit, and a reason is free text that may hold names. Stewards and Business
-- Admins read this history; each audit event of a change keeps who, when, scope, from and to, and names its row here
-- (levelChangeId). No existing row changes.
--
-- * scope DOCUMENT names the document; scope RECORD names the record as record_sensitivity_levels does: the sending
--   system and the record key, with the key's SHA-256.
-- * direction RAISED is a Steward's raise (applied at once); LOWERED is a lowering a Business Admin approved, and
--   then names the request (access_requests) it came from.
-- * from_level is null only for a record that had no level before its first raise.
-- * The person's name is filled at the time of the change from the signed-in session (as access_requests).
create table sensitivity_level_changes (
    change_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    scope text not null,
    document_id uuid null,
    source_id uuid null,
    record_key text null,
    record_key_sha256 char(64) null,
    direction text not null,
    from_level text null,
    to_level text not null,
    reason varchar(500) not null,
    changed_by varchar(300) not null,
    changed_by_display_name varchar(300) null,
    changed_at timestamptz not null,
    access_request_id uuid null references access_requests(request_id),
    constraint fk_level_changes_document foreign key (tenant_id, document_id) references documents (tenant_id, document_id),
    constraint fk_level_changes_source foreign key (tenant_id, source_id) references ingestion_sources (tenant_id, source_id),
    constraint ck_level_changes_scope check (scope in ('DOCUMENT', 'RECORD')),
    -- (Every part is written so it is true or false, never null: a check that comes out null lets the row in.)
    constraint ck_level_changes_target check (
        (scope = 'DOCUMENT' and document_id is not null
            and source_id is null and record_key is null and record_key_sha256 is null)
        or (scope = 'RECORD' and document_id is null
            and source_id is not null and record_key is not null and record_key_sha256 is not null
            and length(record_key) > 0
            and record_key_sha256 = encode(sha256(convert_to(record_key, 'UTF8')), 'hex'))),
    constraint ck_level_changes_direction check (direction in ('RAISED', 'LOWERED')),
    constraint ck_level_changes_levels check (
        to_level in ('STANDARD', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL')
        and (from_level is null or from_level in ('STANDARD', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL'))),
    -- Only a record's first raise has no "from"; a document always has a level.
    constraint ck_level_changes_from check (from_level is not null or (scope = 'RECORD' and direction = 'RAISED')),
    -- A lowering always came from an approved request; a raise never did.
    constraint ck_level_changes_request check ((direction = 'LOWERED') = (access_request_id is not null)),
    constraint ck_level_changes_reason check (char_length(btrim(reason)) between 3 and 500 and reason = btrim(reason)),
    constraint ck_level_changes_changed_by check (length(changed_by) > 0)
);
-- A document's history, and a record's history, newest first.
create index idx_level_changes_document on sensitivity_level_changes(tenant_id, document_id, changed_at desc)
    where document_id is not null;
create index idx_level_changes_record on sensitivity_level_changes(tenant_id, source_id, record_key_sha256, changed_at desc)
    where scope = 'RECORD';
