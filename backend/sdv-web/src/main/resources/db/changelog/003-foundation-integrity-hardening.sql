-- SDV Foundation FIX1: tighten tenant/source/submission referential integrity before Foundation freeze.

-- Composite identities used by tenant/source-aware foreign keys.
alter table metadata_field_definitions
    add constraint uq_metadata_definition_tenant_source_id
    unique (tenant_id, source_id, definition_id);

alter table metadata_field_definitions
    add constraint uq_metadata_definition_identity
    unique (tenant_id, source_id, definition_id, field_key, version);

alter table submissions
    add constraint uq_submission_tenant_source_identity
    unique (tenant_id, submission_id, source_id);

alter table documents
    add constraint uq_document_tenant_submission_identity
    unique (tenant_id, submission_id, document_id);

-- A document must belong to the same source as its submission.
alter table documents
    add constraint fk_document_submission_source
    foreign key (tenant_id, submission_id, source_id)
    references submissions(tenant_id, submission_id, source_id);

-- An expected-document receipt may only point at a document from the same submission.
alter table submission_expected_documents
    drop constraint fk_expected_received_document;

alter table submission_expected_documents
    add constraint fk_expected_received_document
    foreign key (tenant_id, submission_id, received_document_id)
    references documents(tenant_id, submission_id, document_id);

-- Persist source identity on metadata values so source-aware search/history is explicit
-- and the exact historical field definition can be tenant/source constrained.
alter table business_metadata_values
    add column source_id uuid;

update business_metadata_values v
   set source_id = s.source_id
  from submissions s
 where s.tenant_id = v.tenant_id
   and s.submission_id = v.submission_id;

alter table business_metadata_values
    alter column source_id set not null;

alter table business_metadata_values
    add constraint fk_business_metadata_submission_source
    foreign key (tenant_id, submission_id, source_id)
    references submissions(tenant_id, submission_id, source_id);

alter table business_metadata_values
    drop constraint business_metadata_values_definition_id_fkey;

alter table business_metadata_values
    add constraint fk_business_metadata_definition_identity
    foreign key (tenant_id, source_id, definition_id, field_key, definition_version)
    references metadata_field_definitions(tenant_id, source_id, definition_id, field_key, version);

drop index idx_business_metadata_lookup;
create index idx_business_metadata_lookup
    on business_metadata_values(tenant_id, source_id, field_key, normalized_value);
