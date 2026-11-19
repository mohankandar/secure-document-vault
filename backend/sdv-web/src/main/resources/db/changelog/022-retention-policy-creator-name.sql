-- Task 0019 follow-up: the name of the person who made each new retention rule, from the signed-in session.
-- Nullable and without a default: existing rules are never backfilled or changed; screens show "a Business admin".
alter table retention_policy_versions add column if not exists created_by_display_name varchar(300) null;
