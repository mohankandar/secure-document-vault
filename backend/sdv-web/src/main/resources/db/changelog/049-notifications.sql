-- Task 0081 (owner-approved with the task): notifications.
--
-- 1. The recipient lists the admins keep (answer 5 of plan 0069): who gets e-mail for a role, per entity (entity_id) or,
--    for the IT Admin, for the whole customer (entity_id null). Addresses live here and nowhere else: not in the
--    notif_ tables, the audit or a log. The platform's notif_ tables come from their own run of the schema step.
-- 2. Time zones move to identity, the one source (idn_entity.time_zone, idn_customer.time_zone). An entity's own zone
--    in tenants.time_zone is copied to its idn_entity row when that has none, then the column goes. A zone identity's
--    check refuses was refused by the reports already; it is not copied.
create table notification_recipients (
    recipient_id uuid primary key,
    customer_id text not null check (customer_id = btrim(customer_id) and length(customer_id) between 1 and 128),
    entity_id uuid null references tenants(tenant_id),
    role text not null check (role in ('BUSINESS_USER', 'DOCUMENT_STEWARD', 'RESTRICTED_STEWARD', 'BUSINESS_ADMIN', 'IT_ADMIN')),
    user_id text not null check (user_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    email text not null check (length(email) between 3 and 254 and email ~ '^[^@\s]+@[^@\s]+$'),
    added_by text not null,
    added_at timestamptz not null,
    constraint ck_notification_recipients_scope check ((role = 'IT_ADMIN') = (entity_id is null))
);
create unique index uq_notification_recipients
    on notification_recipients (customer_id, coalesce(entity_id, '00000000-0000-0000-0000-000000000000'::uuid), role, user_id);
create index ix_notification_recipients_user on notification_recipients (customer_id, user_id);

do $$
begin
    if to_regclass('idn_entity') is not null then
        update idn_entity e set time_zone = t.time_zone
        from tenants t
        where t.tenant_id = e.entity_uuid and t.time_zone is not null and e.time_zone is null
          and t.time_zone ~ '^[A-Za-z][A-Za-z0-9_+/-]{0,63}$';
    end if;
end
$$;
alter table tenants drop constraint if exists ck_tenants_time_zone;
alter table tenants drop column time_zone;
