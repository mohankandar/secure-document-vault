-- Task 0039, item 2 (owner-approved 2026-11-22: one small table). Intake attempts that ended in an error before Vero
-- knew the sending system, for example a failed sign-in on an intake address (report 0033: they were recorded
-- nowhere). Tenant IT and Platform operations see them in Problems. No existing table or row changes.
--
-- * tenant_id: only when a verified sign-in named a tenant that exists (for example a suspended tenant, or a sending
--   system that isn't one of the tenant's). Null otherwise; such an attempt is shown to every tenant's Tenant IT.
-- * caller_address: the network address the request came from, as Vero's server saw it (a proxy's, behind one).
-- * address: the Vero address called, with its IDs blanked ({id}); never a query string.
-- * code and reference: what the caller got, and the request's correlation ID (on Vero's log line too).
-- Never a header, a token or anything from one, a body, or a file name. Rows older than 30 days are removed when a new
-- one is written.
create table unidentified_intake_attempts (
    attempt_id uuid primary key,
    tenant_id uuid null references tenants(tenant_id),
    occurred_at timestamptz not null,
    caller_address varchar(64) null,
    method varchar(10) not null,
    address varchar(200) not null,
    status integer not null,
    code varchar(40) not null,
    reference varchar(200) not null,
    constraint ck_unidentified_intake_attempts_status check (status between 400 and 599)
);
-- Problems reads the newest first, per tenant and for no tenant; old rows are removed by time.
create index idx_unidentified_intake_attempts_time on unidentified_intake_attempts(occurred_at desc, attempt_id);
create index idx_unidentified_intake_attempts_tenant_time on unidentified_intake_attempts(tenant_id, occurred_at desc);
