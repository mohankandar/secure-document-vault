package com.sdv.platform.application.operations;

import com.sdv.platform.application.business.BusinessExperienceService;
import com.sdv.platform.application.business.BusinessExperienceView;
import com.sdv.platform.application.port.BusinessRecordRepository;
import com.sdv.platform.application.port.BusinessRecordRepository.MetadataSnapshot;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.domain.source.IngestionSource;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Task 0011 Part C.1: the record behind each problem (its first identifier in display order, its record title as in
 * task 0009) and the sending system's name. Everything is read within the given tenant. A record that can't be
 * resolved still gets an entry, with the sending system only, so the problem is never dropped.
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

    public record ProblemRecord(String recordNumber, String recordNumberLabel, String recordTitle, String sourceName) { }

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
                Map<String, Integer> order = new HashMap<>();
                List<String> saved = definitions.findDisplayOrder(tenantId, sourceId);
                for (int i = 0; i < saved.size(); i++) order.put(saved.get(i), i);
                List<String> titleFields = definitions.findRecordTitleFields(tenantId, sourceId);
                Map<UUID, List<MetadataSnapshot>> metadata = records.findMetadataSnapshots(tenantId, sourceId, submissionIds)
                        .stream().collect(Collectors.groupingBy(MetadataSnapshot::submissionId));
                for (UUID submissionId : submissionIds) {
                    List<MetadataSnapshot> values = metadata.getOrDefault(submissionId, List.of());
                    MetadataSnapshot identifier = values.stream()
                            .filter(MetadataSnapshot::businessIdentifier)
                            .filter(v -> v.storedValue() != null && !v.storedValue().isBlank())
                            .min(Comparator.comparingInt((MetadataSnapshot v) -> order.getOrDefault(v.fieldKey(), Integer.MAX_VALUE))
                                    .thenComparing(MetadataSnapshot::fieldKey))
                            .orElse(null);
                    List<BusinessExperienceView.FieldValue> fields = values.stream()
                            .map(v -> new BusinessExperienceView.FieldValue(v.fieldKey(), v.displayLabel(), v.storedValue(), v.businessIdentifier()))
                            .sorted(Comparator.comparing(BusinessExperienceView.FieldValue::displayLabel, String.CASE_INSENSITIVE_ORDER)
                                    .thenComparing(BusinessExperienceView.FieldValue::fieldKey))
                            .toList();
                    result.put(submissionId, new ProblemRecord(
                            identifier == null ? null : identifier.storedValue(),
                            identifier == null ? null : identifier.displayLabel(),
                            BusinessExperienceService.recordTitle(fields, titleFields),
                            sourceName));
                }
            } catch (RuntimeException ex) {
                for (UUID submissionId : submissionIds) result.putIfAbsent(submissionId, new ProblemRecord(null, null, null, sourceName));
            }
        });
        return result;
    }
}
