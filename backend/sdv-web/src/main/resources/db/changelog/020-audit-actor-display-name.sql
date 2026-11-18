-- Task 0011 Part C.2: the actor's display name on new audit events, from the signed-in session.
-- Nullable and without a default: existing rows are never backfilled or changed.
alter table audit_events add column if not exists actor_display_name varchar(300) null;
