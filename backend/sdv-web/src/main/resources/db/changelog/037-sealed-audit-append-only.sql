-- Task 0050: the audit trail and the report register are append-only. A row is written once and never changed or
-- removed: the triggers refuse UPDATE, DELETE and TRUNCATE for everyone, the owner included, and the running app's
-- user loses those rights (the table owner keeps only what PostgreSQL never lets it give up).
create function sdv_refuse_append_only_change() returns trigger
language plpgsql as $$
begin
    raise exception 'SDV-AUD-017 % is append-only: % is refused', tg_table_name, tg_op
        using errcode = 'insufficient_privilege';
end;
$$;

create trigger trg_audit_events_append_only
    before update or delete on audit_events
    for each row execute function sdv_refuse_append_only_change();
create trigger trg_audit_events_no_truncate
    before truncate on audit_events
    for each statement execute function sdv_refuse_append_only_change();
create trigger trg_audit_reports_append_only
    before update or delete on audit_reports
    for each row execute function sdv_refuse_append_only_change();
create trigger trg_audit_reports_no_truncate
    before truncate on audit_reports
    for each statement execute function sdv_refuse_append_only_change();

-- Every role but the tables' owner (the running app's user, granted through default privileges) keeps SELECT and
-- INSERT only.
do $$
declare
    r record;
begin
    revoke update, delete, truncate on audit_events, audit_reports from public;
    for r in
        select distinct g.grantee, g.table_name
        from information_schema.role_table_grants g
        join pg_tables t on t.tablename = g.table_name and t.schemaname = g.table_schema
        where g.table_schema = current_schema()
          and g.table_name in ('audit_events', 'audit_reports')
          and g.privilege_type in ('UPDATE', 'DELETE', 'TRUNCATE')
          and g.grantee <> t.tableowner
          and g.grantee <> 'PUBLIC'
    loop
        execute format('revoke update, delete, truncate on %I from %I', r.table_name, r.grantee);
    end loop;
end;
$$;
