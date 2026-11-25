-- Task 0078 (owner-approved with the task): records, and the end of Vero's own field tables. A record is a row; a submission points to
-- its record, and a level, a request and a level change name the record by its ID. Field values and definitions are platform-core's
-- (fld_ tables, made by the same schema step). Values of before are not moved (nothing is in production): a database that holds some is
-- refused here, so that nothing is lost by mistake. Make a new database beside it.
do $$
begin
    if exists (select 1 from business_metadata_values) then
        raise exception 'This database holds field values of an earlier Vero, which this version does not move. Nothing was changed. Make a new database beside this one for this version.';
    end if;
    if to_regclass('fld_field_definition') is null and exists (select 1 from metadata_field_definitions) then
        raise exception 'This database holds field definitions of an earlier Vero, which this version does not move. Nothing was changed. Make a new database beside this one for this version.';
    end if;
end
$$;

create table records (
    record_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    source_id uuid not null,
    created_at timestamptz not null default now(),
    constraint fk_records_source foreign key (tenant_id, source_id) references ingestion_sources (tenant_id, source_id),
    constraint uq_records_scope unique (tenant_id, source_id, record_id)
);

-- A submission is a record on its own until intake finds the record its identifiers name.
insert into records(record_id, tenant_id, source_id, created_at) select submission_id, tenant_id, source_id, created_at from submissions;
alter table submissions add column record_id uuid null;
update submissions set record_id = submission_id;
alter table submissions alter column record_id set not null;
alter table submissions add constraint fk_submissions_record
    foreign key (tenant_id, source_id, record_id) references records (tenant_id, source_id, record_id);
create index idx_submissions_record on submissions(tenant_id, source_id, record_id, created_at desc, submission_id);

create function submission_own_record() returns trigger language plpgsql as $$
begin
    if new.record_id is null then
        insert into records(record_id, tenant_id, source_id, created_at) values (new.submission_id, new.tenant_id, new.source_id, new.created_at)
            on conflict (record_id) do nothing;
        new.record_id := new.submission_id;
    end if;
    return new;
end;
$$;
create trigger trg_submission_own_record before insert on submissions for each row execute function submission_own_record();

-- The old key of a record: "submission:<id>" is that submission's record. Another key has no submission left to name it (no values of
-- before are here), so it gets a record of its own: the level, request or change is kept.
create table old_record_keys as
    select tenant_id, source_id, record_key_sha256,
           case when record_key ~ '^submission:[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' then substr(record_key, 12)::uuid end as submission_id,
           gen_random_uuid() as new_id
      from (select tenant_id, source_id, record_key_sha256, record_key from record_sensitivity_levels
            union select tenant_id, source_id, record_key_sha256, record_key from access_requests where record_key is not null
            union select tenant_id, source_id, record_key_sha256, record_key from sensitivity_level_changes where record_key is not null) k;
update old_record_keys k set new_id = s.submission_id
  from submissions s where s.tenant_id = k.tenant_id and s.source_id = k.source_id and s.submission_id = k.submission_id;
insert into records(record_id, tenant_id, source_id) select new_id, tenant_id, source_id from old_record_keys on conflict (record_id) do nothing;

alter table record_sensitivity_levels add column record_id uuid null;
update record_sensitivity_levels t set record_id = k.new_id from old_record_keys k
 where k.tenant_id = t.tenant_id and k.source_id = t.source_id and k.record_key_sha256 = t.record_key_sha256;
alter table record_sensitivity_levels alter column record_id set not null;
alter table record_sensitivity_levels drop column record_key;
alter table record_sensitivity_levels drop column record_key_sha256;
alter table record_sensitivity_levels add constraint uq_record_levels_record unique (tenant_id, source_id, record_id);
alter table record_sensitivity_levels add constraint fk_record_levels_record
    foreign key (tenant_id, source_id, record_id) references records (tenant_id, source_id, record_id);

alter table access_requests add column record_id uuid null;
update access_requests t set record_id = k.new_id from old_record_keys k
 where k.tenant_id = t.tenant_id and k.source_id = t.source_id and k.record_key_sha256 = t.record_key_sha256;
alter table access_requests drop constraint ck_access_requests_target;
drop index uq_access_requests_pending_record;
alter table access_requests drop column record_key;
alter table access_requests drop column record_key_sha256;
alter table access_requests add constraint ck_access_requests_target check (
    (kind <> 'LOWER_RECORD_LEVEL' and document_id is not null and source_id is null and record_id is null)
    or (kind = 'LOWER_RECORD_LEVEL' and document_id is null and source_id is not null and record_id is not null));
alter table access_requests add constraint fk_access_requests_record
    foreign key (tenant_id, source_id, record_id) references records (tenant_id, source_id, record_id);
create unique index uq_access_requests_pending_record on access_requests(tenant_id, requested_by, source_id, record_id)
    where status = 'PENDING' and kind = 'LOWER_RECORD_LEVEL';

alter table sensitivity_level_changes add column record_id uuid null;
update sensitivity_level_changes t set record_id = k.new_id from old_record_keys k
 where k.tenant_id = t.tenant_id and k.source_id = t.source_id and k.record_key_sha256 = t.record_key_sha256;
alter table sensitivity_level_changes drop constraint ck_level_changes_target;
drop index idx_level_changes_record;
alter table sensitivity_level_changes drop column record_key;
alter table sensitivity_level_changes drop column record_key_sha256;
alter table sensitivity_level_changes add constraint ck_level_changes_target check (
    (scope = 'DOCUMENT' and document_id is not null and source_id is null and record_id is null)
    or (scope = 'RECORD' and document_id is null and source_id is not null and record_id is not null));
alter table sensitivity_level_changes add constraint fk_level_changes_record
    foreign key (tenant_id, source_id, record_id) references records (tenant_id, source_id, record_id);
create index idx_level_changes_record on sensitivity_level_changes(tenant_id, source_id, record_id, changed_at desc)
    where scope = 'RECORD';

drop table old_record_keys;
drop table record_title_fields;
drop table metadata_field_display_order;
drop table business_metadata_values;
drop table metadata_field_definitions;
