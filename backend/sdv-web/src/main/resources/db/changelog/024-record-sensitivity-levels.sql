-- Task 0024, part 2: a sensitivity level for a whole record (a contract, a patient file), set by the sending system on
-- the submission. A record is the group of a sending system's submissions that share their identifier values (the
-- same grouping as record search and record detail); a submission without identifier values is a record on its own.
-- record_key is that grouping as one canonical text value (see RecordKey in sdv-application). Uniqueness is on the
-- key's SHA-256 so a long key can never exceed the index row limit; the check keeps the two in step.
-- A document's effective level is the higher of its own level and its record's level; nothing is copied onto documents.
-- History (set, raised, kept) is in audit_events. No existing row changes; the table starts empty.
create table if not exists record_sensitivity_levels (
    record_level_id uuid primary key,
    tenant_id uuid not null,
    source_id uuid not null,
    record_key text not null,
    record_key_sha256 char(64) not null,
    sensitivity_level text not null,
    reason text null,
    -- SOURCE for a level from a delivery, otherwise the person who set it.
    set_by varchar(300) not null,
    set_at timestamptz not null,
    updated_at timestamptz not null,
    constraint ck_record_sensitivity_levels_level
        check (sensitivity_level in ('STANDARD', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL')),
    constraint ck_record_sensitivity_levels_key check (length(record_key) > 0),
    constraint ck_record_sensitivity_levels_key_sha256
        check (record_key_sha256 = encode(sha256(convert_to(record_key, 'UTF8')), 'hex')),
    constraint ck_record_sensitivity_levels_set_by check (length(set_by) > 0),
    constraint ck_record_sensitivity_levels_times check (updated_at >= set_at),
    constraint uq_record_sensitivity_levels_record unique (tenant_id, source_id, record_key_sha256),
    constraint fk_record_sensitivity_levels_source
        foreign key (tenant_id, source_id) references ingestion_sources (tenant_id, source_id)
);
