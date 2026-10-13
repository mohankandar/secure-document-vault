package com.sdv.platform.application.operations;

import com.sdv.platform.application.port.KeyServiceHealthProbe;
import com.sdv.platform.application.port.OperationalRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.application.storage.StorageVolumeAdminService;
import com.sdv.platform.application.storage.StorageVolumeView;
import com.sdv.platform.domain.storage.StorageCapacitySnapshot;
import com.sdv.platform.domain.storage.StorageVolumeState;
import com.sdv.platform.domain.tenant.TenantStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OperationsService {
    private static final int INCIDENT_LIMIT = 100;
    private static final Duration PROCESSING_STUCK_AFTER = Duration.ofMinutes(15);
    private static final Duration TRANSITION_STUCK_AFTER = Duration.ofMinutes(10);

    private final OperationalRepository repository;
    private final StorageVolumeAdminService storage;
    private final KeyServiceHealthProbe keyHealth;
    private final TenantRepository tenants;
    private final Clock clock = Clock.systemUTC();

    public OperationsService(OperationalRepository repository,
                             StorageVolumeAdminService storage,
                             KeyServiceHealthProbe keyHealth,
                             TenantRepository tenants) {
        this.repository = repository;
        this.storage = storage;
        this.keyHealth = keyHealth;
        this.tenants = tenants;
    }

    public CapacityBanner capacityBanner(UUID tenantId) {
        requireActiveTenant(tenantId);
        List<StorageVolumeView> views;
        try {
            views = storage.list(tenantId);
        } catch (RuntimeException ex) {
            return new CapacityBanner("ATTENTION", true,
                    "Storage capacity cannot be verified right now. Technical administrators should review storage health.",
                    0, 1, clock.instant());
        }

        int warning = 0;
        int critical = 0;
        for (StorageVolumeView view : views) {
            if (view.volume().state() == StorageVolumeState.OFFLINE) continue;
            if (view.observationError() != null || view.stopWriteThresholdReached()) critical++;
            else if (view.warningThresholdReached()) warning++;
        }
        if (critical > 0) {
            return new CapacityBanner("CRITICAL", true,
                    "Document storage requires immediate technical attention. New document custody may be restricted.",
                    warning, critical, clock.instant());
        }
        if (warning > 0) {
            return new CapacityBanner("WARNING", true,
                    "Document storage has reached its configured capacity warning threshold.",
                    warning, 0, clock.instant());
        }
        return new CapacityBanner("NORMAL", false, null, 0, 0, clock.instant());
    }

    public OperationsSummary summary(UUID tenantId) {
        requireActiveTenant(tenantId);
        Instant now = clock.instant();
        ComponentHealth application = new ComponentHealth("application", "UP", "SDV application is serving requests.", now);
        ComponentHealth database = databaseHealth(now);
        ComponentHealth key = keyHealth(now);

        SectionResult<StorageVolumeStatus> storageRead = storageStatuses(tenantId);
        IncidentResult<OperationalRepository.ProcessingFailure> failures = incidentSection(
                "processing-failures",
                () -> repository.countProcessingFailures(tenantId),
                () -> repository.findProcessingFailures(tenantId, INCIDENT_LIMIT));
        IncidentResult<OperationalRepository.StuckDocument> stuck = incidentSection(
                "stuck-documents",
                () -> repository.countStuckDocuments(
                        tenantId, now.minus(PROCESSING_STUCK_AFTER), now.minus(TRANSITION_STUCK_AFTER)),
                () -> repository.findStuckDocuments(
                        tenantId, now.minus(PROCESSING_STUCK_AFTER), now.minus(TRANSITION_STUCK_AFTER), INCIDENT_LIMIT));
        IncidentResult<OperationalRepository.AttentionSubmission> incomplete = incidentSection(
                "attention-submissions",
                () -> repository.countAttentionSubmissions(tenantId, now),
                () -> repository.findAttentionSubmissions(tenantId, now, INCIDENT_LIMIT));

        List<OperationalDataObservation> observations = List.of(
                storageRead.observation(), failures.observation(), stuck.observation(), incomplete.observation());

        boolean dataDegraded = observations.stream().anyMatch(OperationalDataObservation::degraded);
        boolean storageCritical = storageRead.items().stream()
                .anyMatch(v -> v.health().equals("DOWN") || v.stopWriteThresholdReached());
        boolean storageWarning = storageRead.items().stream().anyMatch(StorageVolumeStatus::warningThresholdReached);
        boolean incidentsPresent = positive(failures.count()) || positive(stuck.count()) || positive(incomplete.count());

        String overall = database.status().equals("DOWN") || key.status().equals("DOWN") || storageCritical
                ? "DOWN"
                : (dataDegraded || key.status().equals("NOT_CONFIGURED") || storageWarning || incidentsPresent)
                    ? "ATTENTION" : "UP";

        return new OperationsSummary(overall, now, application, database, key, observations, storageRead.items(),
                failures.count(), failures.items(), stuck.count(), stuck.items(), incomplete.count(), incomplete.items());
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private <T> IncidentResult<T> incidentSection(String section, LongSupplier countSupplier, ListSupplier<T> listSupplier) {
        try {
            long count = countSupplier.get();
            List<T> items = List.copyOf(listSupplier.get());
            return new IncidentResult<>(count, items, OperationalDataObservation.available(section));
        } catch (RuntimeException ex) {
            return new IncidentResult<>(null, List.of(), OperationalDataObservation.degraded(section));
        }
    }

    private void requireActiveTenant(UUID tenantId) {
        var tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Tenant not found."));
        if (tenant.status() != TenantStatus.ACTIVE) throw new IllegalStateException("Tenant is not active.");
    }

    private ComponentHealth databaseHealth(Instant now) {
        try {
            return repository.databaseAvailable()
                    ? new ComponentHealth("database", "UP", "PostgreSQL check succeeded.", now)
                    : new ComponentHealth("database", "DOWN", "PostgreSQL check failed.", now);
        } catch (RuntimeException ex) {
            return new ComponentHealth("database", "DOWN", "PostgreSQL check failed.", now);
        }
    }

    private ComponentHealth keyHealth(Instant now) {
        try {
            KeyServiceHealthProbe.Health health = keyHealth.check();
            return new ComponentHealth("key-service", health.status().name(), health.message(), health.observedAt());
        } catch (RuntimeException ex) {
            return new ComponentHealth("key-service", "DOWN", "Key service health check failed.", now);
        }
    }

    private SectionResult<StorageVolumeStatus> storageStatuses(UUID tenantId) {
        try {
            List<StorageVolumeStatus> items = storage.list(tenantId).stream().map(view -> {
                StorageCapacitySnapshot capacity = view.capacity();
                String health;
                if (view.volume().state() == StorageVolumeState.OFFLINE) health = "OFFLINE";
                else if (view.observationError() != null) health = "DOWN";
                else if (view.stopWriteThresholdReached()) health = "CRITICAL";
                else if (view.warningThresholdReached()) health = "WARNING";
                else health = "UP";
                return new StorageVolumeStatus(
                        view.volume().volumeId(), view.volume().displayName(), view.volume().providerId(),
                        view.volume().rootPath(), view.volume().state().name(), view.volume().priority(),
                        view.volume().warningPercent(), view.volume().stopWritePercent(), health,
                        capacity == null ? null : capacity.totalBytes(),
                        capacity == null ? null : capacity.usableBytes(),
                        capacity == null ? null : capacity.usedPercent(),
                        capacity != null && capacity.writable(),
                        view.warningThresholdReached(), view.stopWriteThresholdReached(), view.observationError(),
                        capacity == null ? null : capacity.observedAt());
            }).toList();
            return new SectionResult<>(items, OperationalDataObservation.available("storage-volumes"));
        } catch (RuntimeException ex) {
            return new SectionResult<>(List.of(), OperationalDataObservation.degraded("storage-volumes"));
        }
    }

    private record SectionResult<T>(List<T> items, OperationalDataObservation observation) { }
    private record IncidentResult<T>(Long count, List<T> items, OperationalDataObservation observation) { }

    @FunctionalInterface private interface LongSupplier { long get(); }
    @FunctionalInterface private interface ListSupplier<T> { List<T> get(); }

    public record CapacityBanner(
            String severity,
            boolean visible,
            String message,
            int warningVolumeCount,
            int criticalVolumeCount,
            Instant observedAt) { }

    public record ComponentHealth(String component, String status, String message, Instant observedAt) { }

    public record OperationalDataObservation(String section, String status, String message) {
        private static OperationalDataObservation available(String section) {
            return new OperationalDataObservation(section, "AVAILABLE", "Operational data is available.");
        }
        private static OperationalDataObservation degraded(String section) {
            return new OperationalDataObservation(section, "DEGRADED",
                    "Operational data could not be read completely. Retry or investigate the underlying service.");
        }
        public boolean degraded() { return "DEGRADED".equals(status); }
    }

    public record StorageVolumeStatus(
            UUID volumeId,
            String displayName,
            String providerId,
            String rootPath,
            String state,
            int priority,
            int warningPercent,
            int stopWritePercent,
            String health,
            Long totalBytes,
            Long usableBytes,
            Integer usedPercent,
            boolean writable,
            boolean warningThresholdReached,
            boolean stopWriteThresholdReached,
            String observationError,
            Instant observedAt) { }

    public record OperationsSummary(
            String overallStatus,
            Instant observedAt,
            ComponentHealth application,
            ComponentHealth database,
            ComponentHealth keyService,
            List<OperationalDataObservation> dataObservations,
            List<StorageVolumeStatus> storageVolumes,
            Long processingFailureCount,
            List<OperationalRepository.ProcessingFailure> processingFailures,
            Long stuckDocumentCount,
            List<OperationalRepository.StuckDocument> stuckDocuments,
            Long attentionSubmissionCount,
            List<OperationalRepository.AttentionSubmission> attentionSubmissions) {
        public OperationsSummary {
            dataObservations = List.copyOf(dataObservations);
            storageVolumes = List.copyOf(storageVolumes);
            processingFailures = List.copyOf(processingFailures);
            stuckDocuments = List.copyOf(stuckDocuments);
            attentionSubmissions = List.copyOf(attentionSubmissions);
        }
    }
}
