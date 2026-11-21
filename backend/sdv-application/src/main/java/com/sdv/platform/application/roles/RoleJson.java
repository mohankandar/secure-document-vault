package com.sdv.platform.application.roles;

import com.sdv.platform.domain.error.InternalCheckFailed;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/** JSON text for audit details. Role and group names are written by the JSON library, never pasted by hand. */
final class RoleJson {
    private static final ObjectMapper JSON = new ObjectMapper();

    private RoleJson() { }

    static String quote(String value) {
        return write(value);
    }

    static String array(List<String> values) {
        return write(values == null ? null : List.copyOf(values));
    }

    private static String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw InternalCheckFailed.auditNotWritten("Audit details could not be written.", ex);
        }
    }
}
