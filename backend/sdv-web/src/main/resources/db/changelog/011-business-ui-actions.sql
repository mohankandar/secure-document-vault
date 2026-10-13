-- SDV post-V1 business workspace modernization.
-- Adds tenant-scoped role capability overrides and document tags.

create table tenant_role_capability_overrides (
    tenant_id uuid not null references tenants(tenant_id),
    role_name varchar(80) not null,
    capability varchar(120) not null,
    enabled boolean not null,
    updated_by varchar(300) not null,
    updated_at timestamptz not null default now(),
    primary key (tenant_id, role_name, capability)
);

create table document_tags (
    tag_id uuid primary key,
    tenant_id uuid not null,
    document_id uuid not null,
    tag_value varchar(80) not null,
    created_by varchar(300) not null,
    created_at timestamptz not null default now(),
    foreign key (tenant_id, document_id) references documents(tenant_id, document_id),
    unique (tenant_id, document_id, tag_value)
);

create index idx_document_tags_document on document_tags(tenant_id, document_id, created_at desc);
