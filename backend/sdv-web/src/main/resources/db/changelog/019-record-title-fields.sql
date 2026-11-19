-- Task 0009: the one or two fields that name a record, chosen per sending system by the Business admin.
-- No rows for a source means the default title (the first preview field).
create table if not exists record_title_fields (
    tenant_id uuid not null,
    source_id uuid not null,
    field_key varchar(100) not null,
    title_position smallint not null check (title_position between 0 and 1),
    primary key (tenant_id, source_id, title_position),
    unique (tenant_id, source_id, field_key),
    foreign key (tenant_id, source_id) references ingestion_sources (tenant_id, source_id)
);
