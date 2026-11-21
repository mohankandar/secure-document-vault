-- Task 0032 (owner-approved with the task): the tenant's time zone, for "today" in the Daily intake report.
-- There was no time-zone setting before. One new nullable column; no existing row changes.
--
-- * null (every tenant today): the install configuration's default applies (sdv.tenant-defaults.time-zone, from
--   SDV_DEFAULT_TIME_ZONE; UTC when unset). The report shows the zone in use, read-only.
-- * A value is an IANA zone name such as America/New_York. The application checks it with java.time.ZoneId when the
--   report is read and refuses the report (SDV-RPT-004) rather than guess when it is not a zone it knows.
alter table tenants add column time_zone varchar(64) null;
alter table tenants add constraint ck_tenants_time_zone
    check (time_zone is null or (time_zone = btrim(time_zone) and length(time_zone) between 1 and 64));
