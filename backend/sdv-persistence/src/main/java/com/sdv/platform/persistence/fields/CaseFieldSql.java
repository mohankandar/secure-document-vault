package com.sdv.platform.persistence.fields;

import com.sdv.platform.application.business.SearchValueNormalizer;
import com.sdv.platform.domain.identity.VeroProduct;

import java.util.UUID;

/**
 * Task 0078: the one place that joins Vero's rows with platform-fields' tables. A Vero record is the platform record of type
 * {@code s-<sending system>} with the record's ID; readable columns are empty for a sensitive value (the database refuses any other).
 */
public final class CaseFieldSql {
    private CaseFieldSql() { }

    /** The platform record's type: a record belongs to one sending system. */
    public static String recordType(UUID source) {
        return "s-" + source;
    }

    /** A value or search text in the form {@code fld_value.search_value} has: lower case, single spaces. */
    public static String searchForm(String text) {
        return SearchValueNormalizer.searchForm(text);
    }

    /** In a saved quarantine filter: fingerprints of a sensitive value, never the value. */
    public static final String FINGERPRINTS = "fingerprint:";

    /** Joins submission alias {@code s} to its platform record, alias {@code fr}. */
    public static String record(String s) {
        return " join fld_record fr on fr.entity_uuid=" + s + ".tenant_id and fr.product_code='" + VeroProduct.CODE + "' and fr.record_type='s-'||"
                + s + ".source_id::text and fr.record_id=" + s + ".record_id::text ";
    }

    /** Joins value alias {@code v} (of record {@code fr}) to its field and to what the sending system of {@code s} says about it: {@code f}, {@code x}. */
    public static String field(String s) {
        return " join fld_field_definition f on f.entity_uuid=v.entity_uuid and f.product_code=v.product_code and lower(f.field_key)=lower(v.field_key)"
                + " join fld_field_extension x on x.definition_id=f.definition_id and x.name='source.'||" + s + ".source_id::text ";
    }

    /** The sending system's label, identifier flag and place, from {@code x}. */
    public static final String LABEL = "(x.value::jsonb->>'label')";
    public static final String IDENTIFIER = "(x.value::jsonb->>'identifier')::boolean";
    public static final String PLACE = "nullif((x.value::jsonb->>'order')::int, 0)";

    /** A readable value of one field equals a text in search form; a saved filter's fingerprints equal a sensitive value's. */
    public static String equalsFilter(String text) {
        return "(v.search_value=" + text + " or (v.fingerprint is not null and " + text + " like '" + FINGERPRINTS + "%' and v.fingerprint=any(string_to_array(substr("
                + text + ", " + (FINGERPRINTS.length() + 1) + "), '|'))))";
    }

    /** exists (a readable or sensitive value of the record of this document or submission, with the key, equal to the text). */
    public static String hasValue(String tenant, String submission, String key, String text) {
        return "exists (select 1 from submissions rs" + record("rs") + "join fld_value v on v.record_uuid=fr.record_uuid where rs.tenant_id=" + tenant
                + " and rs.submission_id=" + submission + " and v.field_key=" + key + " and " + equalsFilter(text) + ")";
    }

    /** The submissions of the entity {@code :tenantId} one of whose readable identifiers is the text (search form). */
    public static String submissionsByIdentifier(String text) {
        // From the identifier fields of each sending system (few) to their values, by the whole value index.
        return "select rs.submission_id from fld_field_definition f join fld_field_extension x on x.definition_id=f.definition_id and x.name like 'source.%' and " + IDENTIFIER
                + " join fld_value v on v.entity_uuid=f.entity_uuid and v.product_code=f.product_code and v.record_type='s-'||substr(x.name, 8) and v.field_key=f.field_key and v.search_value=" + text
                + " join fld_record fr on fr.record_uuid=v.record_uuid"
                + " join submissions rs on rs.tenant_id=fr.entity_uuid and rs.source_id=substr(x.name, 8)::uuid and rs.record_id=fr.record_id::uuid"
                + " where f.entity_uuid=:tenantId and f.product_code='" + VeroProduct.CODE + "'";
    }
}
