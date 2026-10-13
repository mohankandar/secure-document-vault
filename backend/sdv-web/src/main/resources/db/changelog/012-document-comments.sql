-- SDV R3 viewer comments. Tenant-scoped and document-scoped; comments are append-only.
create table document_comments (
    comment_id uuid primary key,
    tenant_id uuid not null,
    document_id uuid not null,
    comment_text varchar(2000) not null,
    created_by varchar(300) not null,
    created_at timestamptz not null default now(),
    foreign key (tenant_id, document_id) references documents(tenant_id, document_id)
);
create index idx_document_comments_document on document_comments(tenant_id, document_id, created_at desc);
