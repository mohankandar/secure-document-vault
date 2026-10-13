package com.sdv.platform.web.foundation;

import com.fasterxml.jackson.databind.JsonNode;
import com.sdv.platform.application.metadata.BusinessMetadataBehaviorCommand;
import com.sdv.platform.application.metadata.MetadataFieldAdminService;
import com.sdv.platform.application.metadata.SourceFieldContractCommand;
import com.sdv.platform.application.metadata.UpsertMetadataFieldCommand;
import com.sdv.platform.application.source.CreateIngestionSourceCommand;
import com.sdv.platform.application.source.IngestionSourceAdminService;
import com.sdv.platform.application.source.SourceOperationsService;
import com.sdv.platform.application.source.UpdateIngestionSourceCommand;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.domain.source.IngestionSourceAuthType;
import com.sdv.platform.domain.source.IngestionSourceStatus;
import com.sdv.platform.domain.source.SourceLimits;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.RoleCapabilities;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class FoundationAdminController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final IngestionSourceAdminService sources;
    private final MetadataFieldAdminService metadata;
    private final SourceOperationsService sourceOperations;

    public FoundationAdminController(SecurityContextFacade security,
                                     AuthorizationService authorization,
                                     IngestionSourceAdminService sources,
                                     MetadataFieldAdminService metadata,
                                     SourceOperationsService sourceOperations) {
        this.security = security;
        this.authorization = authorization;
        this.sources = sources;
        this.metadata = metadata;
        this.sourceOperations = sourceOperations;
    }

    @GetMapping("/foundation/context")
    public Object context() {
        SdvPrincipal principal = security.requirePrincipal();
        return Map.of(
                "tenantId", principal.tenantId(),
                "subject", principal.subject(),
                "roles", principal.roles(),
                "capabilities", RoleCapabilities.capabilities(principal.roles()));
    }

    @GetMapping("/ingestion-sources")
    public Object listSources() {
        SdvPrincipal principal = require(Capability.CONFIGURE_INGESTION_SOURCES);
        return sources.list(principal.tenantId());
    }

    @GetMapping("/metadata-sources")
    public Object listMetadataSources() {
        SdvPrincipal principal = require(Capability.CONFIGURE_METADATA_SEARCH);
        return sources.list(principal.tenantId()).stream()
                .map(source -> new MetadataSourceSummary(source.sourceId(), source.sourceKey(), source.displayName(), source.status()))
                .toList();
    }

    @PostMapping("/ingestion-sources")
    @ResponseStatus(HttpStatus.CREATED)
    public Object createSource(@RequestBody CreateSourceRequest request) {
        SdvPrincipal principal = require(Capability.CONFIGURE_INGESTION_SOURCES);
        return sources.create(principal.tenantId(), request.toCommand(), principal.subject(), correlationId());
    }

    @GetMapping("/ingestion-sources/{sourceId}")
    public Object getSource(@PathVariable UUID sourceId) {
        SdvPrincipal principal = require(Capability.CONFIGURE_INGESTION_SOURCES);
        return sources.get(principal.tenantId(), sourceId);
    }

    @PutMapping("/ingestion-sources/{sourceId}")
    public Object updateSource(@PathVariable UUID sourceId, @RequestBody UpdateSourceRequest request) {
        SdvPrincipal principal = require(Capability.CONFIGURE_INGESTION_SOURCES);
        return sources.update(principal.tenantId(), sourceId, request.toCommand(), principal.subject(), correlationId());
    }

    @GetMapping("/ingestion-sources/{sourceId}/operations")
    public Object sourceOperations(@PathVariable UUID sourceId) {
        SdvPrincipal principal = require(Capability.CONFIGURE_INGESTION_SOURCES);
        return sourceOperations.summarize(principal.tenantId(), sourceId);
    }

    @GetMapping("/ingestion-sources/{sourceId}/metadata-fields")
    public Object listMetadataFields(@PathVariable UUID sourceId) {
        SdvPrincipal principal = require(Capability.CONFIGURE_METADATA_SEARCH);
        return metadata.listCurrent(principal.tenantId(), sourceId);
    }


    @GetMapping("/ingestion-sources/{sourceId}/source-fields")
    public Object listSourceFields(@PathVariable UUID sourceId) {
        SdvPrincipal principal = require(Capability.CONFIGURE_INGESTION_SOURCES);
        return metadata.listCurrent(principal.tenantId(), sourceId);
    }

    @PutMapping("/ingestion-sources/{sourceId}/source-fields/{fieldKey}")
    public Object createOrReviseSourceField(@PathVariable UUID sourceId,
                                            @PathVariable String fieldKey,
                                            @RequestBody SourceFieldRequest request) {
        SdvPrincipal principal = require(Capability.CONFIGURE_INGESTION_SOURCES);
        return metadata.createOrReviseSourceField(principal.tenantId(), sourceId, fieldKey, request.toCommand(),
                principal.subject(), correlationId());
    }

    @PutMapping("/metadata-sources/{sourceId}/fields/{fieldKey}")
    public Object reviseBusinessMetadataBehavior(@PathVariable UUID sourceId,
                                                  @PathVariable String fieldKey,
                                                  @RequestBody BusinessMetadataBehaviorRequest request) {
        SdvPrincipal principal = require(Capability.CONFIGURE_METADATA_SEARCH);
        return metadata.reviseBusinessBehavior(principal.tenantId(), sourceId, fieldKey, request.toCommand(),
                principal.subject(), correlationId());
    }

    @PutMapping("/ingestion-sources/{sourceId}/metadata-fields/{fieldKey}")
    public Object createOrReviseMetadataField(@PathVariable UUID sourceId,
                                               @PathVariable String fieldKey,
                                               @RequestBody MetadataFieldRequest request) {
        SdvPrincipal principal = require(Capability.CONFIGURE_METADATA_SEARCH);
        return metadata.createOrRevise(principal.tenantId(), sourceId, fieldKey, request.toCommand(),
                principal.subject(), correlationId());
    }

    @GetMapping("/ingestion-sources/{sourceId}/metadata-fields/{fieldKey}/history")
    public Object metadataHistory(@PathVariable UUID sourceId, @PathVariable String fieldKey) {
        SdvPrincipal principal = require(Capability.CONFIGURE_METADATA_SEARCH);
        return metadata.history(principal.tenantId(), sourceId, fieldKey);
    }

    private SdvPrincipal require(Capability capability) {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, capability);
        return principal;
    }

    private static String correlationId() {
        return MDC.get("correlationId");
    }

    public record MetadataSourceSummary(
            UUID sourceId,
            String sourceKey,
            String displayName,
            IngestionSourceStatus status) { }

    public record SourceLimitsRequest(
            Long maxPdfBytes,
            Integer maxPagesPerPdf,
            Integer maxDocumentsPerSubmission,
            Long maxAggregateSubmissionBytes,
            Integer incompleteAttentionHours) {
        SourceLimits toDomain() {
            return new SourceLimits(maxPdfBytes, maxPagesPerPdf, maxDocumentsPerSubmission,
                    maxAggregateSubmissionBytes, incompleteAttentionHours);
        }
    }

    public record CreateSourceRequest(
            String sourceKey,
            String displayName,
            IngestionSourceAuthType authType,
            String credentialReference,
            SourceLimitsRequest limits) {
        CreateIngestionSourceCommand toCommand() {
            return new CreateIngestionSourceCommand(sourceKey, displayName, authType, credentialReference,
                    limits == null ? new SourceLimits(null, null, null, null, null) : limits.toDomain());
        }
    }

    public record UpdateSourceRequest(
            String displayName,
            IngestionSourceStatus status,
            IngestionSourceAuthType authType,
            String credentialReference,
            SourceLimitsRequest limits) {
        UpdateIngestionSourceCommand toCommand() {
            return new UpdateIngestionSourceCommand(displayName, status, authType, credentialReference,
                    limits == null ? new SourceLimits(null, null, null, null, null) : limits.toDomain());
        }
    }


    public record SourceFieldRequest(
            String displayLabel,
            MetadataFieldType fieldType,
            boolean required,
            MetadataNormalization normalization,
            JsonNode validationRules) {
        SourceFieldContractCommand toCommand() {
            return new SourceFieldContractCommand(displayLabel, fieldType, required, normalization,
                    validationRules == null || validationRules.isNull() ? "{}" : validationRules.toString());
        }
    }

    public record BusinessMetadataBehaviorRequest(
            String displayLabel,
            boolean businessIdentifier,
            boolean searchable,
            MetadataNormalization normalization,
            MetadataSearchMode searchMode) {
        BusinessMetadataBehaviorCommand toCommand() {
            return new BusinessMetadataBehaviorCommand(displayLabel, businessIdentifier, searchable, normalization, searchMode);
        }
    }

    public record MetadataFieldRequest(
            String displayLabel,
            MetadataFieldType fieldType,
            boolean required,
            boolean businessIdentifier,
            boolean searchable,
            MetadataNormalization normalization,
            MetadataSearchMode searchMode,
            JsonNode validationRules) {
        UpsertMetadataFieldCommand toCommand() {
            return new UpsertMetadataFieldCommand(displayLabel, fieldType, required, businessIdentifier,
                    searchable, normalization, searchMode,
                    validationRules == null || validationRules.isNull() ? "{}" : validationRules.toString());
        }
    }
}
