package com.sdv.platform.persistence.identity;

import com.documentplatform.identity.OidcConnectionBinding;
import com.documentplatform.identity.OidcConnectionBindingResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcOidcConnectionBindingRepository implements OidcConnectionBindingResolver {
    private static final Duration ACTIVATION_TEST_MAX_AGE = Duration.ofMinutes(15);
    private final JdbcTemplate jdbc;
    public JdbcOidcConnectionBindingRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Override public Optional<OidcConnectionBinding> findActive(String providerId, URI issuer, String audience) {
        List<OidcConnectionBinding> rows=jdbc.query("""
            select connection_id,tenant_id,provider_id,issuer_uri,api_audience,configuration_version
              from enterprise_identity_connections where provider_id=? and issuer_uri=? and api_audience=? and status='ACTIVE'
            """, (rs,n)->new OidcConnectionBinding(rs.getObject("connection_id",UUID.class),rs.getObject("tenant_id",UUID.class),rs.getString("provider_id"),URI.create(rs.getString("issuer_uri")),rs.getString("api_audience"),rs.getLong("configuration_version")), clean(providerId), normalizeIssuer(issuer), clean(audience));
        if(rows.size()>1) throw new IllegalStateException("Ambiguous ACTIVE OIDC connection binding");
        return rows.stream().findFirst();
    }

    public Optional<ConnectionAdminView> findActiveForTenant(UUID tenantId) { return findForTenantByStatus(tenantId, "ACTIVE"); }
    public Optional<ConnectionAdminView> findStagedForTenant(UUID tenantId) {
        List<ConnectionAdminView> rows = jdbc.query(SELECT_ADMIN + " where tenant_id=? and status in ('DRAFT','VALIDATED') order by updated_at desc limit 1", this::mapAdmin, tenantId);
        return rows.stream().findFirst();
    }
    public List<ConnectionAdminView> findRollbackCandidatesForTenant(UUID tenantId) {
        return jdbc.query(SELECT_ADMIN + " c where c.tenant_id=? and c.status='DISABLED' and exists (select 1 from enterprise_identity_connection_history h where h.tenant_id=c.tenant_id and h.connection_id=c.connection_id and h.change_type in ('BOOTSTRAP','ACTIVATED','ROLLBACK_ACTIVATED')) order by c.updated_at desc", this::mapAdmin, tenantId);
    }

    @Transactional public ConnectionAdminView saveDraft(UUID tenantId, String providerId, String displayName, URI issuer, String browserClientId, String audience, String actor) {
        String pid=clean(providerId), name=clean(displayName), normalizedIssuer=normalizeIssuer(issuer), aud=clean(audience), client=clean(browserClientId), changedBy=clean(actor);
        Optional<ConnectionAdminView> existing=findStagedForTenant(tenantId);
        Instant now=Instant.now(); UUID id; long version;
        if(existing.isPresent()) {
            ConnectionAdminView current=existing.get(); id=current.connectionId(); version=current.configurationVersion()+1;
            int updated=jdbc.update("""
                update enterprise_identity_connections set provider_id=?,provider_type='OIDC',display_name=?,issuer_uri=?,browser_client_id=?,api_audience=?,
                       status='DRAFT',configuration_version=?,last_tested_at=null,last_test_status=null,last_test_message=null,updated_at=?
                 where connection_id=? and tenant_id=? and configuration_version=? and status in ('DRAFT','VALIDATED')
                """, pid,name,normalizedIssuer,client,aud,version,Timestamp.from(now),id,tenantId,current.configurationVersion());
            if(updated!=1) throw new IllegalStateException("Authentication draft changed while it was being saved. Reload and try again.");
        } else {
            Long max=jdbc.queryForObject("select coalesce(max(configuration_version),0) from enterprise_identity_connections where tenant_id=? and provider_id=?",Long.class,tenantId,pid);
            version=(max==null?0:max)+1; id=UUID.randomUUID();
            jdbc.update("""
                insert into enterprise_identity_connections(connection_id,tenant_id,provider_id,provider_type,display_name,issuer_uri,browser_client_id,api_audience,secret_reference,status,configuration_version,created_at,updated_at)
                values (?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?)
                """,id,tenantId,pid,"OIDC",name,normalizedIssuer,client,aud,null,version,Timestamp.from(now),Timestamp.from(now));
        }
        appendHistory(id,tenantId,pid,normalizedIssuer,client,aud,"DRAFT",version,"DRAFT_SAVED",changedBy,now);
        return findById(tenantId,id).orElseThrow();
    }

    @Transactional public ConnectionAdminView recordTest(UUID tenantId, UUID connectionId, long expectedConfigurationVersion, boolean passed, String message, String actor) {
        ConnectionAdminView current=findById(tenantId,connectionId).orElseThrow(() -> new IllegalArgumentException("Staged identity connection was not found."));
        if(!current.status().equals("DRAFT")&&!current.status().equals("VALIDATED")) throw new IllegalArgumentException("Only a staged identity connection can be tested.");
        if(current.configurationVersion()!=expectedConfigurationVersion) throw staleTest();
        Instant now=Instant.now(); String status=passed?"VALIDATED":"DRAFT"; String testStatus=passed?"PASSED":"FAILED"; String safeMessage=truncate(message,500);
        int updated=jdbc.update("update enterprise_identity_connections set status=?,last_tested_at=?,last_test_status=?,last_test_message=?,updated_at=? where connection_id=? and tenant_id=? and configuration_version=? and status in ('DRAFT','VALIDATED')",
                status,Timestamp.from(now),testStatus,safeMessage,Timestamp.from(now),connectionId,tenantId,expectedConfigurationVersion);
        if(updated!=1) throw staleTest();
        appendHistory(connectionId,tenantId,current.providerId(),current.issuerUri(),current.browserClientId(),current.apiAudience(),status,expectedConfigurationVersion,passed?"TEST_PASSED":"TEST_FAILED",clean(actor),now);
        return findById(tenantId,connectionId).orElseThrow();
    }

    @Transactional public ActivationResult activateValidated(UUID tenantId, UUID connectionId, long expectedConfigurationVersion, String actor) {
        ConnectionAdminView staged=findById(tenantId,connectionId).orElseThrow(() -> new IllegalArgumentException("Validated identity configuration was not found."));
        if(!"VALIDATED".equals(staged.status()) || staged.configurationVersion()!=expectedConfigurationVersion)
            throw new IllegalStateException("Only the exact currently VALIDATED configuration version can be activated.");
        Instant now=Instant.now();
        if(!"PASSED".equals(staged.lastTestStatus()) || staged.lastTestedAt()==null || staged.lastTestedAt().isAfter(now) || Duration.between(staged.lastTestedAt(),now).compareTo(ACTIVATION_TEST_MAX_AGE)>0)
            throw new IllegalStateException("Activation requires a successful connection test from the last 15 minutes. Test the current draft again.");
        ConnectionAdminView active=findActiveForTenant(tenantId).orElseThrow(() -> new IllegalStateException("No ACTIVE identity connection exists. Use the controlled recovery procedure instead of activation."));
        if(active.connectionId().equals(staged.connectionId())) throw new IllegalStateException("The selected identity connection is already ACTIVE.");

        int retired=jdbc.update("update enterprise_identity_connections set status='DISABLED',updated_at=? where connection_id=? and tenant_id=? and status='ACTIVE'",
                Timestamp.from(now),active.connectionId(),tenantId);
        if(retired!=1) throw new IllegalStateException("ACTIVE identity connection changed during activation. No change was committed.");
        int activated=jdbc.update("update enterprise_identity_connections set status='ACTIVE',updated_at=? where connection_id=? and tenant_id=? and configuration_version=? and status='VALIDATED' and last_test_status='PASSED'",
                Timestamp.from(now),staged.connectionId(),tenantId,expectedConfigurationVersion);
        if(activated!=1) throw new IllegalStateException("Validated identity configuration changed during activation. No change was committed.");

        String changedBy=clean(actor);
        appendHistory(active.connectionId(),tenantId,active.providerId(),active.issuerUri(),active.browserClientId(),active.apiAudience(),"DISABLED",active.configurationVersion(),"SUPERSEDED",changedBy,now);
        appendHistory(staged.connectionId(),tenantId,staged.providerId(),staged.issuerUri(),staged.browserClientId(),staged.apiAudience(),"ACTIVE",staged.configurationVersion(),"ACTIVATED",changedBy,now);
        return new ActivationResult(findById(tenantId,staged.connectionId()).orElseThrow(), active);
    }

    @Transactional public ActivationResult rollbackToKnownGood(UUID tenantId, UUID connectionId, long expectedConfigurationVersion, String actor) {
        ConnectionAdminView candidate=findById(tenantId,connectionId).orElseThrow(() -> new IllegalArgumentException("Rollback identity connection was not found."));
        if(!"DISABLED".equals(candidate.status()) || candidate.configurationVersion()!=expectedConfigurationVersion)
            throw new IllegalStateException("Rollback target is not the expected disabled configuration version.");
        Integer knownGood=jdbc.queryForObject("select count(*) from enterprise_identity_connection_history where tenant_id=? and connection_id=? and change_type in ('BOOTSTRAP','ACTIVATED','ROLLBACK_ACTIVATED')",Integer.class,tenantId,connectionId);
        if(knownGood==null || knownGood==0) throw new IllegalStateException("Rollback target is not a previously ACTIVE known-good identity configuration.");
        ConnectionAdminView active=findActiveForTenant(tenantId).orElseThrow(() -> new IllegalStateException("No ACTIVE identity connection exists. Use the controlled recovery procedure instead of rollback."));
        Instant now=Instant.now();
        int retired=jdbc.update("update enterprise_identity_connections set status='DISABLED',updated_at=? where connection_id=? and tenant_id=? and status='ACTIVE'",
                Timestamp.from(now),active.connectionId(),tenantId);
        if(retired!=1) throw new IllegalStateException("ACTIVE identity connection changed during rollback. No change was committed.");
        int restored=jdbc.update("update enterprise_identity_connections set status='ACTIVE',updated_at=? where connection_id=? and tenant_id=? and configuration_version=? and status='DISABLED'",
                Timestamp.from(now),candidate.connectionId(),tenantId,expectedConfigurationVersion);
        if(restored!=1) throw new IllegalStateException("Rollback target changed during rollback. No change was committed.");
        String changedBy=clean(actor);
        appendHistory(active.connectionId(),tenantId,active.providerId(),active.issuerUri(),active.browserClientId(),active.apiAudience(),"DISABLED",active.configurationVersion(),"ROLLBACK_SUPERSEDED",changedBy,now);
        appendHistory(candidate.connectionId(),tenantId,candidate.providerId(),candidate.issuerUri(),candidate.browserClientId(),candidate.apiAudience(),"ACTIVE",candidate.configurationVersion(),"ROLLBACK_ACTIVATED",changedBy,now);
        return new ActivationResult(findById(tenantId,candidate.connectionId()).orElseThrow(), active);
    }

    @Transactional public ActivationResult recoverKnownGood(UUID tenantId, UUID connectionId, long expectedConfigurationVersion, String recoveryRequestId) {
        String actor="SYSTEM_RECOVERY:"+clean(recoveryRequestId);
        Integer alreadyApplied=jdbc.queryForObject("select count(*) from enterprise_identity_connection_history where tenant_id=? and connection_id=? and change_type='RECOVERY_ACTIVATED' and changed_by=?",Integer.class,tenantId,connectionId,actor);
        if(alreadyApplied!=null && alreadyApplied>0){
            ConnectionAdminView active=findActiveForTenant(tenantId).orElseThrow(() -> new IllegalStateException("Recovery request was already applied but the tenant currently has no ACTIVE identity connection."));
            if(active.connectionId().equals(connectionId) && active.configurationVersion()==expectedConfigurationVersion) return new ActivationResult(active,null);
            throw new IllegalStateException("Recovery request-id has already been consumed and will not be replayed. Disable the recovery setting or create a new explicit recovery request.");
        }
        ConnectionAdminView candidate=findById(tenantId,connectionId).orElseThrow(() -> new IllegalArgumentException("Recovery target identity connection was not found."));
        if(candidate.configurationVersion()!=expectedConfigurationVersion) throw new IllegalStateException("Recovery target version does not match the requested known-good configuration version.");
        Integer knownGood=jdbc.queryForObject("select count(*) from enterprise_identity_connection_history where tenant_id=? and connection_id=? and change_type in ('BOOTSTRAP','ACTIVATED','ROLLBACK_ACTIVATED','RECOVERY_ACTIVATED')",Integer.class,tenantId,connectionId);
        if(knownGood==null || knownGood==0) throw new IllegalStateException("Recovery target is not a previously ACTIVE known-good identity configuration.");
        Optional<ConnectionAdminView> current=findActiveForTenant(tenantId);
        if(current.isPresent() && current.get().connectionId().equals(connectionId)){
            return new ActivationResult(current.get(),null);
        }
        if(!"DISABLED".equals(candidate.status())) throw new IllegalStateException("Recovery target must be a disabled previously ACTIVE identity configuration.");
        Instant now=Instant.now(); ConnectionAdminView previous=current.orElse(null);
        if(previous!=null){
            int retired=jdbc.update("update enterprise_identity_connections set status='DISABLED',updated_at=? where connection_id=? and tenant_id=? and status='ACTIVE'",Timestamp.from(now),previous.connectionId(),tenantId);
            if(retired!=1) throw new IllegalStateException("ACTIVE identity connection changed during recovery. No change was committed.");
            appendHistory(previous.connectionId(),tenantId,previous.providerId(),previous.issuerUri(),previous.browserClientId(),previous.apiAudience(),"DISABLED",previous.configurationVersion(),"RECOVERY_SUPERSEDED",actor,now);
        }
        int restored=jdbc.update("update enterprise_identity_connections set status='ACTIVE',updated_at=? where connection_id=? and tenant_id=? and configuration_version=? and status='DISABLED'",Timestamp.from(now),candidate.connectionId(),tenantId,expectedConfigurationVersion);
        if(restored!=1) throw new IllegalStateException("Recovery target changed during recovery. No change was committed.");
        appendHistory(candidate.connectionId(),tenantId,candidate.providerId(),candidate.issuerUri(),candidate.browserClientId(),candidate.apiAudience(),"ACTIVE",candidate.configurationVersion(),"RECOVERY_ACTIVATED",actor,now);
        return new ActivationResult(findById(tenantId,candidate.connectionId()).orElseThrow(),previous);
    }

    @Transactional public void bootstrapIfAbsent(UUID tenantId,String providerId,URI issuer,String browserClientId,String audience) {
        Integer tenantRows=jdbc.queryForObject("select count(*) from enterprise_identity_connections where tenant_id=?",Integer.class,tenantId);
        if(tenantRows!=null&&tenantRows>0)return; // one-way bootstrap only; never recreate/reactivate after administration begins
        UUID id=UUID.randomUUID(); Instant now=Instant.now();
        jdbc.update("""
                insert into enterprise_identity_connections(
                    connection_id, tenant_id, provider_id, provider_type, display_name, issuer_uri,
                    browser_client_id, api_audience, secret_reference, status, configuration_version,
                    created_at, updated_at
                ) values (?,?,?,?,?,?,?,?,?,'ACTIVE',1,?,?)
                """, id, tenantId, clean(providerId), "OIDC", "Bootstrap OIDC connection",
                normalizeIssuer(issuer), blankToNull(browserClientId), clean(audience), null,
                Timestamp.from(now), Timestamp.from(now));
        appendHistory(id,tenantId,clean(providerId),normalizeIssuer(issuer),blankToNull(browserClientId),clean(audience),"ACTIVE",1L,"BOOTSTRAP","SYSTEM",now);
    }

    private Optional<ConnectionAdminView> findForTenantByStatus(UUID tenantId,String status){
        List<ConnectionAdminView> rows=jdbc.query(SELECT_ADMIN+" where tenant_id=? and status=? order by updated_at desc limit 1",this::mapAdmin,tenantId,status); return rows.stream().findFirst();
    }
    private Optional<ConnectionAdminView> findById(UUID tenantId,UUID id){
        List<ConnectionAdminView> rows=jdbc.query(SELECT_ADMIN+" where tenant_id=? and connection_id=?",this::mapAdmin,tenantId,id); return rows.stream().findFirst();
    }
    private ConnectionAdminView mapAdmin(java.sql.ResultSet rs,int n)throws java.sql.SQLException{return new ConnectionAdminView(rs.getObject("connection_id",UUID.class),rs.getObject("tenant_id",UUID.class),rs.getString("provider_id"),rs.getString("provider_type"),rs.getString("display_name"),rs.getString("issuer_uri"),rs.getString("browser_client_id"),rs.getString("api_audience"),rs.getString("status"),rs.getLong("configuration_version"),toInstant(rs.getTimestamp("created_at")),toInstant(rs.getTimestamp("updated_at")),toInstant(rs.getTimestamp("last_tested_at")),rs.getString("last_test_status"),rs.getString("last_test_message"));}
    private void appendHistory(UUID id,UUID tenantId,String providerId,String issuer,String client,String audience,String status,long version,String change,String actor,Instant at){jdbc.update("""
        insert into enterprise_identity_connection_history(history_id,connection_id,tenant_id,provider_id,provider_type,issuer_uri,browser_client_id,api_audience,status,configuration_version,change_type,changed_by,changed_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?,?)
        """,UUID.randomUUID(),id,tenantId,providerId,"OIDC",issuer,client,audience,status,version,change,actor,Timestamp.from(at));}
    private static final String SELECT_ADMIN="select connection_id,tenant_id,provider_id,provider_type,display_name,issuer_uri,browser_client_id,api_audience,status,configuration_version,created_at,updated_at,last_tested_at,last_test_status,last_test_message from enterprise_identity_connections";
    private static Instant toInstant(Timestamp v){return v==null?null:v.toInstant();}
    private static String normalizeIssuer(URI issuer){if(issuer==null)throw new IllegalArgumentException("OIDC issuer is required");String s=issuer.toString().trim();return s.endsWith("/")?s.substring(0,s.length()-1):s;}
    private static String clean(String v){if(v==null||v.isBlank())throw new IllegalArgumentException("OIDC configuration value is required");return v.trim();}
    private static String blankToNull(String v){return v==null||v.isBlank()?null:v.trim();}
    private static String truncate(String v,int max){if(v==null)return null;return v.length()<=max?v:v.substring(0,max);}
    private static IllegalStateException staleTest(){return new IllegalStateException("Identity configuration changed while the connection test was running. The stale test result was discarded; test the current draft again.");}
    public record ConnectionAdminView(UUID connectionId,UUID tenantId,String providerId,String providerType,String displayName,String issuerUri,String browserClientId,String apiAudience,String status,long configurationVersion,Instant createdAt,Instant updatedAt,Instant lastTestedAt,String lastTestStatus,String lastTestMessage){}
    public record ActivationResult(ConnectionAdminView active, ConnectionAdminView previousActive){}
}
