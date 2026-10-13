-- SDV Business Experience Checkpoint A (Units 11-13)
-- Additive indexes for source-aware business metadata search and record aggregation.

create index idx_business_metadata_search_submission
    on business_metadata_values(tenant_id, source_id, field_key, normalized_value, submission_id);

create index idx_documents_business_record_available
    on documents(tenant_id, source_id, submission_id, status, available_at desc);
