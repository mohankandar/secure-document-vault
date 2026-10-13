alter table enterprise_identity_connections add column last_tested_at timestamptz;
alter table enterprise_identity_connections add column last_test_status varchar(20);
alter table enterprise_identity_connections add column last_test_message varchar(500);
alter table enterprise_identity_connections add constraint ck_sdv_eic_test_status check (last_test_status is null or last_test_status in ('PASSED','FAILED'));
create unique index uq_sdv_eic_staged_per_tenant_provider
  on enterprise_identity_connections(tenant_id)
  where status in ('DRAFT','VALIDATED');
