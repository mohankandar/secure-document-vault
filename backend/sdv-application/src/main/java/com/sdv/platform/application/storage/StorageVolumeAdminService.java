package com.sdv.platform.application.storage;

import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.StorageVolumeProbe;
import com.sdv.platform.application.port.StorageVolumeRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;
import com.sdv.platform.domain.storage.StorageCapacitySnapshot;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.storage.StorageVolumeState;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class StorageVolumeAdminService {
    public static final String LOCAL_FILESYSTEM = "LOCAL_FILESYSTEM";

    private final TenantRepository tenants;
    private final StorageVolumeRepository volumes;
    private final StorageVolumeProbe probe;
    private final StoragePlacementService placement;
    private final AuditEventPort audit;
    private final Clock clock;

    @Autowired
    public StorageVolumeAdminService(TenantRepository tenants,
                                     StorageVolumeRepository volumes,
                                     StorageVolumeProbe probe,
                                     StoragePlacementService placement,
                                     AuditEventPort audit) {
        this(tenants, volumes, probe, placement, audit, Clock.systemUTC());
    }

    StorageVolumeAdminService(TenantRepository tenants,
                              StorageVolumeRepository volumes,
                              StorageVolumeProbe probe,
                              StoragePlacementService placement,
                              AuditEventPort audit,
                              Clock clock) {
        this.tenants = tenants;
        this.volumes = volumes;
        this.probe = probe;
        this.placement = placement;
        this.audit = audit;
        this.clock = clock;
    }

    public List<StorageVolumeView> list(UUID tenantId) {
        requireActiveTenant(tenantId);
        return volumes.findAll(tenantId).stream().map(this::view).toList();
    }

    public StorageVolumeView get(UUID tenantId, UUID volumeId) {
        requireActiveTenant(tenantId);
        return view(requireVolume(tenantId, volumeId));
    }

    @Transactional
    public StorageVolumeView create(UUID tenantId,
                                    CreateStorageVolumeCommand command,
                                    String actorId,
                                    String correlationId) {
        Tenant tenant = requireActiveTenant(tenantId);
        validateName(command == null ? null : command.displayName());
        if (command.state() == null) throw new IllegalArgumentException("state is required.");
        if (command.priority() < 0) throw new IllegalArgumentException("priority cannot be negative.");

        int warning = command.warningPercent() == null
                ? tenant.guardrails().storageWarningPercent() : command.warningPercent();
        int stopWrite = command.stopWritePercent() == null
                ? tenant.guardrails().storageStopWritePercent() : command.stopWritePercent();
        validateThresholds(warning, stopWrite);

        volumes.lockConfiguration();
        String normalizedCandidate = probe.normalize(command.rootPath());
        rejectOverlappingRoot(normalizedCandidate);
        StorageVolumeProbe.PreparedRoot prepared = probe.prepare(command.rootPath());
        // Recheck the canonical/real path after preparation so symlink/canonicalization changes cannot
        // bypass the pre-touch overlap boundary. Configuration writes remain serialized by the table lock.
        rejectOverlappingRoot(prepared.normalizedRootPath());

        Instant now = clock.instant();
        StorageVolume volume = new StorageVolume(
                UUID.randomUUID(), tenantId, LOCAL_FILESYSTEM, command.displayName().trim(),
                prepared.normalizedRootPath(), command.state(), command.priority(), warning, stopWrite,
                now, now);
        volumes.insert(volume);
        audit.append(auditEvent(tenantId, actorId, "STORAGE_VOLUME_CREATED", volume.volumeId(), correlationId));
        return view(volume);
    }

    @Transactional
    public StorageVolumeView update(UUID tenantId,
                                    UUID volumeId,
                                    UpdateStorageVolumeCommand command,
                                    String actorId,
                                    String correlationId) {
        requireActiveTenant(tenantId);
        StorageVolume existing = requireVolume(tenantId, volumeId);
        if (command == null) throw new IllegalArgumentException("storage volume update is required.");
        validateName(command.displayName());
        if (command.state() == null) throw new IllegalArgumentException("state is required.");
        if (command.priority() < 0) throw new IllegalArgumentException("priority cannot be negative.");
        validateThresholds(command.warningPercent(), command.stopWritePercent());

        // rootPath/providerId are deliberately immutable. Existing document references must remain readable
        // from the same configured volume; V1 has no automatic migration/rebalance.
        StorageVolume updated = new StorageVolume(
                existing.volumeId(), existing.tenantId(), existing.providerId(), command.displayName().trim(),
                existing.rootPath(), command.state(), command.priority(),
                command.warningPercent(), command.stopWritePercent(), existing.createdAt(), clock.instant());
        volumes.update(updated);
        audit.append(auditEvent(tenantId, actorId, "STORAGE_VOLUME_UPDATED", volumeId, correlationId));
        return view(updated);
    }

    public StoragePlacementService.PlacementDecision previewPlacement(UUID tenantId, long requiredBytes) {
        requireActiveTenant(tenantId);
        return placement.select(tenantId, requiredBytes);
    }

    private void rejectOverlappingRoot(String candidateRoot) {
        boolean overlapsExistingRoot = volumes.findConfiguredRoots(LOCAL_FILESYSTEM).stream()
                .anyMatch(existing -> probe.overlaps(candidateRoot, existing.rootPath()));
        if (overlapsExistingRoot) {
            throw new IllegalArgumentException("The configured storage root overlaps another SDV volume root.");
        }
    }

    private StorageVolumeView view(StorageVolume volume) {
        if (volume.state() == StorageVolumeState.OFFLINE) {
            return new StorageVolumeView(volume, null, false, false, "Volume is configured OFFLINE.");
        }
        try {
            StorageCapacitySnapshot capacity = probe.inspect(volume.rootPath());
            boolean warning = capacity.usedPercent() >= volume.warningPercent();
            boolean stop = !capacity.writable() || capacity.usedPercent() >= volume.stopWritePercent();
            return new StorageVolumeView(volume, capacity, warning, stop, null);
        } catch (RuntimeException ex) {
            return new StorageVolumeView(volume, null, false, true, "Storage root is unavailable or unreadable.");
        }
    }

    private Tenant requireActiveTenant(UUID tenantId) {
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new NoSuchElementException("Tenant not found."));
        if (tenant.status() != TenantStatus.ACTIVE) throw new IllegalStateException("Tenant is not active.");
        return tenant;
    }

    private StorageVolume requireVolume(UUID tenantId, UUID volumeId) {
        return volumes.findById(tenantId, volumeId)
                .orElseThrow(() -> new NoSuchElementException("Storage volume not found."));
    }

    private static void validateName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("displayName is required.");
        if (value.trim().length() > 200) throw new IllegalArgumentException("displayName exceeds 200 characters.");
    }

    private static void validateThresholds(int warning, int stopWrite) {
        if (warning < 1 || warning > 99) throw new IllegalArgumentException("warningPercent must be between 1 and 99.");
        if (stopWrite < 2 || stopWrite > 100) throw new IllegalArgumentException("stopWritePercent must be between 2 and 100.");
        if (stopWrite <= warning) throw new IllegalArgumentException("stopWritePercent must be greater than warningPercent.");
    }

    private AuditEvent auditEvent(UUID tenantId, String actorId, String action, UUID targetId, String correlationId) {
        return new AuditEvent(UUID.randomUUID(), tenantId, "USER", actorId, action,
                "STORAGE_VOLUME", targetId.toString(), clock.instant(), correlationId,
                AuditOutcome.SUCCESS, "{}");
    }
}
