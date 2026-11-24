package com.sdv.platform.web.ingestion;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.RoleCapabilityPolicyPort;
import com.sdv.platform.application.port.StorageVolumeProbe;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.application.storage.StoragePlacementService;
import com.sdv.platform.application.storage.StorageVolumeAdminService;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantStatus;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.CapabilityPolicyService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.storage.local.LocalFilesystemVolumeProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0010 Part B.3: POST /api/v1/admin/storage-volumes/check. */
class StorageFolderCheckTest {
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir Path temp;

    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final StorageVolumeRepository volumes = mock(StorageVolumeRepository.class);
    private final RoleCapabilityPolicyPort overrides = mock(RoleCapabilityPolicyPort.class);
    private StorageVolumeProbe probe;
    private StorageAdminController controller;

    @BeforeEach
    void setUp() {
        use(temp.toString());
        Tenant tenant = mock(Tenant.class);
        when(tenant.status()).thenReturn(TenantStatus.ACTIVE);
        when(tenants.findById(TENANT)).thenReturn(Optional.of(tenant));
        when(overrides.findOverrides(any(), anyString())).thenReturn(Map.of());
        when(volumes.findConfiguredRoots(anyString())).thenReturn(List.of());
    }

    /** Task 0062: the installer's allowed folders. */
    private void use(String allowedRoots) {
        probe = spy(new LocalFilesystemVolumeProbe(allowedRoots));
        controller = new StorageAdminController(new SecurityContextFacade(), new AuthorizationService(audit, new CapabilityPolicyService(overrides)),
                new StorageVolumeAdminService(tenants, volumes, probe, mock(StoragePlacementService.class), audit));
    }

    @Test
    void aFolderOutsideTheAllowedOnesIsRefusedAndNothingIsTouched() throws IOException {
        signIn(SdvRole.IT_ADMIN);
        Path inside = Files.createDirectory(temp.resolve("inside"));
        use(inside.toString());
        for (String path : List.of(temp.resolve("other").toString(), inside + "/../other", temp.toString(), inside + "-more", "other",
                "\\\\host\\share", "//host/share", "\\\\?\\C:\\x", "\\\\.\\C:", "")) {
            var result = (StorageVolumeProbe.FolderCheck) controller.checkFolder(new StorageAdminController.FolderCheckRequest(path));
            assertFalse(result.writable(), path);
            assertEquals(StorageVolumeAdminService.FOLDER_NOT_ALLOWED, result.problem(), path);
            assertEquals(path.trim(), result.normalizedRootPath(), "nothing about the server's paths");
            assertNull(result.usableBytes(), path);
            var refused = assertThrows(com.sdv.platform.domain.error.CodedRefusal.BadRequest.class, () -> controller.create(
                    new StorageAdminController.CreateStorageVolumeRequest("v", path, com.sdv.platform.domain.storage.StorageVolumeState.ACTIVE, 1, 80, 95)), path);
            assertEquals("SDV-STO-008", refused.errorCode().code());
            assertEquals(StorageVolumeAdminService.FOLDER_NOT_ALLOWED, refused.getMessage());
        }
        assertEquals(1, entries(temp), "only the allowed folder");
        assertTrue(check(inside.resolve("a").resolve("b")).writable());
    }

    @Test
    void aLinkInsideAnAllowedFolderIsRefused() throws Exception {
        signIn(SdvRole.IT_ADMIN);
        Path inside = Files.createDirectory(temp.resolve("inside"));
        Path elsewhere = Files.createDirectory(temp.resolve("elsewhere"));
        use(inside.toString());
        Path link = inside.resolve("link");
        if (System.getProperty("os.name").startsWith("Windows")) {
            // A junction needs no administrator.
            assertEquals(0, new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), elsewhere.toString()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor());
        } else {
            Files.createSymbolicLink(link, elsewhere);
        }
        for (Path path : List.of(link, link.resolve("volume"))) {
            assertEquals(StorageVolumeAdminService.FOLDER_NOT_ALLOWED, check(path).problem());
        }
        assertEquals(0, entries(elsewhere));
        Files.delete(link);
    }

    @Test
    void withoutASettingOnlyTheDataFolderIsAllowedAndABadSettingStopsTheStart() {
        var none = new LocalFilesystemVolumeProbe("");
        assertTrue(none.allowed("data/volume-a"));
        assertFalse(none.allowed("other"));
        assertFalse(none.allowed("data/../other"));
        for (String bad : List.of(" ; ", "\\\\host\\share", "data;//host/share")) {
            assertThrows(com.sdv.platform.domain.error.StartupFailure.class, () -> new LocalFilesystemVolumeProbe(bad), bad);
        }
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(SdvRole role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new SdvPrincipal(TENANT, "person-" + role.name().toLowerCase(), Set.of(role)), null, List.of()));
    }

    private StorageVolumeProbe.FolderCheck check(Path folder) {
        return (StorageVolumeProbe.FolderCheck) controller.checkFolder(new StorageAdminController.FolderCheckRequest(folder.toString()));
    }

    private static long entries(Path folder) throws IOException {
        try (Stream<Path> all = Files.walk(folder)) {
            return all.count() - 1;
        }
    }

    @Test
    void refusedWithoutTheStoragePermission() {
        for (SdvRole role : List.of(SdvRole.BUSINESS_USER, SdvRole.DOCUMENT_STEWARD, SdvRole.BUSINESS_ADMIN, SdvRole.PLATFORM_OPERATIONS_ADMIN)) {
            signIn(role);
            assertThrows(AccessDeniedException.class, () -> check(temp.resolve("new")), role.name());
        }
        verify(probe, never()).check(anyString());
        verify(audit, never()).append(argThat(e -> e.action().equals("STORAGE_FOLDER_CHECKED")));
        assertFalse(Files.exists(temp.resolve("new")));
    }

    @Test
    void aNewFolderIsWritableAndNothingIsLeftBehind() throws IOException {
        signIn(SdvRole.IT_ADMIN);
        var result = check(temp.resolve("volumes").resolve("primary-b"));
        assertTrue(result.writable());
        assertNull(result.problem());
        assertTrue(result.usableBytes() != null && result.usableBytes() > 0);
        assertEquals(0, entries(temp), "the check removes its test file and the folders it created");
        verify(audit).append(argThat(e -> e.action().equals("STORAGE_FOLDER_CHECKED") && e.outcome() == AuditOutcome.SUCCESS
                && e.safeDetailsJson().equals("{\"writable\":true}") && !e.targetId().contains("primary-b")));
    }

    @Test
    void anExistingFolderKeepsItsContents() throws IOException {
        signIn(SdvRole.IT_ADMIN);
        Path folder = Files.createDirectories(temp.resolve("existing"));
        Files.writeString(folder.resolve("keep.txt"), "kept");
        assertTrue(check(folder).writable());
        assertEquals(1, entries(folder), "only the file that was already there");
        assertEquals("kept", Files.readString(folder.resolve("keep.txt")));
    }

    @Test
    void anUnwritableFolderIsReportedPlainly() throws IOException {
        signIn(SdvRole.IT_ADMIN);
        Path file = Files.writeString(temp.resolve("not-a-folder.txt"), "x");
        var result = check(file.resolve("volume"));
        assertFalse(result.writable());
        assertEquals("Part of this path is a file, not a folder.", result.problem());
        assertEquals(1, entries(temp), "nothing created next to the file");
        verify(audit).append(argThat(e -> e.action().equals("STORAGE_FOLDER_CHECKED") && e.outcome() == AuditOutcome.FAILED));
    }

    @Test
    void aFolderOverlappingAnotherVolumeIsRefusedWithoutWriting() {
        signIn(SdvRole.IT_ADMIN);
        when(volumes.findConfiguredRoots(anyString())).thenReturn(List.of(
                new StorageVolumeRepository.ConfiguredRoot(UUID.randomUUID(), temp.toAbsolutePath().normalize().toString())));
        var result = check(temp.resolve("inside"));
        assertFalse(result.writable());
        assertEquals(StorageVolumeAdminService.FOLDER_NOT_ALLOWED, result.problem(), "the same answer as a folder that is not allowed");
        verify(probe, never()).check(anyString());
        assertFalse(Files.exists(temp.resolve("inside")));
    }
}
