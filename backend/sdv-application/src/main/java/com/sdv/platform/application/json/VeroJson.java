package com.sdv.platform.application.json;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Task 0070: the mapper for JSON Vero stores, hashes, reads back or writes by hand. Jackson 3 changed defaults (order of
 * names, text after the JSON, null for a number, depth); what is stored or hashed must stay byte for byte as it was, so
 * this keeps the defaults it was written with. The mapper can't be changed, so one serves all.
 */
public final class VeroJson {
    private static final JsonMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

    private VeroJson() { }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** A value as text, as before Jackson 3, which refuses or changes some: null is "null", an object or array is "". */
    public static String text(JsonNode node) {
        if (node.isString()) return node.stringValue();
        if (node.isNull()) return "null";
        return node.isValueNode() ? node.asString() : "";
    }
}
