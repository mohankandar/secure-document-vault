package com.sdv.platform.persistence.business;

/**
 * Task 0056: the SQL form of the one primary-identifier rule (see {@code PrimaryIdentifierRule} in sdv-application).
 * A record's primary identifier is its first non-blank business-identifier value in the sending system's display
 * order; fields without a saved position come after, and ties go by field key. Every admin read uses these pieces.
 * <p>
 * Later: at Vero's case-fields switch-over, platform-fields' {@code primary} flag becomes the source of the primary
 * identifier, and only this helper and {@code PrimaryIdentifierRule} change.
 */
public final class PrimaryIdentifierSql {
    private PrimaryIdentifierSql() { }

    /**
     * A lateral join named {@code pid} with the primary identifier of one submission: {@code pid.field_key},
     * {@code pid.stored_value}, {@code pid.normalized_value} and {@code pid.display_label} (all null when it has none).
     * tenant and submission are SQL expressions from the outer query.
     */
    public static String join(String tenant, String submission) {
        return """
                left join lateral (
                    select v.field_key, v.stored_value, v.normalized_value, f.display_label
                      from business_metadata_values v
                      join metadata_field_definitions f on f.definition_id=v.definition_id and f.business_identifier
                      left join metadata_field_display_order o
                        on o.tenant_id=v.tenant_id and o.source_id=f.source_id and o.field_key=v.field_key
                     where v.tenant_id=%s and v.submission_id=%s and btrim(v.stored_value)<>''
                     order by o.display_position nulls last, v.field_key
                     limit 1) pid on true
                """.formatted(tenant, submission);
    }

    /** Joins the display position {@code o} of field definition alias f; order by {@link #FIELD_ORDER} after it. */
    public static final String FIELD_POSITION = """
            left join metadata_field_display_order o
              on o.tenant_id=f.tenant_id and o.source_id=f.source_id and o.field_key=f.field_key
            """;

    /** The rule's order over field definitions f joined with {@link #FIELD_POSITION}. */
    public static final String FIELD_ORDER = "o.display_position nulls last, f.field_key";
}
