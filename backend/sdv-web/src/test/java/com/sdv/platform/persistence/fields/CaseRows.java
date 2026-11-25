package com.sdv.platform.persistence.fields;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/** Test rows for tests that write the tables themselves (task 0078): a field of a sending system, and readable values of a submission's record. */
public final class CaseRows {
    private static final UUID CUSTOMER = new UUID(0, 1);

    private CaseRows() { }

    /** place: 0 for none. */
    public static void field(JdbcTemplate db, UUID tenant, UUID source, String key, String label, boolean identifier, String normalization, int place) {
        boolean digits = "DIGITS_ONLY".equals(normalization);
        db.update("""
                insert into fld_field_definition(definition_id, entity_uuid, product_code, field_key, label, field_type, required, letter_case, strip, primary_flag, searchable, search_mode,
                                                 sensitive, multi_value, displayable, display_order, active, version, created_at, updated_at, updated_by)
                values (gen_random_uuid(), ?, 'SDV', ?, ?, 'TEXT', false, 'PRESERVE', ?, ?, true, 'EXACT', false, false, true, 0, true, 1, now(), now(), 'test')
                on conflict do nothing
                """, tenant, key, label, digits ? "NON_DIGITS" : "NONE", identifier);
        String json = """
                {"label":"%s","required":false,"identifier":%s,"searchable":true,"normalization":"%s","mode":"EXACT","order":%d,"title":0,"version":1,"since":0}
                """.formatted(label, identifier, normalization, place).strip();
        db.update("""
                insert into fld_field_extension(definition_id, name, value, updated_at)
                select definition_id, ?, ?, now() from fld_field_definition where entity_uuid = ? and product_code = 'SDV' and field_key = ?
                on conflict (definition_id, name) do update set value = excluded.value
                """, "source." + source, json, tenant, key);
    }

    public static void place(JdbcTemplate db, UUID tenant, String key, int place) {
        db.update("""
                update fld_field_extension x set value = jsonb_set(x.value::jsonb, '{order}', to_jsonb(?::int))::text
                  from fld_field_definition f where f.definition_id = x.definition_id and f.entity_uuid = ? and f.field_key = ?
                """, place, tenant, key);
    }

    /** A value of the submission's record. An identifier value another record of the system has already joins the submission to that record. */
    public static void value(JdbcTemplate db, UUID tenant, UUID source, UUID submission, String key, String value) {
        if (value.isBlank()) return;
        Boolean identifier = db.queryForObject("select coalesce(bool_or((x.value::jsonb->>'identifier')::boolean), false) from fld_field_extension x"
                + " join fld_field_definition f on f.definition_id = x.definition_id where f.entity_uuid = ? and f.field_key = ? and x.name = ?", Boolean.class, tenant, key, "source." + source);
        if (Boolean.TRUE.equals(identifier)) {
            List<String> same = db.queryForList("select fr.record_id from fld_value v join fld_record fr on fr.record_uuid = v.record_uuid"
                    + " where v.entity_uuid = ? and v.record_type = ? and v.field_key = ? and v.search_value = ?", String.class, tenant, "s-" + source, key, CaseFieldSql.searchForm(value));
            if (!same.isEmpty()) {
                db.update("update submissions set record_id = ? where submission_id = ?", UUID.fromString(same.getFirst()), submission);
                return;
            }
        }
        insert(db, tenant, source, submission, key, value);
    }

    private static void insert(JdbcTemplate db, UUID tenant, UUID source, UUID submission, String key, String value) {
        UUID record = db.queryForObject("select record_id from submissions where submission_id = ?", UUID.class, submission);
        db.update("""
                insert into fld_record(record_uuid, customer_id, entity_uuid, product_code, record_type, record_id, version, created_at, updated_at)
                values (gen_random_uuid(), ?, ?, 'SDV', ?, ?, 1, now(), now()) on conflict do nothing
                """, CUSTOMER, tenant, "s-" + source, record.toString());
        db.update("""
                insert into fld_value(record_uuid, customer_id, entity_uuid, product_code, record_type, field_key, position, sensitive, display_value, normalised_value, search_value)
                select record_uuid, customer_id, entity_uuid, product_code, record_type, ?, 0, false, ?, ?, ? from fld_record
                 where entity_uuid = ? and product_code = 'SDV' and record_type = ? and record_id = ?
                """, key, value.strip(), value.strip(), CaseFieldSql.searchForm(value), tenant, "s-" + source, record.toString());
    }

    /** Identifier values (key, value ...): the submission joins the record that has exactly these, or is a record of its own. */
    public static void identified(JdbcTemplate db, UUID tenant, UUID source, UUID submission, String... pairs) {
        for (UUID other : db.queryForList("select distinct fr.record_id::uuid from fld_record fr where fr.entity_uuid = ? and fr.record_type = ?", UUID.class, tenant, "s-" + source)) {
            List<String> have = db.queryForList("select v.field_key || '=' || v.search_value from fld_value v join fld_record fr on fr.record_uuid = v.record_uuid where fr.entity_uuid = ? and fr.record_type = ? and fr.record_id = ? order by 1",
                    String.class, tenant, "s-" + source, other.toString());
            List<String> want = new java.util.ArrayList<>();
            for (int i = 0; i < pairs.length; i += 2) want.add(pairs[i] + "=" + CaseFieldSql.searchForm(pairs[i + 1]));
            java.util.Collections.sort(want);
            if (pairs.length > 0 && java.util.Set.copyOf(have).equals(java.util.Set.copyOf(want))) {
                db.update("update submissions set record_id = ? where submission_id = ?", other, submission);
                return;
            }
        }
        for (int i = 0; i < pairs.length; i += 2) insert(db, tenant, source, submission, pairs[i], pairs[i + 1]);
    }

    /** One value for each submission of a select with submission_id and n; value is an SQL expression over n. */
    public static void values(JdbcTemplate db, UUID tenant, UUID source, String submissions, String key, String value) {
        db.execute("insert into fld_record(record_uuid, customer_id, entity_uuid, product_code, record_type, record_id, version, created_at, updated_at) select gen_random_uuid(), '"
                + CUSTOMER + "', '" + tenant + "', 'SDV', 's-" + source + "', submission_id::text, 1, now(), now() from " + submissions + " s");
        db.execute("insert into fld_value(record_uuid, customer_id, entity_uuid, product_code, record_type, field_key, position, sensitive, display_value, normalised_value, search_value)"
                + " select fr.record_uuid, fr.customer_id, fr.entity_uuid, fr.product_code, fr.record_type, '" + key + "', 0, false, " + value + ", " + value + ", lower(" + value + ") from " + submissions
                + " s join fld_record fr on fr.entity_uuid = '" + tenant + "' and fr.product_code = 'SDV' and fr.record_type = 's-" + source + "' and fr.record_id = s.submission_id::text");
    }
}
