-- Task 0039 part 2, owner answer 2 to report 0039 (owner-approved 2026-11-22: count and last-seen columns on
-- unidentified_intake_attempts). One row per caller address + address called + code per minute, with a count and
-- the last time, instead of one row per request, so a flood of requests can't grow the table without bound. Kept 30
-- days, as before. No other table changes.
--
-- * attempt_count: how many requests the row stands for (1 for every row written before this change).
-- * last_seen_at: the last of them (occurred_at, the first, for every row written before this change).
-- * The minute key also holds the tenant (who may see the row), the method and the status, so rows of different
--   tenants are never merged. The minute is the UTC minute of occurred_at (the first request of the row).
-- * The reference is the first request's; every request's reference is on Vero's log line.

alter table unidentified_intake_attempts add column attempt_count integer not null default 1;
alter table unidentified_intake_attempts add column last_seen_at timestamptz null;
update unidentified_intake_attempts set last_seen_at = occurred_at;

-- Rows written before this change that share a key and a minute become one: the first keeps the count and the last
-- time, the others are removed.
update unidentified_intake_attempts u
   set attempt_count = g.n, last_seen_at = g.last_at
  from (select (array_agg(attempt_id order by occurred_at, attempt_id))[1] as keep_id, count(*) as n,
               max(occurred_at) as last_at
          from unidentified_intake_attempts
         group by tenant_id, caller_address, method, address, code, status,
                  date_trunc('minute', occurred_at at time zone 'UTC')
        having count(*) > 1) g
 where u.attempt_id = g.keep_id;
delete from unidentified_intake_attempts u
 where exists (select 1 from unidentified_intake_attempts k
                where k.tenant_id is not distinct from u.tenant_id
                  and k.caller_address is not distinct from u.caller_address
                  and k.method = u.method and k.address = u.address and k.code = u.code and k.status = u.status
                  and date_trunc('minute', k.occurred_at at time zone 'UTC') = date_trunc('minute', u.occurred_at at time zone 'UTC')
                  and (k.occurred_at, k.attempt_id) < (u.occurred_at, u.attempt_id));

alter table unidentified_intake_attempts alter column last_seen_at set not null;
alter table unidentified_intake_attempts add constraint ck_unidentified_intake_attempts_count check (attempt_count >= 1);
alter table unidentified_intake_attempts add constraint ck_unidentified_intake_attempts_last_seen check (last_seen_at >= occurred_at);

-- One row per key and minute; Vero adds to it ("insert ... on conflict ... do update").
create unique index uq_unidentified_intake_attempts_minute on unidentified_intake_attempts(
    coalesce(tenant_id, '00000000-0000-0000-0000-000000000000'::uuid), coalesce(caller_address, ''), method, address,
    code, status, date_trunc('minute', occurred_at at time zone 'UTC'));
