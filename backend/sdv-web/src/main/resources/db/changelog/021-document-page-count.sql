-- Task 0018 follow-up: each document's page count, worked out once when the document is stored, so record lists
-- can show "12 pages" without opening the document. Nullable and without a default: documents stored before this
-- change keep no count, and lists show nothing for them. Never backfilled by opening documents.
alter table documents add column if not exists page_count integer null;
alter table documents add constraint ck_documents_page_count check (page_count is null or page_count > 0);
