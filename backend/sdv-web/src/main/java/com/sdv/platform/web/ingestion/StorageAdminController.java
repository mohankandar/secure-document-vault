package com.sdv.platform.web.ingestion;

import com.sdv.platform.application.storage.CreateStorageVolumeCommand;
import com.sdv.platform.application.storage.StoragePlacementService;
import com.sdv.platform.application.storage.StorageVolumeAdminService;
import com.sdv.platform.application.storage.UpdateStorageVolumeCommand;
import com.sdv.platform.domain.storage.StorageVolumeState;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/storage-volumes")
public class StorageAdminController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final StorageVolumeAdminService storage;

    public StorageAdminController(SecurityContextFacade security,
                                  AuthorizationService authorization,
                                  StorageVolumeAdminService storage) {
        this.security = security;
        this.authorization = authorization;
        this.storage = storage;
    }

    @GetMapping
    public Object list() {
        SdvPrincipal principal = requireAdmin();
        return storage.list(principal.tenantId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Object create(@RequestBody CreateStorageVolumeRequest request) {
        SdvPrincipal principal = requireAdmin();
        return storage.create(principal.tenantId(), request.toCommand(), principal.subject(), correlationId());
    }

    @GetMapping("/{volumeId}")
    public Object get(@PathVariable UUID volumeId) {
        SdvPrincipal principal = requireAdmin();
        return storage.get(principal.tenantId(), volumeId);
    }

    @PutMapping("/{volumeId}")
    public Object update(@PathVariable UUID volumeId, @RequestBody UpdateStorageVolumeRequest request) {
        SdvPrincipal principal = requireAdmin();
        return storage.update(principal.tenantId(), volumeId, request.toCommand(), principal.subject(), correlationId());
    }

    @GetMapping("/placement-preview")
    public StoragePlacementService.PlacementDecision preview(@RequestParam long requiredBytes) {
        SdvPrincipal principal = requireAdmin();
        return storage.previewPlacement(principal.tenantId(), requiredBytes);
    }

    private SdvPrincipal requireAdmin() {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, Capability.CONFIGURE_STORAGE_KEY_PROVIDER);
        return principal;
    }

    private static String correlationId() {
        return MDC.get("correlationId");
    }

    public record CreateStorageVolumeRequest(
            String displayName,
            String rootPath,
            StorageVolumeState state,
            int priority,
            Integer warningPercent,
            Integer stopWritePercent) {
        CreateStorageVolumeCommand toCommand() {
            return new CreateStorageVolumeCommand(displayName, rootPath, state, priority, warningPercent, stopWritePercent);
        }
    }

    public record UpdateStorageVolumeRequest(
            String displayName,
            StorageVolumeState state,
            int priority,
            int warningPercent,
            int stopWritePercent) {
        UpdateStorageVolumeCommand toCommand() {
            return new UpdateStorageVolumeCommand(displayName, state, priority, warningPercent, stopWritePercent);
        }
    }
}
