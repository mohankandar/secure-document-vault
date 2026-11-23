-- Task 0052: the incident's counts follow its rows, in the same statement. One update of the incident per statement
-- (a batch of 1,000 rows is one update), whatever writes or releases the rows.
create function sdv_quarantine_counts() returns trigger
language plpgsql as $$
begin
    with changed as (
        select tenant_id, incident_id, released_at, level, received_at, 1 as sign from new_rows
        union all
        select tenant_id, incident_id, released_at, level, received_at, -1 as sign from old_rows
    ), delta as (
        select tenant_id, incident_id,
               sum(sign) as total,
               coalesce(sum(sign) filter (where released_at is not null), 0) as released,
               coalesce(sum(sign) filter (where level = 'STANDARD'), 0) as standard,
               coalesce(sum(sign) filter (where level = 'CONFIDENTIAL'), 0) as confidential,
               coalesce(sum(sign) filter (where level = 'HIGHLY_CONFIDENTIAL'), 0) as highly_confidential,
               coalesce(sum(sign) filter (where level = 'STANDARD' and released_at is null), 0) as standard_in_force,
               coalesce(sum(sign) filter (where level = 'CONFIDENTIAL' and released_at is null), 0) as confidential_in_force,
               coalesce(sum(sign) filter (where level = 'HIGHLY_CONFIDENTIAL' and released_at is null), 0) as highly_confidential_in_force,
               min(received_at) filter (where sign = 1) as first_received,
               max(received_at) filter (where sign = 1) as last_received
          from changed group by tenant_id, incident_id
    )
    update quarantine_incidents qi
       set document_count = qi.document_count + d.total,
           released_count = qi.released_count + d.released,
           standard_count = qi.standard_count + d.standard,
           confidential_count = qi.confidential_count + d.confidential,
           highly_confidential_count = qi.highly_confidential_count + d.highly_confidential,
           standard_in_force = qi.standard_in_force + d.standard_in_force,
           confidential_in_force = qi.confidential_in_force + d.confidential_in_force,
           highly_confidential_in_force = qi.highly_confidential_in_force + d.highly_confidential_in_force,
           first_received_at = least(qi.first_received_at, d.first_received),
           last_received_at = greatest(qi.last_received_at, d.last_received)
      from delta d
     where qi.tenant_id = d.tenant_id and qi.incident_id = d.incident_id;
    return null;
end;
$$;

create trigger trg_quarantined_documents_counts_update
    after update on quarantined_documents
    referencing old table as old_rows new table as new_rows
    for each statement execute function sdv_quarantine_counts();

create function sdv_quarantine_counts_on_insert() returns trigger
language plpgsql as $$
begin
    with delta as (
        select tenant_id, incident_id,
               count(*) as total,
               count(*) filter (where released_at is not null) as released,
               count(*) filter (where level = 'STANDARD') as standard,
               count(*) filter (where level = 'CONFIDENTIAL') as confidential,
               count(*) filter (where level = 'HIGHLY_CONFIDENTIAL') as highly_confidential,
               count(*) filter (where level = 'STANDARD' and released_at is null) as standard_in_force,
               count(*) filter (where level = 'CONFIDENTIAL' and released_at is null) as confidential_in_force,
               count(*) filter (where level = 'HIGHLY_CONFIDENTIAL' and released_at is null) as highly_confidential_in_force,
               min(received_at) as first_received,
               max(received_at) as last_received
          from new_rows group by tenant_id, incident_id
    )
    update quarantine_incidents qi
       set document_count = qi.document_count + d.total,
           released_count = qi.released_count + d.released,
           standard_count = qi.standard_count + d.standard,
           confidential_count = qi.confidential_count + d.confidential,
           highly_confidential_count = qi.highly_confidential_count + d.highly_confidential,
           standard_in_force = qi.standard_in_force + d.standard_in_force,
           confidential_in_force = qi.confidential_in_force + d.confidential_in_force,
           highly_confidential_in_force = qi.highly_confidential_in_force + d.highly_confidential_in_force,
           first_received_at = least(qi.first_received_at, d.first_received),
           last_received_at = greatest(qi.last_received_at, d.last_received)
      from delta d
     where qi.tenant_id = d.tenant_id and qi.incident_id = d.incident_id;
    return null;
end;
$$;

create trigger trg_quarantined_documents_counts_insert
    after insert on quarantined_documents
    referencing new table as new_rows
    for each statement execute function sdv_quarantine_counts_on_insert();

