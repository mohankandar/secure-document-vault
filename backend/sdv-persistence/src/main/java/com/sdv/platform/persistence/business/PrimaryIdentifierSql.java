package com.sdv.platform.persistence.business;

import com.sdv.platform.persistence.fields.CaseFieldSql;

/**
 * Task 0056, 0078: the SQL form of the one primary-identifier rule (see {@code PrimaryIdentifierRule} in sdv-application).
 * A record's primary identifier is its first identifier value in the sending system's display order; fields without a saved position come
 * after, and ties go by field key. Every admin read uses these pieces. A sensitive identifier is never shown there: the next one is, or none.
 */
public final class PrimaryIdentifierSql {
    private PrimaryIdentifierSql() { }

    /**
     * A lateral join named {@code pid} with the primary identifier of one submission's record: {@code pid.field_key},
     * {@code pid.stored_value}, {@code pid.normalized_value} and {@code pid.display_label} (all null when it has none).
     * tenant and submission are SQL expressions from the outer query.
     */
    public static String join(String tenant, String submission) {
        return """
                left join lateral (
                    select v.field_key, v.display_value as stored_value, v.normalised_value as normalized_value, v.search_value, %s as display_label
                      from submissions rs%sjoin fld_value v on v.record_uuid=fr.record_uuid and not v.sensitive%s
                     where rs.tenant_id=%s and rs.submission_id=%s and %s
                     order by %s nulls last, v.field_key
                     limit 1) pid on true
                """.formatted(CaseFieldSql.LABEL, CaseFieldSql.record("rs"), CaseFieldSql.field("rs"), tenant, submission, CaseFieldSql.IDENTIFIER, CaseFieldSql.PLACE);
    }
}
