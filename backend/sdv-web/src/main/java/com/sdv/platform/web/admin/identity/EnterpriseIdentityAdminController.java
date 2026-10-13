package com.sdv.platform.web.admin.identity;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.persistence.identity.JdbcOidcConnectionBindingRepository;
import com.sdv.platform.persistence.identity.JdbcOidcConnectionBindingRepository.ConnectionAdminView;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/enterprise-identity")
public class EnterpriseIdentityAdminController {
    private final SecurityContextFacade security; private final AuthorizationService authorization; private final JdbcOidcConnectionBindingRepository repository; private final OidcDiscoveryTester tester; private final AuditEventPort audit;
    public EnterpriseIdentityAdminController(SecurityContextFacade security,AuthorizationService authorization,JdbcOidcConnectionBindingRepository repository,OidcDiscoveryTester tester,AuditEventPort audit){this.security=security;this.authorization=authorization;this.repository=repository;this.tester=tester;this.audit=audit;}

    @GetMapping public Overview overview(){SdvPrincipal p=requireTenantIt();return overviewFor(p);}

    @PutMapping("/draft") public Overview saveDraft(@RequestBody DraftRequest body){SdvPrincipal p=requireTenantIt();if(body==null)throw new IllegalArgumentException("Draft configuration is required.");ConnectionAdminView saved=repository.saveDraft(p.tenantId(),defaultProvider(body.providerId()),body.displayName(),parseIssuer(body.issuerUri()),body.browserClientId(),body.apiAudience(),p.subject());appendAudit(p,"ENTERPRISE_IDENTITY_DRAFT_SAVED",saved.connectionId(),AuditOutcome.SUCCESS,"{\"version\":"+saved.configurationVersion()+"}");return overviewFor(p);}

    @PostMapping("/draft/test") public Overview testDraft(){SdvPrincipal p=requireTenantIt();ConnectionAdminView staged=repository.findStagedForTenant(p.tenantId()).orElseThrow(()->new IllegalArgumentException("Save a draft identity configuration before testing it."));OidcDiscoveryTester.TestResult result=tester.test(staged.issuerUri());ConnectionAdminView tested=repository.recordTest(p.tenantId(),staged.connectionId(),staged.configurationVersion(),result.passed(),result.message(),p.subject());appendAudit(p,"ENTERPRISE_IDENTITY_CONNECTION_TESTED",tested.connectionId(),result.passed()?AuditOutcome.SUCCESS:AuditOutcome.FAILED,"{\"version\":"+tested.configurationVersion()+",\"result\":\""+(result.passed()?"PASSED":"FAILED")+"\"}");return overviewFor(p);}

    @PostMapping("/draft/activate") public Overview activateDraft(@RequestBody ActivationRequest body){
        SdvPrincipal p=requireTenantIt(); if(body==null||!"ACTIVATE".equals(body.confirmation()))throw new IllegalArgumentException("Activation requires the exact confirmation value ACTIVATE.");
        ConnectionAdminView staged=repository.findStagedForTenant(p.tenantId()).orElseThrow(()->new IllegalArgumentException("No staged identity configuration is available to activate."));
        if(!staged.connectionId().equals(body.connectionId()))throw new IllegalStateException("Activation target is not the current staged identity configuration.");
        try{var result=repository.activateValidated(p.tenantId(),body.connectionId(),body.expectedConfigurationVersion(),p.subject());appendAudit(p,"ENTERPRISE_IDENTITY_CONNECTION_ACTIVATED",result.active().connectionId(),AuditOutcome.SUCCESS,"{\"version\":"+result.active().configurationVersion()+",\"previousConnectionId\":\""+result.previousActive().connectionId()+"\"}");return overviewFor(p);}catch(RuntimeException ex){appendAudit(p,"ENTERPRISE_IDENTITY_CONNECTION_ACTIVATION_FAILED",body.connectionId(),AuditOutcome.FAILED,"{\"version\":"+body.expectedConfigurationVersion()+"}");throw ex;}
    }

    @PostMapping("/rollback") public Overview rollback(@RequestBody RollbackRequest body){
        SdvPrincipal p=requireTenantIt(); if(body==null||!"ROLLBACK".equals(body.confirmation()))throw new IllegalArgumentException("Rollback requires the exact confirmation value ROLLBACK.");
        try{var result=repository.rollbackToKnownGood(p.tenantId(),body.connectionId(),body.expectedConfigurationVersion(),p.subject());appendAudit(p,"ENTERPRISE_IDENTITY_CONNECTION_ROLLED_BACK",result.active().connectionId(),AuditOutcome.SUCCESS,"{\"version\":"+result.active().configurationVersion()+",\"replacedConnectionId\":\""+result.previousActive().connectionId()+"\"}");return overviewFor(p);}catch(RuntimeException ex){appendAudit(p,"ENTERPRISE_IDENTITY_CONNECTION_ROLLBACK_FAILED",body.connectionId(),AuditOutcome.FAILED,"{\"version\":"+body.expectedConfigurationVersion()+"}");throw ex;}
    }

    private Overview overviewFor(SdvPrincipal p){return new Overview(repository.findActiveForTenant(p.tenantId()).orElse(null),repository.findStagedForTenant(p.tenantId()).orElse(null),repository.findRollbackCandidatesForTenant(p.tenantId()),true,900);}
    private SdvPrincipal requireTenantIt(){SdvPrincipal p=security.requirePrincipal();authorization.require(p,Capability.CONFIGURE_TENANT_AUTH);return p;}
    private void appendAudit(SdvPrincipal p,String action,UUID id,AuditOutcome outcome,String details){audit.append(new AuditEvent(UUID.randomUUID(),p.tenantId(),"USER",p.subject(),action,"ENTERPRISE_IDENTITY_CONNECTION",id==null?"UNKNOWN":id.toString(),Instant.now(),MDC.get("correlationId"),outcome,details));}
    private static URI parseIssuer(String value){if(value==null||value.isBlank())throw new IllegalArgumentException("Issuer URI is required.");return URI.create(value.trim());}
    private static String defaultProvider(String value){return value==null||value.isBlank()?"customer-oidc":value.trim();}
    public record DraftRequest(String providerId,String displayName,String issuerUri,String browserClientId,String apiAudience){}
    public record ActivationRequest(UUID connectionId,long expectedConfigurationVersion,String confirmation){}
    public record RollbackRequest(UUID connectionId,long expectedConfigurationVersion,String confirmation){}
    public record Overview(ConnectionAdminView active,ConnectionAdminView staged,List<ConnectionAdminView> rollbackCandidates,boolean activationSupported,long activationMaxTestAgeSeconds){}
}
