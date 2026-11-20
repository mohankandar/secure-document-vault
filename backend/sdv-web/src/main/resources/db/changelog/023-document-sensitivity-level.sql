-- Task 0024: every document carries a sensitivity level, set by the sending system when it delivers the document.
-- Existing documents become STANDARD through the default; no other data changes. Nothing reads the level to filter
-- yet (enforcement is task 0026), so no index is added here: no query uses the column, and three values alone would
-- not make a useful index. Task 0026 adds one if its search queries need it.
alter table documents add column if not exists sensitivity_level text not null default 'STANDARD';
alter table documents add constraint ck_documents_sensitivity_level
    check (sensitivity_level in ('STANDARD', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL'));
