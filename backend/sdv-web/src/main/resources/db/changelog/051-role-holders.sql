-- Task 0100 (VSEC-203): who held which list role in which entity at their last sign-in. Roles come from each sign-in's
-- groups and are taken away in the customer's login system, which tells Vero nothing; this table is what Vero has seen.
-- A role's notification list accepts only people who hold that role here, messages go only to them, and a sign-in that
-- shows a role gone takes the person off that role's lists in the same transaction (audited). No user ID is a secret;
-- no address is kept here.
--
-- The lists' existing rows: nothing about anyone's roles is known yet, so no row can be checked here, and a deletion
-- here could not be audited. They stay, receive nothing until their person signs in with the role, and are marked
-- "not confirmed" on the screens; a sign-in without the role removes them (audited).
create table role_holders (
    entity_id uuid not null references tenants(tenant_id),
    customer_id text not null check (customer_id = btrim(customer_id) and length(customer_id) between 1 and 128),
    user_id text not null check (user_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'),
    role text not null check (role in ('BUSINESS_USER', 'DOCUMENT_STEWARD', 'RESTRICTED_STEWARD', 'BUSINESS_ADMIN', 'IT_ADMIN')),
    seen_at timestamptz not null,
    primary key (entity_id, user_id, role)
);
create index ix_role_holders_customer on role_holders (customer_id, role, user_id);
