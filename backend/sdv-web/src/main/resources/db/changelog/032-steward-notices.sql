-- Task 0033, section 0.1 (owner-approved with the task: one small table). Notices for the record's Document stewards
-- and Restricted stewards, kept in the database rather than in each browser, because an uncorrected copy of a
-- corrected document is a security event. Each steward sees a notice until they mark it read. No existing table or row
-- changes.
--
-- * kind: what happened. UNCORRECTED_COPY_RECEIVED is the only kind (task 0032): a sending system sent the file a
--   document had before a corrective redaction; nothing was stored.
-- * source_id and the record identifier name the record as the notice shows it: the sending system and the record's
--   primary identifier (its value as stored, and the field's display name). Never a file name, title or content.
-- * audit_event_id: the DOCUMENT_UNCORRECTED_COPY_RECEIVED event the notice was made from; one notice per event.
-- * read_by: who has marked it read, and when, one entry per person: {"<subject>": "<UTC time>"}. Everyone with the
--   steward roles sees a notice until their own entry is there; stewards come from the sign-in system, so there is no
--   list of them to write rows for in advance.
create table steward_notices (
    notice_id uuid primary key,
    tenant_id uuid not null references tenants(tenant_id),
    kind text not null,
    source_id uuid null,
    record_identifier text null,
    record_identifier_label text null,
    audit_event_id uuid not null references audit_events(audit_event_id),
    created_at timestamptz not null,
    read_by jsonb not null default '{}'::jsonb,
    constraint fk_steward_notices_source foreign key (tenant_id, source_id) references ingestion_sources (tenant_id, source_id),
    constraint ck_steward_notices_kind check (kind in ('UNCORRECTED_COPY_RECEIVED')),
    constraint ck_steward_notices_identifier check (record_identifier is null or length(btrim(record_identifier)) > 0),
    constraint ck_steward_notices_read_by check (jsonb_typeof(read_by) = 'object'),
    constraint uq_steward_notices_event unique (audit_event_id)
);
-- A tenant's notices, newest first (each steward's unread ones are read from here).
create index idx_steward_notices_tenant_created on steward_notices(tenant_id, created_at desc, notice_id);

-- Uncorrected copies received before this change (task 0032 kept them in the audit only) become notices too, unread by
-- everyone: they were shown once per browser before, and a security event should not be lost.
insert into steward_notices(notice_id, tenant_id, kind, source_id, record_identifier, record_identifier_label,
                            audit_event_id, created_at)
select a.audit_event_id, a.tenant_id, 'UNCORRECTED_COPY_RECEIVED', src.source_id,
       nullif(btrim(pid.stored_value), ''), pid.display_label, a.audit_event_id, a.occurred_at
  from audit_events a
  left join ingestion_sources src
    on src.tenant_id = a.tenant_id and src.source_id::text = a.safe_details ->> 'sourceId'
  left join submissions s
    on s.tenant_id = a.tenant_id and s.submission_id::text = a.safe_details ->> 'submissionId'
  left join lateral (
      select v.stored_value, f.display_label
        from business_metadata_values v
        join metadata_field_definitions f on f.definition_id = v.definition_id and f.business_identifier
        left join metadata_field_display_order o
          on o.tenant_id = v.tenant_id and o.source_id = f.source_id and o.field_key = v.field_key
       where v.tenant_id = s.tenant_id and v.submission_id = s.submission_id and btrim(v.stored_value) <> ''
       order by o.display_position nulls last, v.field_key
       limit 1) pid on true
 where a.action = 'DOCUMENT_UNCORRECTED_COPY_RECEIVED';
