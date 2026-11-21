package com.sdv.platform.application.access;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Task 0033: a refused attempt to open a restricted document names the person's roles in its audit event, so the
 * Sensitivity report can count attempts by role. The roles decide nothing: the refusal and its message are unchanged,
 * and two clearances that open the same levels are equal whatever their roles.
 */
class RestrictedAttemptRolesTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final BusinessRecordRepository records = mock(BusinessRecordRepository.class);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final DocumentAccessGuard guard = new DocumentAccessGuard(records, audit);
    private final AuthorizationService authorization =
            new AuthorizationService(audit, new CapabilityPolicyService(mock(RoleCapabilityPolicyPort.class)));

    private Document highlyConfidential() {
        when(records.findMetadataSnapshots(any(), any(), any())).thenReturn(List.of());
        when(records.findRecordSensitivityLevel(any(), any(), any())).thenReturn(Optional.empty());
        return new Document(UUID.randomUUID(), TENANT, UUID.randomUUID(), UUID.randomUUID(), "d1", null, "secret.pdf",
                "application/pdf", "a".repeat(64), 10L, DocumentStatus.AVAILABLE, null, Instant.now(), null, Instant.now(),
                1, null, null, SensitivityLevel.HIGHLY_CONFIDENTIAL);
    }

    @Test
    void theRefusalNamesTheRolesFromTheSignIn() {
        Document document = highlyConfidential();
        SdvPrincipal person = new SdvPrincipal(TENANT, "bea", Set.of(SdvRole.DOCUMENT_STEWARD, SdvRole.BUSINESS_USER));
        Clearance clearance = authorization.clearance(person);
        assertEquals(List.of("BUSINESS_USER", "DOCUMENT_STEWARD"), clearance.roles(), "sorted names");

        CodedRefusal refusal = (CodedRefusal) assertThrows(CodedRefusal.Forbidden.class, () -> guard.requireOpenable(
                document, clearance, DocumentAccessGuard.Attempt.OPEN, "bea", "corr"));
        assertEquals("SDV-SEN-008", refusal.errorCode().code());
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        assertEquals("DOCUMENT_ACCESS_REFUSED", event.getValue().action());
        assertEquals("{\"level\":\"HIGHLY_CONFIDENTIAL\",\"attempt\":\"OPEN\",\"code\":\"SDV-SEN-008\","
                + "\"roles\":[\"BUSINESS_USER\",\"DOCUMENT_STEWARD\"]}", event.getValue().safeDetailsJson());
        assertFalse(event.getValue().safeDetailsJson().contains("secret"), "no file name");
    }

    @Test
    void withoutKnownRolesTheEventIsAsBefore() {
        Document document = highlyConfidential();
        assertThrows(CodedRefusal.Forbidden.class, () -> guard.requireOpenable(
                document, Clearance.STANDARD_ONLY, DocumentAccessGuard.Attempt.PAGE, "bea", "corr"));
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).append(event.capture());
        assertEquals("{\"level\":\"HIGHLY_CONFIDENTIAL\",\"attempt\":\"PAGE\",\"code\":\"SDV-SEN-008\"}",
                event.getValue().safeDetailsJson());
    }

    @Test
    void rolesNeverChangeWhatAClearanceOpens() {
        assertEquals(new Clearance(true, false), new Clearance(true, false, List.of("DOCUMENT_STEWARD")));
        assertEquals(new Clearance(true, false).hashCode(), new Clearance(true, false, List.of("X")).hashCode());
        assertNotEquals(new Clearance(true, false), new Clearance(true, true, List.of("DOCUMENT_STEWARD")));
        assertEquals(List.of(), new Clearance(false, false, null).roles());
    }
}
