-- Task 0075: the anchor of every usage export (platform-core's ExportAnchor): the next file goes on from the newest. Numbers
-- and hashes only. A file's months and records are in the ledger (lic_usage).
create table usage_export_anchor (
    sequence bigint primary key,
    final_chain_value varchar(128) not null,
    period_to varchar(7) not null,
    created_at timestamp with time zone not null
);
