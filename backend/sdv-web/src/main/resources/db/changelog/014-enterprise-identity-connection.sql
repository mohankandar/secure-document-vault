create table enterprise_identity_connections (
  connection_id uuid primary key, tenant_id uuid not null references tenants(tenant_id),
  provider_id varchar(100) not null, provider_type varchar(20) not null, display_name varchar(200) not null,
  issuer_uri varchar(1000) not null, browser_client_id varchar(300), api_audience varchar(300) not null,
  secret_reference varchar(500), status varchar(20) not null, configuration_version bigint not null,
  created_at timestamptz not null, updated_at timestamptz not null,
  constraint ck_sdv_eic_provider_type check (provider_type in ('OIDC','SAML','LDAP_AD')),
  constraint ck_sdv_eic_status check (status in ('DRAFT','VALIDATED','ACTIVE','DISABLED')),
  constraint ck_sdv_eic_version check (configuration_version > 0)
);
create unique index uq_sdv_eic_active_binding on enterprise_identity_connections(provider_id,issuer_uri,api_audience) where status='ACTIVE';
create index ix_sdv_eic_active_lookup on enterprise_identity_connections(provider_id,issuer_uri,api_audience) where status='ACTIVE';

create table enterprise_identity_connection_history (
  history_id uuid primary key, connection_id uuid not null, tenant_id uuid not null, provider_id varchar(100) not null,
  provider_type varchar(20) not null, issuer_uri varchar(1000) not null, browser_client_id varchar(300),
  api_audience varchar(300) not null, status varchar(20) not null, configuration_version bigint not null,
  change_type varchar(40) not null, changed_by varchar(200) not null, changed_at timestamptz not null
);
create index ix_sdv_eich_connection_time on enterprise_identity_connection_history(connection_id,changed_at desc);
