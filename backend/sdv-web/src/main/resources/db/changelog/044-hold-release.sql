-- Task 0071: releasing a legal hold is a request that another Business Admin decides. No existing row changes.
alter table access_requests add column hold_id uuid null references legal_holds(hold_id);
alter table access_requests drop constraint ck_access_requests_kind;
alter table access_requests add constraint ck_access_requests_kind
    check (kind in ('PRINT', 'DOWNLOAD', 'LOWER_LEVEL', 'LOWER_RECORD_LEVEL', 'REDACT_HELD', 'RELEASE_HOLD'));
alter table access_requests drop constraint ck_access_requests_requested_level;
alter table access_requests add constraint ck_access_requests_requested_level check (
    (kind in ('LOWER_LEVEL', 'LOWER_RECORD_LEVEL') and requested_level is not null
        and requested_level in ('STANDARD', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL'))
    or (kind in ('PRINT', 'DOWNLOAD', 'REDACT_HELD', 'RELEASE_HOLD') and requested_level is null));
alter table access_requests add constraint ck_access_requests_hold check ((kind = 'RELEASE_HOLD') = (hold_id is not null));
