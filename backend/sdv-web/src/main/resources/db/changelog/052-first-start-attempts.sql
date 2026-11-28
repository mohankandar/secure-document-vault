-- Task 0098 follow-up (answer Q-7): refused attempts on the first-start screen. Before the first entity is active Vero's
-- audit has no entity to write them in, so they wait here (time, user ID, slot, result code; never the licence file) and
-- are copied into the new entity's audit as FIRST_START_REFUSED when the first entity is activated; the table is emptied in
-- that same transaction. Append-only like the audit trail: UPDATE and TRUNCATE are refused for everyone, and DELETE as long
-- as no entity is ACTIVE (so only the copy at the first activation can empty it).
create table first_start_attempts (
    attempt_id uuid primary key,
    attempted_at timestamptz not null,
    user_id text not null check (length(user_id) between 1 and 300),
    display_name text null check (display_name is null or length(display_name) <= 300),
    slot_id text not null check (length(slot_id) between 1 and 100),
    result_code text not null check (result_code ~ '^[A-Z_]{1,60}$')
);
create index ix_first_start_attempts_time on first_start_attempts (attempted_at);

create trigger trg_first_start_attempts_append_only
    before update on first_start_attempts
    for each row execute function sdv_refuse_append_only_change();
create trigger trg_first_start_attempts_no_truncate
    before truncate on first_start_attempts
    for each statement execute function sdv_refuse_append_only_change();

create function sdv_first_start_attempts_kept() returns trigger
language plpgsql as $$
begin
    if not exists (select 1 from idn_entity where status = 'ACTIVE') then
        raise exception 'SDV-AUD-017 % keeps every attempt until the first entity is active: % is refused', tg_table_name, tg_op
            using errcode = 'insufficient_privilege';
    end if;
    return old;
end;
$$;
create trigger trg_first_start_attempts_kept
    before delete on first_start_attempts
    for each row execute function sdv_first_start_attempts_kept();
