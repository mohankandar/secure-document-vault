-- Task 0055 (owner-approved with the task, for speed): the Restrictions page lists records with a record level
-- (newest set first) and the last 30 days' level changes (newest first), a page at a time. Two indexes; no table or
-- row changes.
create index idx_record_levels_restricted on record_sensitivity_levels(tenant_id, set_at desc, record_level_id desc)
    where sensitivity_level in ('CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL');
create index idx_level_changes_recent on sensitivity_level_changes(tenant_id, changed_at desc, change_id desc);
