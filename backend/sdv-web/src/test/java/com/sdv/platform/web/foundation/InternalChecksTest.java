package com.sdv.platform.web.foundation;

import com.sdv.platform.application.access.DocumentAccessGuard;
import com.sdv.platform.application.approvals.AccessRequestService;
import com.sdv.platform.application.legalhold.LegalHoldService;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.RecordSensitivityRepository;
import com.sdv.platform.application.port.StorageVolumeProbe;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.application.redaction.PdfSanitizingRedactor;
import com.sdv.platform.application.storage.StoragePlacementService;
import com.sdv.platform.application.storage.StorageVolumeAdminService;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantStatus;
import com.sdv.platform.persistence.redaction.JdbcDocumentRedactionRepository;
import com.sdv.platform.security.separation.ForbiddenGrant;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 0037, item 6: the SDV-SYS-016 checks that weren't IllegalStateException (report 0036, "Not done"). Each is
 * internal now (InternalCheckFailed, 500 SDV-SYS-016); the one a caller can trigger is a refusal with its own code.
 * The package-private ones are in InternalChecksPackagesTest classes next to their code.
 */
class InternalChecksTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static void internal(Executable check) {
        InternalCheckFailed failed = assertThrows(InternalCheckFailed.class, check);
        assertEquals(SdvErrorCode.SYS_016, failed.errorCode());
    }

    @Test
    void requiredArgumentsNoRequestCanLeaveOutAreInternal() {
        AccessRequestService approvals = new AccessRequestService(mock(com.sdv.platform.application.port.AccessRequestRepository.class),
                mock(com.sdv.platform.application.port.DocumentIntakeRepository.class),
                new DocumentAccessGuard(mock(BusinessRecordRepository.class), mock(AuditEventPort.class)),
                mock(com.sdv.platform.application.business.RecordReferences.class), mock(BusinessRecordRepository.class),
                mock(RecordSensitivityRepository.class), mock(com.sdv.platform.application.approvals.SensitiveAccessGrantStore.class),
                com.sdv.platform.application.approvals.ApprovalSettings.defaults(), mock(AuditEventPort.class),
                mock(PlatformTransactionManager.class), java.time.Clock.systemUTC());
        internal(() -> approvals.decide(null, "ba-1", null, UUID.randomUUID().toString(), AccessRequestService.Decision.APPROVE, "c"));
        internal(() -> approvals.decide(TENANT, " ", null, UUID.randomUUID().toString(), AccessRequestService.Decision.APPROVE, "c"));

        LegalHoldService holds = new LegalHoldService(mock(com.sdv.platform.application.port.LegalHoldRepository.class),
                new DocumentAccessGuard(mock(BusinessRecordRepository.class), mock(AuditEventPort.class)),
                mock(AuditEventPort.class), mock(PlatformTransactionManager.class));
        internal(() -> holds.release(null, "ba-1", null, UUID.randomUUID().toString(), "c"));
        internal(() -> holds.release(TENANT, null, null, UUID.randomUUID().toString(), "c"));
    }

    @Test
    void theDocumentAndCryptoInputChecksAreInternal() throws Exception {
        internal(() -> new PdfSanitizingRedactor().sanitize(null, List.of(), 10));
        internal(() -> new PdfSanitizingRedactor().sanitize(new byte[]{1}, List.of(), 10));
        var crypto = new com.sdv.platform.web.crypto.BoundStreamCrypto(null, null);
        internal(() -> crypto.encrypt(null, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
        internal(() -> crypto.decrypt(null, null, new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()));
        internal(() -> new Document(UUID.randomUUID(), TENANT, UUID.randomUUID(), UUID.randomUUID(), "d", "k", "a.pdf",
                "application/pdf", "0".repeat(64), 1L, DocumentStatus.AVAILABLE, 30, Instant.now(), null, null, 0, null, null, null));
        internal(() -> SensitivityLevel.effective(null, SensitivityLevel.STANDARD));
        DocumentAccessGuard guard = new DocumentAccessGuard(mock(BusinessRecordRepository.class), mock(AuditEventPort.class));
        Document doc = new Document(UUID.randomUUID(), TENANT, UUID.randomUUID(), UUID.randomUUID(), "d", "k", "a.pdf",
                "application/pdf", "0".repeat(64), 1L, DocumentStatus.AVAILABLE, 30, Instant.now(), null, null, 0, null, null,
                SensitivityLevel.STANDARD);
        internal(() -> guard.requirePrintOrDownload(doc, com.sdv.platform.application.access.Clearance.ALL,
                DocumentAccessGuard.Attempt.OPEN, "a", "c", com.sdv.platform.application.access.SensitiveAccessGrants.Use.CHECK));
    }

    @Test
    void theFixedRoleRulesAndTheRecoverySweepLimitsAreInternal() {
        internal(() -> new ForbiddenGrant<>(" ", Set.of("a"), Set.of("b")));
        internal(() -> new ForbiddenGrant<>("X", Set.of(), Set.of("b")));
        JdbcDocumentRedactionRepository redactions = new JdbcDocumentRedactionRepository(mock(NamedParameterJdbcTemplate.class));
        internal(() -> redactions.claimStaleForRecovery(0, 10, UUID.randomUUID()));
        internal(() -> redactions.claimStaleForRecovery(5, 101, UUID.randomUUID()));
    }

    @Test
    void theUnsupportedDefaultsAreInternal() {
        MetadataFieldDefinitionRepository fields = mock(MetadataFieldDefinitionRepository.class, CALLS_REAL_METHODS);
        internal(() -> fields.replaceDisplayOrder(TENANT, UUID.randomUUID(), List.of("a")));
        internal(() -> fields.replaceRecordTitleFields(TENANT, UUID.randomUUID(), List.of("a")));
        RecordSensitivityRepository levels = mock(RecordSensitivityRepository.class, CALLS_REAL_METHODS);
        internal(() -> levels.setLevel(TENANT, UUID.randomUUID(), SensitivityLevel.STANDARD, "s", "r", Instant.now()));
        StorageVolumeProbe probe = mock(StorageVolumeProbe.class, CALLS_REAL_METHODS);
        internal(() -> probe.check("C:/x"));
    }

    @Test
    void aNegativePlacementSizeIsTheCallersMistake_sto005_andInternalOnlyInsideUpload() throws Exception {
        StoragePlacementService placement = new StoragePlacementService(mock(StorageVolumeRepository.class), mock(StorageVolumeProbe.class));
        internal(() -> placement.select(TENANT, -1));

        TenantRepository tenants = mock(TenantRepository.class);
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        StorageVolumeAdminService admin = new StorageVolumeAdminService(tenants, mock(StorageVolumeRepository.class),
                mock(StorageVolumeProbe.class), placement, mock(AuditEventPort.class));
        CodedRefusal.BadRequest refused = assertThrows(CodedRefusal.BadRequest.class, () -> admin.previewPlacement(TENANT, -1));
        assertEquals(SdvErrorCode.STO_005, refused.errorCode());

        // Through the handler: 400 with the code and the sentence, not 500.
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Preview(admin)).setControllerAdvice(new FoundationExceptionHandler()).build();
        mvc.perform(get("/preview").param("requiredBytes", "-1")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SDV-STO-005"))
                .andExpect(jsonPath("$.message").value("requiredBytes must be 0 or more."));
        mvc.perform(get("/preview").param("requiredBytes", "0")).andExpect(status().isOk());
    }

    @RestController
    static class Preview {
        private final StorageVolumeAdminService admin;
        Preview(StorageVolumeAdminService admin) { this.admin = admin; }
        @GetMapping("/preview") Object preview(@RequestParam long requiredBytes) {
            try {
                return admin.previewPlacement(TENANT, requiredBytes);
            } catch (com.sdv.platform.application.storage.NoWritableStorageException none) {
                return "none";
            }
        }
    }
}
