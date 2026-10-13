package com.sdv.platform.web.business;

import com.sdv.platform.application.business.BusinessExperienceService;
import com.sdv.platform.application.business.BusinessExperienceView;
import com.sdv.platform.security.Capability;
import com.sdv.platform.security.auth.AuthorizationService;
import com.sdv.platform.security.auth.SdvPrincipal;
import com.sdv.platform.security.auth.SecurityContextFacade;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/business")
public class BusinessExperienceController {
    private final SecurityContextFacade security;
    private final AuthorizationService authorization;
    private final BusinessExperienceService business;

    public BusinessExperienceController(
            SecurityContextFacade security,
            AuthorizationService authorization,
            BusinessExperienceService business) {
        this.security = security;
        this.authorization = authorization;
        this.business = business;
    }

    @GetMapping("/search-schema")
    public BusinessExperienceView.SearchSchema searchSchema() {
        SdvPrincipal principal = requireSearch();
        return business.searchSchema(principal.tenantId());
    }

    @PostMapping("/search")
    public BusinessExperienceView.SearchResponse search(@RequestBody SearchRequest request) {
        SdvPrincipal principal = requireSearch();
        return business.search(
                principal.tenantId(),
                request.sourceId(),
                request.criteria() == null ? Map.of() : new LinkedHashMap<>(request.criteria()),
                request.dateFrom(),
                request.dateTo(),
                request.lastUpdatedFrom(),
                request.lastUpdatedTo(),
                request.limit(),
                principal.subject(),
                correlationId());
    }

    @GetMapping("/records/{recordRef}")
    public BusinessExperienceView.RecordView record(@PathVariable String recordRef) {
        SdvPrincipal principal = requireSearch();
        return business.record(
                principal.tenantId(),
                recordRef,
                principal.subject(),
                correlationId());
    }

    private SdvPrincipal requireSearch() {
        SdvPrincipal principal = security.requirePrincipal();
        authorization.require(principal, Capability.SEARCH_RECORDS);
        return principal;
    }

    private static String correlationId() {
        return MDC.get("correlationId");
    }

    public record SearchRequest(
            UUID sourceId,
            Map<String, String> criteria,
            Instant dateFrom,
            Instant dateTo,
            Instant lastUpdatedFrom,
            Instant lastUpdatedTo,
            Integer limit) {
        public SearchRequest {
            if (sourceId == null) throw new IllegalArgumentException("sourceId is required.");
        }
    }
}
