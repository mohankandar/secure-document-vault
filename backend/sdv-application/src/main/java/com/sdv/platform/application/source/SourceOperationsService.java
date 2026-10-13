package com.sdv.platform.application.source;

import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.SourceOperationalQueryPort;
import org.springframework.stereotype.Service;

import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class SourceOperationsService {
    private final IngestionSourceRepository sources;
    private final SourceOperationalQueryPort query;

    public SourceOperationsService(IngestionSourceRepository sources, SourceOperationalQueryPort query) {
        this.sources = sources;
        this.query = query;
    }

    public SourceOperationalQueryPort.SourceOperationalSummary summarize(UUID tenantId, UUID sourceId) {
        sources.findById(tenantId, sourceId)
                .orElseThrow(() -> new NoSuchElementException("Ingestion source not found."));
        return query.summarize(tenantId, sourceId);
    }
}
