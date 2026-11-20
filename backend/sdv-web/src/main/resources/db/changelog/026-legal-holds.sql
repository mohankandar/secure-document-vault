-- Task 0027 (R2): legal holds, V1-lite. Owner-approved with the task. One new table; no existing table or row changes.
-- A document with an active hold (released_at is null) is never deleted by the retention cleanup. Released holds stay
-- as history; at most one active hold per document. Every place and release is also in audit_events.
create table legal_holds (
    hold_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    document_id uuid not null,
    reason varchar(500) not null,
    placed_by varchar(300) not null,
    placed_at timestamptz not null,
    released_by varchar(300) null,
    released_at timestamptz null,
    constraint fk_legal_holds_document foreign key (tenant_id, document_id) references documents (tenant_id, document_id),
    -- 3 to 500 characters, not counting spaces at either end (the service trims before saving).
    constraint ck_legal_holds_reason check (char_length(btrim(reason)) between 3 and 500 and reason = btrim(reason)),
    constraint ck_legal_holds_placed_by check (length(placed_by) > 0),
    -- Released means both who and when; neither before the hold was placed.
    constraint ck_legal_holds_release check ((released_by is null) = (released_at is null)),
    constraint ck_legal_holds_released_by check (released_by is null or length(released_by) > 0),
    constraint ck_legal_holds_times check (released_at is null or released_at >= placed_at)
);
-- One active hold per document; also what the retention cleanup looks up for each document.
create unique index uq_legal_holds_active on legal_holds(tenant_id, document_id) where released_at is null;
-- The Business Admin's list of holds in place, newest first.
create index idx_legal_holds_tenant_active on legal_holds(tenant_id, placed_at desc) where released_at is null;
