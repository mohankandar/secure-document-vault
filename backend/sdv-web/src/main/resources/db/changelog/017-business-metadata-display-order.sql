create table if not exists metadata_field_display_order (
    tenant_id uuid not null,
    source_id uuid not null,
    field_key varchar(100) not null,
    display_position integer not null check (display_position >= 0),
    primary key (tenant_id, source_id, field_key),
    unique (tenant_id, source_id, display_position)
);
