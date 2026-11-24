package com.sdv.platform.application.viewer;

import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.source.IngestionSource;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Task 0063: the sending system's page limit, or the entity's when it has none of its own. */
@Component
public class SourcePageLimit implements DocumentViewerService.PageLimit {
    private final TenantRepository tenants;
    private final IngestionSourceRepository sources;

    public SourcePageLimit(TenantRepository tenants, IngestionSourceRepository sources) {
        this.tenants = tenants;
        this.sources = sources;
    }

    @Override
    public int maxPages(UUID tenantId, UUID sourceId) {
        int tenantMax = tenants.findById(tenantId).map(t -> t.guardrails().maxPagesPerPdf()).orElse(0);
        Integer sourceMax = sources.findById(tenantId, sourceId).map(IngestionSource::limits).map(l -> l.maxPagesPerPdf()).orElse(null);
        return sourceMax == null ? tenantMax : Math.min(tenantMax, sourceMax);
    }
}
