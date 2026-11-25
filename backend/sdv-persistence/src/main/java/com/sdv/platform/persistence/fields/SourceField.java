package com.sdv.platform.persistence.fields;

import com.sdv.platform.application.json.VeroJson;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Task 0077: what Vero keeps about a field for one sending system, as an extension of its definition ({@code source.<id>}): that the
 * system sends it, its own label, whether it must send it, whether it identifies that system's records, how people search by it, its
 * normalisation of before, its place in the order and in the record title (0: none), and which version of these settings it is, since when.
 */
record SourceField(String label, boolean required, boolean identifier, boolean searchable, MetadataNormalization normalization,
                   MetadataSearchMode mode, int order, int title, int version, long since) {
    static String name(UUID source) {
        return "source." + source;
    }

    static SourceField of(MetadataFieldDefinition d, SourceField before) {
        return new SourceField(d.displayLabel(), d.required(), d.businessIdentifier(), d.searchable(), d.normalization(), d.searchMode(),
                before == null ? 0 : before.order, before == null ? 0 : before.title, d.version(), d.effectiveAt().toEpochMilli());
    }

    SourceField withOrder(int place) {
        return new SourceField(label, required, identifier, searchable, normalization, mode, place, title, version, since);
    }

    SourceField withTitle(int place) {
        return new SourceField(label, required, identifier, searchable, normalization, mode, order, place, version, since);
    }

    String json() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("required", required);
        m.put("identifier", identifier);
        m.put("searchable", searchable);
        m.put("normalization", normalization.name());
        m.put("mode", mode.name());
        m.put("order", order);
        m.put("title", title);
        m.put("version", version);
        m.put("since", since);
        return VeroJson.mapper().writeValueAsString(m);
    }

    static SourceField parse(String json) {
        JsonNode n = VeroJson.mapper().readTree(json);
        return new SourceField(n.path("label").asString(), n.path("required").asBoolean(false), n.path("identifier").asBoolean(false),
                n.path("searchable").asBoolean(false), MetadataNormalization.valueOf(n.path("normalization").asString()),
                MetadataSearchMode.valueOf(n.path("mode").asString()), n.path("order").asInt(0), n.path("title").asInt(0),
                n.path("version").asInt(1), n.path("since").asLong(0));
    }
}
