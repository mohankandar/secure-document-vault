package com.sdv.platform.web.baseline;

import com.sdv.platform.application.business.RecordKey;
import com.sdv.platform.domain.document.SensitivityLevel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0060 (plan 0059, step 0): the JSON Vero keeps (audit details, record keys, record references, storage counts,
 * redaction regions, filters), byte for byte, and what it accepts when it reads it back. Rows are compared, hashed and
 * sealed, so a byte that changes with the JSON library changes stored data. Reflection is used on purpose: the same
 * test must run on either library.
 */
class StoredJsonBaselineTest {
    private static final UUID ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    /** Unsorted names, characters that writers may escape differently, every kind of number, an enum, a null. */
    private static Map<String, Object> sample() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("zebra", "a/b \"quoted\" back\\slash <tag> & 'single'");
        m.put("alpha", "line\nfeed\ttab\u0001\u007f ");
        m.put("names", "Zoë 日本 😀");
        m.put("int", 42);
        m.put("long", 9_000_000_000L);
        m.put("double", 1.0);
        m.put("small", 1.0E-7);
        m.put("big", 1.0E10);
        m.put("decimal", new java.math.BigDecimal("10.500"));
        m.put("flag", true);
        m.put("none", null);
        m.put("level", SensitivityLevel.HIGHLY_CONFIDENTIAL);
        m.put("id", ID);
        m.put("list", List.of("b", "a"));
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("y", 1);
        inner.put("x", 2);
        m.put("inner", inner);
        m.put("empty", Map.of());
        return m;
    }

    private static boolean isMapper(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) if (c.getSimpleName().equals("ObjectMapper")) return true;
        return false;
    }

    /** An instance whose collaborators are stand-ins: only its mapper and its JSON methods are used. */
    private static Object instance(Class<?> c) throws Exception {
        Exception last = null;
        for (Constructor<?> k : c.getDeclaredConstructors()) {
            k.setAccessible(true);
            try {
                return k.newInstance(Arrays.stream(k.getParameterTypes()).map(StoredJsonBaselineTest::stand).toArray());
            } catch (InvocationTargetException ex) {
                last = ex;
            }
        }
        throw new IllegalStateException("No instance of " + c.getName(), last);
    }

    private static Object stand(Class<?> t) {
        if (t == boolean.class) return false;
        if (t == int.class) return 1;
        if (t == long.class) return 1L;
        if (t == String.class) return "x";
        if (t == List.class) return List.of();
        if (t == Map.class) return Map.of();
        if (t == Set.class) return Set.of();
        if (t == java.net.URI.class) return java.net.URI.create("http://127.0.0.1:8200");
        if (t == java.time.Clock.class) return java.time.Clock.systemUTC();
        if (isMapper(t)) {
            try {
                return t.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(ex);
            }
        }
        if (Modifier.isFinal(t.getModifiers())) return null;
        return org.mockito.Mockito.mock(t);
    }

    /** Each mapper Vero makes by hand, found by its field, and what it writes. */
    @Test
    void everyMapperWritesTheSameBytes() throws Exception {
        Map<String, String> written = new TreeMap<>();
        for (Class<?> c : VeroClasses.all()) {
            for (Field f : c.getDeclaredFields()) {
                if (!isMapper(f.getType())) continue;
                f.setAccessible(true);
                Object mapper = f.get(Modifier.isStatic(f.getModifiers()) ? null : instance(c));
                written.put(c.getSimpleName(), (String) mapper.getClass().getMethod("writeValueAsString", Object.class).invoke(mapper, sample()));
            }
        }
        assertTrue(written.size() > 20, written.keySet().toString());
        assertEquals(1, Set.copyOf(written.values()).size(), "every mapper writes the same text: " + written);
        Baseline.matches("stored-json-mappers", written.values().iterator().next());
    }

    private static Object call(String className, String method, Object... args) throws Exception {
        Class<?> c = Class.forName("com.sdv.platform." + className);
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(method) || m.getParameterCount() != args.length) continue;
            m.setAccessible(true);
            try {
                return m.invoke(Modifier.isStatic(m.getModifiers()) ? null : instance(c), args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause() instanceof Exception cause ? cause : ex;
            }
        }
        throw new NoSuchMethodException(className + "." + method);
    }

    private static String outcome(String className, String method, Object... args) {
        try {
            return "read " + call(className, method, args);
        } catch (Exception ex) {
            return "refused " + ex.getClass().getSimpleName();
        }
    }

    @Test
    void recordKeysTheirHashAndWhatIsRefused() {
        String key = RecordKey.ofIdentifiers(List.of(new RecordKey.Part("memberNumber", "M/1 \"q\" ë日😀"),
                new RecordKey.Part("contractNumber", "300000000076")));
        assertEquals("identifiers:[[\"contractNumber\",\"300000000076\"],[\"memberNumber\",\"M/1 \\\"q\\\" ë日😀\"]]", key);
        assertEquals(2, RecordKey.parse(key).identifiers().size());
        Baseline.matches("record-key-hash", RecordKey.sha256(key));
        for (String not : List.of(key + " ", key + " x", key.replace("\",\"", "\", \""), "identifiers:[]", "identifiers:", "identifiers:null",
                "identifiers:[[\"a\",1]]", "identifiers:[[\"a\",\"b\"]]]")) {
            assertThrows(RuntimeException.class, () -> RecordKey.parse(not), not);
        }
    }

    @Test
    void recordReferencesAndWhatTheyAccept() throws Exception {
        String codec = "application.business.BusinessRecordRefCodec";
        Constructor<?> part = Class.forName("com.sdv.platform." + codec + "$IdentifierPart").getDeclaredConstructors()[0];
        Constructor<?> identity = Class.forName("com.sdv.platform." + codec + "$RecordIdentity").getDeclaredConstructors()[0];
        part.setAccessible(true);
        identity.setAccessible(true);
        Object byIds = identity.newInstance(ID, List.of(part.newInstance("memberNumber", "M/1 ë"), part.newInstance("contractNumber", "3")), null);
        assertEquals("{\"s\":\"" + ID + "\",\"i\":[{\"k\":\"contractNumber\",\"v\":\"3\"},{\"k\":\"memberNumber\",\"v\":\"M/1 ë\"}]}",
                new String(Base64.getUrlDecoder().decode((String) call(codec, "encode", byIds)), StandardCharsets.UTF_8));
        String good = "{\"s\":\"" + ID + "\",\"u\":\"" + ID + "\"}";
        assertEquals(good, new String(Base64.getUrlDecoder().decode((String) call(codec, "encode", identity.newInstance(ID, List.of(), ID))), StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        for (String json : List.of(good, good + " ", good + " trailing", good + "{}", good.replace("}", ",\"more\":1}"),
                good.replace("}", ",\"u\":\"" + ID + "\"}"), "[]", "null", "", "x")) {
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            text.append(json.replace(ID.toString(), "ID")).append(" -> ").append(outcome(codec, "decode", token).replaceAll("read .*", "read")).append('\n');
        }
        Baseline.matches("record-reference-reading", text);
    }

    @Test
    void rolesInAuditDetails() throws Exception {
        assertEquals("[\"b/\\\"\",\"ë\"]", call("application.roles.RoleJson", "array", List.of("b/\"", "ë")));
        assertEquals("\"x\\\\y\"", call("application.roles.RoleJson", "quote", "x\\y"));
        assertEquals("null", call("application.roles.RoleJson", "array", new Object[] {null}));
    }

    /** Redaction regions and storage counts are records written as they are: their field order is stored data. */
    @Test
    void recordsThatAreStoredKeepTheirFieldOrder() throws Exception {
        String redaction = "application.redaction.CorrectiveRedactionService";
        Class<?> regionType = Class.forName("com.sdv.platform.application.redaction.RedactionRegion");
        Constructor<?> region = regionType.getDeclaredConstructors()[0];
        region.setAccessible(true);
        String regions = (String) call(redaction, "toJson", List.of(region.newInstance(2, 1.0E-4, 0.25, 0.1, 0.5)));
        String storage = "application.reports.StorageReportService";
        Constructor<?> counts = Class.forName("com.sdv.platform." + storage + "$Counts").getDeclaredConstructors()[0];
        counts.setAccessible(true);
        Object value = counts.newInstance(Arrays.stream(counts.getParameterTypes()).map(t -> t == String.class ? "t" : t == List.class ? List.of()
                : t == long.class ? (Object) 5L : t == int.class ? (Object) 5 : t == boolean.class ? (Object) true : null).toArray());
        String kept = (String) call(storage, "write", value);
        assertEquals(value, call(storage, "read", kept));
        assertEquals(value, call(storage, "read", kept.replaceFirst("\\{", "{\"laterField\":1,")), "a field this version doesn't know");
        Baseline.matches("stored-records", regions + "\n" + kept + "\n"
                + "regions, text after -> " + outcome(redaction, "regionCount", regions + " x") + "\n"
                + "counts, text after -> " + outcome(storage, "read", kept + " x").replaceAll("read .*", "read") + "\n"
                + "counts, null number -> " + outcome(storage, "read", kept.replace(":5", ":null")).replaceAll("read .*", "read") + "\n");
    }

    /** Task 0062: a redaction's event names the operation; its reason (free text) and regions stay in the redaction tables. */
    @Test
    void aRedactionsAuditHoldsNoReasonAndNoRegions() throws Exception {
        Constructor<?> k = Class.forName("com.sdv.platform.application.redaction.RedactionOperation").getDeclaredConstructors()[0];
        k.setAccessible(true);
        Object op = k.newInstance(Arrays.stream(k.getParameterTypes()).map(t -> t == String.class ? "SECRET-TEXT" : t == UUID.class ? (Object) ID
                : t == long.class ? (Object) 5L : t == int.class ? (Object) 5 : t == Long.class ? (Object) 5L : t == Integer.class ? (Object) 5
                : t == java.time.Instant.class ? (Object) java.time.Instant.EPOCH : null).toArray());
        String json = (String) call("application.redaction.CorrectiveRedactionService", "successAuditJson", op, 2, ID);
        assertTrue(json.startsWith("{\"operationId\":\"" + ID + "\",\"regionCount\":2,\"oldContentSha256\":"), json);
        assertTrue(json.contains("\"accessRequestId\":\"" + ID + "\",\"sealedOriginalId\":\"" + ID + "\""), json);
        assertFalse(json.contains("reason") || json.contains("\"regions\""), json);
    }

    @Test
    void filtersRolesAndTheBusyBodyAreReadAsToday() throws Exception {
        StringBuilder text = new StringBuilder();
        String json = "{\"zeta\":\"1\",\"alpha\":\"2\",\"n\":3,\"none\":null}";
        for (String s : List.of(json, json + " x", "[", "", "{\"a\":{}}")) {
            text.append("filters ").append(s).append(" -> ").append(outcome("application.audit.SealedAuditReportService", "readFilters", s)).append('\n');
        }
        for (String s : List.of("[\"B\",\"A\",1]", "{", "[\"A\"] x")) {
            text.append("roles ").append(s).append(" -> ").append(outcome("persistence.reports.JdbcSensitivityReportRepository", "roles", s)).append('\n');
        }
        for (String s : List.of("{\"status\":\"BUSY\"}", "{\"status\":\"UP\"}", "{\"status\":1}", "{\"status\":null}", "{}", "[]", "x", "{\"status\":\"BUSY\"} x")) {
            text.append("busy ").append(s).append(" -> ")
                    .append(outcome("web.schema.ReadinessBusyAnswer", "busy", (Object) s.getBytes(StandardCharsets.UTF_8))).append('\n');
        }
        Baseline.matches("json-reading", text);
    }
}
