package com.sdv.platform.application.operations;

import com.sdv.platform.application.business.PrimaryIdentifierRule;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.MetadataSnapshot;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.domain.source.IngestionSource;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Task 0011 Part C.1: the record behind each problem (its first identifier in display order) and the sending system's
 * name. Everything is read within the given tenant. A record that can't be resolved still gets an entry, with the
 * sending system only, so the problem is never dropped.
 * <p>
 * Task 0028, 0.1: no record title. Problems are read by IT Admins and Platform Ops, and admin views carry the record's
 * primary identifier only; the record-title fields are never read here.
 */
@Component
public class ProblemRecordResolver {
    private final BusinessRecordRepository records;
    private final MetadataFieldDefinitionRepository definitions;
    private final IngestionSourceRepository sources;

    public ProblemRecordResolver(BusinessRecordRepository records,
                                 MetadataFieldDefinitionRepository definitions,
                                 IngestionSourceRepository sources) {
        this.records = records;
        this.definitions = definitions;
        this.sources = sources;
    }

    public record ProblemRecord(String recordNumber, String recordNumberLabel, String sourceName) { }

    /** submissionToSource: each problem's submission and its sending system. */
    public Map<UUID, ProblemRecord> resolve(UUID tenantId, Map<UUID, UUID> submissionToSource) {
        Map<UUID, ProblemRecord> result = new LinkedHashMap<>();
        Map<UUID, List<UUID>> bySource = submissionToSource.entrySet().stream()
                .collect(Collectors.groupingBy(Map.Entry::getValue, LinkedHashMap::new,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
        bySource.forEach((sourceId, submissionIds) -> {
            String sourceName = null;
            try {
                sourceName = sources.findById(tenantId, sourceId).map(IngestionSource::displayName).orElse(null);
                // The record number is the record's primary identifier: the one rule (task 0056).
                Comparator<String> order = PrimaryIdentifierRule.fieldOrder(definitions.findDisplayOrder(tenantId, sourceId));
                Map<UUID, List<MetadataSnapshot>> metadata = records.findMetadataSnapshots(tenantId, sourceId, submissionIds)
                        .stream().collect(Collectors.groupingBy(MetadataSnapshot::submissionId));
                for (UUID submissionId : submissionIds) {
                    List<MetadataSnapshot> values = metadata.getOrDefault(submissionId, List.of());
                    MetadataSnapshot identifier = values.stream()
                            .filter(MetadataSnapshot::businessIdentifier)
                            .filter(v -> v.storedValue() != null && !v.storedValue().isBlank())
                            .min(Comparator.comparing(MetadataSnapshot::fieldKey, order))
                            .orElse(null);
                    result.put(submissionId, new ProblemRecord(
                            identifier == null ? null : identifier.storedValue(),
                            identifier == null ? null : identifier.displayLabel(),
                            sourceName));
                }
            } catch (RuntimeException ex) {
                for (UUID submissionId : submissionIds) result.putIfAbsent(submissionId, new ProblemRecord(null, null, sourceName));
            }
        });
        return result;
    }
}
