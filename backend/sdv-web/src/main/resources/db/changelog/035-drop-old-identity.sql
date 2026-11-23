-- Task 0041 (owner answer 14): Vero's identity moves to platform-core (idn_ tables, their own Flyway run). The old
-- tables go; nothing in them is kept (nothing is in production or test). Changesets 014-016 and 025 stay in the file,
-- so a new database makes these tables and drops them here; an existing one just drops them.
--
-- * enterprise_identity_connection_history, enterprise_identity_connections (014-016): Vero's own login connections,
--   replaced by idn_login_connection and idn_login_connection_history (setup loads them).
-- * role_group_mappings_seen, role_mapping_notices (025, rule 3): the watch on the server's Restricted Steward group
--   setting, replaced by idn_grant_history (owner answer 8). Role conflicts (role_conflict_problems) stay.
drop table if exists enterprise_identity_connection_history;
drop table if exists enterprise_identity_connections;
drop table if exists role_mapping_notices;
drop table if exists role_group_mappings_seen;
