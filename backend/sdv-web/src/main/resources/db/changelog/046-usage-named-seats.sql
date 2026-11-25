-- Task 0090: who held a steward or admin role in a month, as a keyed hash only (the key never leaves the key service). Rows
-- of the current and the previous month; the numbers are in the ledger.
create table usage_named_seats (
    tenant_id uuid not null references tenants(tenant_id),
    month varchar(7) not null check (month ~ '^[0-9]{4}-[0-9]{2}$'),
    person_hash varchar(100) not null,
    primary key (tenant_id, month, person_hash)
);
create index idx_usage_named_seats_month on usage_named_seats(month);
