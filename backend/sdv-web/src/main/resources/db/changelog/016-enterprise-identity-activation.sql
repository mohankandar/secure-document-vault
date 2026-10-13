create unique index uq_sdv_eic_active_per_tenant
  on enterprise_identity_connections(tenant_id)
  where status='ACTIVE';
