package com.sdv.platform.security.auth.oidc;

import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.sdv.platform.application.access.ActiveTenantCache;
import com.sdv.platform.application.business.RecordReferences;
import com.sdv.platform.application.document.DocumentFinalizationService;
import com.sdv.platform.application.document.DocumentIntakeService;
import com.sdv.platform.application.port.AccessRequestRepository;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.DocumentIntakeRepository;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.application.port.MetadataFieldDefinitionRepository;
import com.sdv.platform.application.port.RecordSensitivityRepository;
import com.sdv.platform.application.port.StorageProvider;
import com.sdv.platform.application.port.StreamingCryptoPort;
import com.sdv.platform.application.port.SubmissionRepository;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.application.storage.NoWritableStorageException;
import com.sdv.platform.application.storage.StoragePlacementService;
import com.sdv.platform.application.submission.SubmissionService;
import com.sdv.platform.domain.document.Document;
import com.sdv.platform.domain.document.DocumentStatus;
import com.sdv.platform.domain.document.SensitivityLevel;
import com.sdv.platform.domain.error.InternalCheckFailed;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.metadata.MetadataFieldDefinition;
import com.sdv.platform.domain.metadata.MetadataFieldType;
import com.sdv.platform.domain.metadata.MetadataNormalization;
import com.sdv.platform.domain.metadata.MetadataSearchMode;
import com.sdv.platform.domain.source.IngestionSource;
import com.sdv.platform.domain.source.IngestionSourceAuthType;
import com.sdv.platform.domain.source.IngestionSourceStatus;
import com.sdv.platform.domain.storage.StorageVolume;
import com.sdv.platform.domain.storage.StorageVolumeState;
import com.sdv.platform.domain.submission.ExpectedDocumentState;
import com.sdv.platform.domain.submission.Submission;
import com.sdv.platform.domain.submission.SubmissionStatus;
import com.sdv.platform.domain.tenant.Tenant;
import com.sdv.platform.domain.tenant.TenantGuardrails;
import com.sdv.platform.domain.tenant.TenantStatus;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.SecurityContextFacade;
import com.sdv.platform.security.config.SecurityConfiguration;
import com.sdv.platform.security.config.SignInAnswers;
import com.sdv.platform.web.config.CorrelationIdFilter;
import com.sdv.platform.web.ingestion.TestPdfs;
import com.sdv.platform.web.foundation.FoundationExceptionHandler;
import com.sdv.platform.web.foundation.SdvErrorController;
import com.sdv.platform.web.ingestion.DocumentIntakeController;
import com.sdv.platform.web.ingestion.SubmissionController;
import com.sdv.platform.web.schema.DatabaseSchemaCheck;
import com.sdv.platform.web.schema.SchemaGateFilter;
import com.sdv.platform.web.schema.SchemaVersion;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.Filter;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sdv.platform.security.auth.oidc.SignInTestKit.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Task 0040: the ingestion contract ({@code docs/api/sdv-ingestion-v1.openapi.json}) and the running API must agree.
 * This test fails when they don't:
 * <ol>
 *   <li><b>Paths and methods:</b> every address under {@code /api/v1/ingestion} that a Vero controller maps is an
 *       operation in the file, and the other way round.</li>
 *   <li><b>Headers, parameters, bodies:</b> each operation's headers, query and path parameters (name, required) and
 *       request content type are the controller method's; the request and answer fields are the Java records'
 *       fields; the enums are the domain enums. The portable JSON Schema has the same fields.</li>
 *   <li><b>Answers:</b> every status and code the file lists for an operation is produced by the running stack, and
 *       every answer the stack produced is listed. The stack is Vero's real security chain (enterprise-jwt, real signed
 *       tokens, a loopback sign-in server), the schema gate, the real ingestion controllers and services and Vero's
 *       error handling; only the repositories, storage and crypto are stand-ins.</li>
 *   <li><b>Catalog:</b> every code is a product code listed in error-codes.md (task 0040 part 2: no generic code is
 *       left), and each status-and-code pair has its retry rule.</li>
 *   <li><b>Busy:</b> only the busy answer (503 SDV-SYS-018) carries Retry-After, in whole seconds; every 503 is
 *       "retry" (task 0040 part 2).</li>
 *   <li><b>Version:</b> {@code info.version} is semantic and is the newest changelog entry in docs/api/README.md.</li>
 * </ol>
 * It lives in this package to share the sign-in test kit ({@link SignInTestKit}).
 */
@SpringJUnitWebConfig(IngestionContractTest.ContractStack.class)
@TestPropertySource(properties = "sdv.auth.mode=enterprise-jwt")
class IngestionContractTest {
    // Maven runs tests from the module folder (backend/sdv-web).
    private static final Path DOCS = Path.of("..", "..", "docs", "api");
    private static final String INGESTION = "/api/v1/ingestion";
    private static final String READINESS = "/actuator/health/readiness";

    private static final UUID SUBMISSION = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DOCUMENT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ORIGINAL = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final byte[] PDF = TestPdfs.pdf(1, pdf -> { });
    static final com.sdv.platform.application.pdf.PdfIntakeChecks CHECKS = TestPdfs.real();
    private static final String SHA = sha(PDF);
    private static final String SUBMISSIONS = INGESTION + "/submissions";
    private static final String ONE = SUBMISSIONS + "/" + SUBMISSION;
    private static final String DOCUMENTS = ONE + "/documents";
    private static final String RETRY = DOCUMENTS + "/" + DOCUMENT + "/retry-finalization";
    private static final String CREATE_BODY = "{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":1}";

    private static final RSAKey KEY = newKey("contract-key-1");
    private static final HttpServer HUB = keyServer(KEY);

    /** The stand-ins behind the real services. Reset and given their defaults before each test. */
    static final TenantRepository TENANTS = mock(TenantRepository.class);
    static final IngestionSourceRepository SOURCES = mock(IngestionSourceRepository.class);
    static final SubmissionRepository SUBMISSION_STORE = mock(SubmissionRepository.class);
    static final DocumentIntakeRepository DOCUMENT_STORE = mock(DocumentIntakeRepository.class);
    static final MetadataFieldDefinitionRepository DEFINITIONS = mock(MetadataFieldDefinitionRepository.class);
    static final RecordSensitivityRepository RECORD_LEVELS = mock(RecordSensitivityRepository.class);
    static final AuditEventPort AUDIT = mock(AuditEventPort.class);
    static final StoragePlacementService PLACEMENT = mock(StoragePlacementService.class);
    static final StorageProvider STORAGE = mock(StorageProvider.class);
    static final StreamingCryptoPort CRYPTO = mock(StreamingCryptoPort.class);
    static final DocumentFinalizationService FINALIZATION = mock(DocumentFinalizationService.class);
    static final RecordReferences RECORDS = mock(RecordReferences.class);
    static final AccessRequestRepository LEVELS = mock(AccessRequestRepository.class);
    static final DatabaseSchemaCheck SCHEMA = mock(DatabaseSchemaCheck.class);
    /** The token decoder in use: Vero's real one, against the live loopback hub or a hub that is down. */
    static final AtomicReference<JwtDecoder> DECODER = new AtomicReference<>();
    /** Submissions created during a scenario, so a later lookup finds them. */
    static final Map<UUID, Submission> INSERTED = new java.util.concurrent.ConcurrentHashMap<>();

    private final ObjectMapper json = new ObjectMapper();
    private JsonNode api;
    private MockMvc mvc;
    @Autowired WebApplicationContext context;

    @DynamicPropertySource
    static void hub(DynamicPropertyRegistry registry) {
        registry.add("test.jwks", () -> jwks(HUB.getAddress().getPort()));
    }

    private static String jwks(int port) {
        return "http://127.0.0.1:" + port + "/certs";
    }

    @AfterAll
    static void stopHub() {
        HUB.stop(0);
    }

    @BeforeEach
    void setUp() throws Exception {
        api = json.readTree(Files.readString(DOCS.resolve("sdv-ingestion-v1.openapi.json"), StandardCharsets.UTF_8));
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(new CorrelationIdFilter(), new SchemaGateFilter(SCHEMA),
                        new com.sdv.platform.web.foundation.RequestBodyLimitFilter(1_048_576, 8_388_608),
                        context.getBean("springSecurityFilterChain", Filter.class)).build();
        defaults();
    }

    // ----------------------------------------------------------------------------------------------------------------
    // 1. Paths and methods

    @Test
    void everyIngestionAddressOfTheControllersIsAnOperationAndTheOtherWayRound() {
        Set<String> mapped = new TreeSet<>();
        for (Handler handler : ingestionHandlers()) mapped.add(handler.method() + " " + handler.path());
        Set<String> documented = new TreeSet<>();
        for (Operation op : operations()) if (op.path().startsWith(INGESTION)) documented.add(op.method() + " " + op.path());
        assertEquals(documented, mapped, "the controllers' ingestion addresses and the file's operations");
    }

    @Test
    void readinessIsDocumentedOpenAndWiredAsTheCanISendCheck() throws Exception {
        JsonNode readiness = api.path("paths").path(READINESS).path("get");
        assertFalse(readiness.isMissingNode(), READINESS + " is in the file");
        assertTrue(readiness.path("security").isArray() && readiness.path("security").isEmpty(), "no sign-in for readiness");
        assertEquals(Set.of("200", "503"), names(readiness.path("responses")));
        // No sign-in and no schema gate: even with the database older than Vero, readiness is not refused with a code.
        when(SCHEMA.current()).thenReturn(false);
        when(SCHEMA.status()).thenReturn(new SchemaVersion.Status(SchemaVersion.State.OLDER, List.of(), List.of(), 0));
        MvcResult result = mvc.perform(get(READINESS)).andReturn();
        assertNotEquals(401, result.getResponse().getStatus(), "readiness needs no sign-in");
        assertNotEquals(503, result.getResponse().getStatus(), "the schema gate lets readiness through");
        // Spring's readiness group is on and holds Vero's checks (HealthGroupsTest proves 200 and 503 with them).
        String settings = Files.readString(Path.of("src", "main", "resources", "application.yml"), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        assertTrue(settings.contains("probes:\n        enabled: true"), "readiness probes are enabled");
        assertTrue(settings.contains("include: readinessState,databaseSchema,databaseConnection,signInKeys"), "readiness group");
    }

    static final AtomicReference<java.util.function.BooleanSupplier> LICENCE = new AtomicReference<>(() -> true);

    /** What Spring's readiness endpoint answers in {@link #readinessWhenBusyIsTheBusyAnswerAndOtherwiseHasNoCode}. */
    static final AtomicReference<ResponseEntity<String>> READINESS_ANSWER = new AtomicReference<>();

    @RestController
    static class StandInReadiness {
        @org.springframework.web.bind.annotation.GetMapping(READINESS)
        ResponseEntity<String> readiness() { return READINESS_ANSWER.get(); }
    }

    /**
     * Task 0040 part 2 (master section item 2): readiness when Vero is busy is 503 with SDV-SYS-018 in the body and
     * Retry-After; any other readiness 503 has no code and no Retry-After. The file says so (503 lists SDV-SYS-018 and
     * declares the header, the Readiness body has the code), and Vero's readiness filter does it over Spring's answer
     * (the group rules that give BUSY are proven in HealthGroupsTest, the ping's BUSY in
     * DatabaseConnectionHealthIndicatorTest).
     */
    @Test
    void readinessWhenBusyIsTheBusyAnswerAndOtherwiseHasNoCode() throws Exception {
        JsonNode notReady = api.path("paths").path(READINESS).path("get").path("responses").path("503");
        assertEquals(Set.of("SDV-SYS-018"), texts(notReady.path("x-sdv-codes")), "readiness 503 can be busy");
        assertTrue(notReady.path("headers").has("Retry-After"), "readiness 503 declares Retry-After");
        assertEquals("yes", notReady.path("x-sdv-retry").asString());
        JsonNode body = api.path("components").path("schemas").path("Readiness").path("properties");
        assertTrue(body.has("code") && body.has("message"), "the Readiness body has the busy code and message");
        assertTrue(texts(body.path("status").path("examples")).contains("BUSY"));
        assertFalse(api.path("paths").path(READINESS).path("get").path("responses").path("200").has("headers"),
                "200 has no Retry-After");

        MockMvc readiness = MockMvcBuilders.standaloneSetup(new StandInReadiness())
                .addFilters(new com.sdv.platform.web.schema.ReadinessBusyAnswer()).build();
        record Case(int status, String spring, String code, boolean retryAfter) { }
        for (Case c : List.of(
                new Case(503, "{\"status\":\"BUSY\"}", "SDV-SYS-018", true),
                new Case(503, "{\"status\":\"NOT_READY\"}", null, false),
                new Case(503, "{\"status\":\"OUT_OF_SERVICE\"}", null, false),
                new Case(503, "{\"status\":\"DOWN\"}", null, false),
                new Case(503, "not json", null, false),
                new Case(200, "{\"status\":\"UP\"}", null, false))) {
            READINESS_ANSWER.set(ResponseEntity.status(c.status()).contentType(MediaType.APPLICATION_JSON).body(c.spring()));
            MvcResult result = readiness.perform(get(READINESS)).andReturn();
            String label = c.status() + " " + c.spring();
            assertEquals(c.status(), result.getResponse().getStatus(), label);
            String retryAfter = result.getResponse().getHeader("Retry-After");
            if (c.retryAfter()) {
                assertNotNull(retryAfter, label);
                assertTrue(retryAfter.matches("[1-9][0-9]*"), label + ": whole seconds above 0, got " + retryAfter);
                JsonNode answer = json.readTree(result.getResponse().getContentAsString());
                assertEquals("BUSY", answer.path("status").asString(), label);
                assertEquals(c.code(), answer.path("code").asString(), label);
                assertFalse(answer.path("message").asString().isBlank(), label);
            } else {
                assertNull(retryAfter, label + ": no Retry-After");
                assertEquals(c.spring(), result.getResponse().getContentAsString(), label + ": Spring's answer, unchanged");
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // 2. Headers, parameters, bodies, fields

    @Test
    void eachOperationHasTheControllersHeadersParametersAndContentType() {
        Map<String, Handler> handlers = new LinkedHashMap<>();
        for (Handler handler : ingestionHandlers()) handlers.put(handler.method() + " " + handler.path(), handler);
        for (Operation op : operations()) {
            if (!op.path().startsWith(INGESTION)) continue;
            Handler handler = handlers.get(op.method() + " " + op.path());
            Set<String> documented = new TreeSet<>();
            boolean correlation = false;
            for (JsonNode p : op.node().path("parameters")) {
                JsonNode param = resolve(p);
                String in = param.path("in").asString();
                String name = param.path("name").asString();
                if (in.equals("header") && name.equals(CorrelationIdFilter.HEADER)) {
                    correlation = true;
                    assertFalse(param.path("required").asBoolean(), "X-Correlation-Id is optional");
                    continue;
                }
                documented.add(in + " " + (in.equals("header") ? name.toLowerCase(Locale.ROOT) : name)
                        + (param.path("required").asBoolean() ? " required" : ""));
            }
            assertTrue(correlation, op.id() + " documents X-Correlation-Id (read by CorrelationIdFilter)");
            assertEquals(handler.parameters(), documented, op.id() + ": headers and parameters");

            JsonNode body = op.node().path("requestBody");
            Set<String> types = body.isMissingNode() ? Set.of() : names(body.path("content"));
            assertEquals(handler.consumes(), types, op.id() + ": request content type");
            if (!types.isEmpty()) assertTrue(body.path("required").asBoolean(), op.id() + ": the body is required");
        }
    }

    @Test
    void theRequestAndAnswerFieldsAreTheJavaRecordsFields() throws Exception {
        record Pair(String schema, Class<?> type) { }
        List<Pair> pairs = List.of(
                new Pair("SubmissionResponse", SubmissionController.SubmissionResponse.class),
                new Pair("ExpectedDocument", SubmissionController.ExpectedDocumentResponse.class),
                new Pair("DocumentStatus", SubmissionController.DocumentStatusResponse.class),
                new Pair("SubmissionProgress", SubmissionController.SubmissionProgressResponse.class),
                new Pair("DocumentReceipt", DocumentIntakeController.DocumentReceiptResponse.class),
                new Pair("DocumentSummary", DocumentIntakeController.DocumentSummaryResponse.class));
        for (Pair pair : pairs) {
            JsonNode schema = schema(pair.schema());
            Set<String> fields = components(pair.type());
            assertEquals(fields, names(schema.path("properties")), pair.schema() + " properties");
            assertEquals(fields, texts(schema.path("required")), pair.schema() + ": every answer field is always sent");
        }
        // The request: the record's fields, apart from those it only names to refuse them.
        JsonNode create = schema("CreateSubmissionRequest");
        Set<String> accepted = components(SubmissionController.CreateSubmissionRequest.class);
        accepted.removeAll(texts(create.path("x-sdv-refused-fields")));
        assertEquals(Set.of("sensitivityLevel"), texts(create.path("x-sdv-refused-fields")));
        assertEquals(accepted, names(create.path("properties")), "CreateSubmissionRequest properties");
        assertEquals(components(SubmissionController.ExpectedDocumentRequest.class),
                names(schema("ExpectedDocumentRequest").path("properties")));
        assertFalse(create.has("additionalProperties"), "Vero ignores unknown fields, so the file doesn't say it refuses them");

        // The portable JSON Schema has the same fields and the same required ones.
        JsonNode portable = json.readTree(Files.readString(DOCS.resolve("schemas/sdv-ingestion-submission-v1.schema.json")));
        assertEquals(names(create.path("properties")), names(portable.path("properties")), "JSON Schema properties");
        assertEquals(texts(create.path("required")), texts(portable.path("required")), "JSON Schema required");

        // Which record each operation answers with.
        Map<String, Handler> handlers = new LinkedHashMap<>();
        for (Handler handler : ingestionHandlers()) handlers.put(handler.method() + " " + handler.path(), handler);
        for (Operation op : operations()) {
            if (!op.path().startsWith(INGESTION)) continue;
            Class<?> returned = handlers.get(op.method() + " " + op.path()).answerType();
            for (Iterator<Map.Entry<String, JsonNode>> it = op.node().path("responses").properties().iterator(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> response = it.next();
                if (!response.getKey().startsWith("2")) continue;
                JsonNode schema = resolve(response.getValue()).path("content").path("application/json").path("schema");
                if (schema.path("type").asString().equals("array")) schema = schema.path("items");
                String name = schema.path("$ref").asString().replace("#/components/schemas/", "");
                Pair expected = pairs.stream().filter(p -> p.type().equals(returned)).findFirst().orElseThrow();
                assertEquals(expected.schema(), name, op.id() + " " + response.getKey() + " answers with " + returned.getSimpleName());
            }
        }
    }

    @Test
    void theEnumsAreTheDomainEnums() {
        assertEquals(names(SensitivityLevel.values()), texts(schema("SensitivityLevel").path("enum")));
        assertEquals(names(DocumentStatus.values()), texts(schema("DocumentState").path("enum")));
        assertEquals(names(SubmissionStatus.values()), texts(schema("SubmissionResponse").path("properties").path("status").path("enum")));
        assertEquals(names(ExpectedDocumentState.values()), texts(schema("ExpectedDocument").path("properties").path("state").path("enum")));
    }

    // ----------------------------------------------------------------------------------------------------------------
    // 4. Catalog and 5. version

    @Test
    void everyCodeIsAProductCodeInTheCatalogWithItsRetryRule() throws Exception {
        String catalog = Files.readString(DOCS.resolve("error-codes.md"), StandardCharsets.UTF_8);
        Set<String> productCodes = new TreeSet<>();
        for (SdvErrorCode code : SdvErrorCode.values()) productCodes.add(code.code());
        JsonNode answers = api.path("x-sdv-answers");
        Set<String> retryRules = names(api.path("x-sdv-retry"));
        assertEquals(Set.of("yes", "new-token", "no", "after-fix"), retryRules);
        Set<String> used = new TreeSet<>();
        for (Operation op : operations()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = op.node().path("responses").properties().iterator(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode response = resolve(entry.getValue());
                String status = entry.getKey();
                if (status.startsWith("2")) {
                    assertFalse(response.has("x-sdv-codes"), op.id() + " " + status);
                    continue;
                }
                if ("gateway".equals(response.path("x-sdv-sent-by").asString())) {
                    assertEquals("429", status, "only 429 is a gateway's");
                    continue;
                }
                if (op.path().equals(READINESS)) continue;
                assertTrue(response.path("x-sdv-codes").size() > 0, op.id() + " " + status + " lists its codes");
                assertEquals("#/components/schemas/Error",
                        response.path("content").path("application/json").path("schema").path("$ref").asString(),
                        op.id() + " " + status + " has the error body");
                for (String code : texts(response.path("x-sdv-codes"))) {
                    String key = status + " " + code;
                    used.add(key);
                    assertTrue(answers.has(key), "x-sdv-answers has " + key);
                    assertTrue(retryRules.contains(answers.path(key).path("retry").asString()), key + " has a retry rule");
                    // Task 0040 part 2 (owner answer 2): no generic code is left; every code is a product code.
                    assertTrue(productCodes.contains(code), code + " is a product code");
                    assertTrue(catalog.contains("### " + code + ":"), code + " is in error-codes.md");
                }
            }
        }
        assertEquals(names(answers), used, "every x-sdv-answers entry is used by an operation, and only those");
    }

    /**
     * Task 0040 part 2: the busy rule. Only 503 SDV-SYS-018 is marked {@code retryAfter}; it and every other 503 are
     * "retry" (the busy one after the header's wait, the others with back-off), apart from SDV-STO-004 (after-fix);
     * every operation's 503 lists the busy code and declares the Retry-After header; Vero never answers 429 itself.
     */
    @Test
    void theBusyAnswerIsTheOnlyOneWithRetryAfterAndEvery503IsRetry() {
        JsonNode answers = api.path("x-sdv-answers");
        Set<String> withRetryAfter = new TreeSet<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = answers.properties().iterator(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getValue().path("retryAfter").asBoolean(false)) withRetryAfter.add(entry.getKey());
            if (entry.getKey().startsWith("503 ")) {
                // Owner answer 2 to report 0040: SDV-STO-004 is after-fix (the installer must fix it). Task 0075: so is the
                // licence (the customer renews it). The rest retry.
                String rule = Set.of("503 SDV-STO-004", "503 SDV-LIC-001", "503 SDV-LIC-002").contains(entry.getKey()) ? "after-fix" : "yes";
                assertEquals(rule, entry.getValue().path("retry").asString(), entry.getKey());
            }
            assertFalse(entry.getKey().startsWith("429 "), "Vero never answers 429");
        }
        assertEquals(Set.of("503 SDV-SYS-018"), withRetryAfter, "only the busy answer has Retry-After");
        int busy = 0;
        for (Operation op : operations()) {
            if (!op.path().startsWith(INGESTION)) continue;
            JsonNode unavailable = resolve(op.node().path("responses").path("503"));
            assertTrue(texts(unavailable.path("x-sdv-codes")).contains("SDV-SYS-018"), op.id() + " can be busy");
            assertTrue(unavailable.path("headers").has("Retry-After"), op.id() + " 503 declares Retry-After");
            busy++;
        }
        assertEquals(OPERATIONS.size(), busy);
        assertTrue(api.path("x-sdv-retry").path("yes").asString().contains("Retry-After"),
                "the retry rule says to wait the header's seconds when it is there");
    }

    @Test
    void theVersionIsSemanticAndIsTheNewestChangelogEntry() throws Exception {
        String version = api.path("info").path("version").asString();
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+"), "semantic version: " + version);
        assertEquals("3.1.0", api.path("openapi").asString());
        String readme = Files.readString(DOCS.resolve("README.md"), StandardCharsets.UTF_8);
        int changelog = readme.indexOf("\n## Changelog");
        assertTrue(changelog >= 0, "README has a Changelog section");
        Matcher first = Pattern.compile("(?m)^### (\\d+\\.\\d+\\.\\d+)\\b").matcher(readme.substring(changelog));
        assertTrue(first.find(), "the changelog has an entry");
        assertEquals(version, first.group(1), "info.version is the newest changelog entry");
        assertTrue(readme.contains("sdv-ingestion-v" + version), "the entry names its release tag");
        assertTrue(api.path("paths").propertyNames().iterator().hasNext());
        for (Operation op : operations()) {
            if (op.path().startsWith(INGESTION)) assertTrue(op.path().startsWith("/api/v1/"), "the path version is v1");
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // 3. Every answer listed is produced, and every answer produced is listed

    @Test
    void everyListedAnswerIsProducedAndEveryProducedAnswerIsListed() throws Exception {
        Set<String> documented = new TreeSet<>();
        for (Operation op : operations()) {
            if (!op.path().startsWith(INGESTION)) continue;
            for (Iterator<Map.Entry<String, JsonNode>> it = op.node().path("responses").properties().iterator(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode response = resolve(entry.getValue());
                if ("gateway".equals(response.path("x-sdv-sent-by").asString())) continue;
                if (entry.getKey().startsWith("2")) documented.add(op.id() + " " + entry.getKey());
                else for (String code : texts(response.path("x-sdv-codes"))) documented.add(op.id() + " " + entry.getKey() + " " + code);
            }
        }
        Set<String> produced = new TreeSet<>();
        List<String> failures = new ArrayList<>();
        for (Scenario scenario : scenarios()) {
            defaults();
            String label = scenario.op() + " / " + scenario.name();
            try {
                scenario.setup().run(this);
                Answer answer = scenario.special() != null ? scenario.special().answer(this, scenario.request().get())
                        : call(scenario.request().get());
                String seen = answer.status() + (answer.status() < 300 ? "" : " " + answer.code());
                String wanted = scenario.status() + (scenario.status() < 300 ? "" : " " + scenario.code());
                if (!seen.equals(wanted)) {
                    failures.add(label + ": expected " + wanted + ", got " + seen + " " + answer.body());
                    continue;
                }
                checkBody(scenario.op(), answer, label, failures);
                checkRetryAfter(answer, label, failures);
                produced.add(scenario.op() + " " + seen);
            } catch (Throwable ex) {
                failures.add(label + ": " + ex);
            }
        }
        assertTrue(failures.isEmpty(), "answers that differ from the scenario:\n" + String.join("\n", failures));
        Set<String> notListed = new TreeSet<>(produced);
        notListed.removeAll(documented);
        Set<String> notProduced = new TreeSet<>(documented);
        notProduced.removeAll(produced);
        assertEquals(Set.of(), notListed, "answers the API gives that the file doesn't list");
        assertEquals(Set.of(), notProduced, "answers the file lists that no scenario produced");
    }

    /**
     * Task 0040 part 2: an answer carries Retry-After exactly when the file marks its status and code
     * {@code "retryAfter": true} (only the busy answer, 503 SDV-SYS-018), and then it is a whole number of seconds
     * above 0. Every other answer, success or error, has none.
     */
    private void checkRetryAfter(Answer answer, String label, List<String> failures) {
        boolean wanted = answer.status() >= 400
                && api.path("x-sdv-answers").path(answer.status() + " " + answer.code()).path("retryAfter").asBoolean(false);
        if (!wanted) {
            if (answer.retryAfter() != null) failures.add(label + ": Retry-After " + answer.retryAfter() + " on an answer that has none");
            return;
        }
        if (answer.retryAfter() == null || !answer.retryAfter().matches("[1-9]\\d*")) {
            failures.add(label + ": the busy answer needs Retry-After in whole seconds, got " + answer.retryAfter());
        }
    }

    /** The answer's body: the error body with exactly its four fields and the reference header; a success's fields. */
    private void checkBody(String opId, Answer answer, String label, List<String> failures) throws Exception {
        JsonNode body = json.readTree(answer.body());
        if (answer.status() >= 400) {
            if (!names(body).equals(new TreeSet<>(List.of("code", "message", "reference", "timestamp")))) {
                failures.add(label + ": error body fields " + names(body));
            } else if (answer.correlation() != null && !answer.correlation().equals(body.path("reference").asString())) {
                failures.add(label + ": reference " + body.path("reference") + " is not the X-Correlation-Id " + answer.correlation());
            }
            return;
        }
        Operation op = operations().stream().filter(o -> o.id().equals(opId)).findFirst().orElseThrow();
        JsonNode schema = resolve(op.node().path("responses").path(String.valueOf(answer.status())))
                .path("content").path("application/json").path("schema");
        List<String> problems = new ArrayList<>();
        conforms(body, schema, "$", problems);
        for (String problem : problems) failures.add(label + ": " + problem);
        if (answer.correlation() == null) failures.add(label + ": no X-Correlation-Id answer header");
    }

    private void conforms(JsonNode value, JsonNode schema, String where, List<String> problems) {
        schema = resolve(schema);
        if (schema.has("allOf")) schema = resolve(schema.path("allOf").get(0));
        if (value.isNull()) {
            if (!texts(schema.path("type")).contains("null") && !schema.path("type").asString().equals("null")) {
                problems.add(where + " is null, which the file doesn't allow");
            }
            return;
        }
        if (schema.has("enum") && !texts(schema.path("enum")).contains(value.asString())) {
            problems.add(where + " = " + value + " is not in the file's enum");
        }
        if (value.isArray()) {
            for (int i = 0; i < value.size(); i++) conforms(value.get(i), schema.path("items"), where + "[" + i + "]", problems);
        } else if (value.isObject() && schema.has("properties")) {
            if (!names(value).equals(names(schema.path("properties")))) {
                problems.add(where + " fields " + names(value) + " are not the file's " + names(schema.path("properties")));
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = value.properties().iterator(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                JsonNode property = schema.path("properties").path(field.getKey());
                if (!property.isMissingNode()) conforms(field.getValue(), property, where + "." + field.getKey(), problems);
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // The scenarios

    interface Setup { void run(IngestionContractTest test) throws Exception; }

    interface Special { Answer answer(IngestionContractTest test, MockHttpServletRequestBuilder request) throws Exception; }

    record Scenario(String op, String name, java.util.function.Supplier<MockHttpServletRequestBuilder> request,
                    Setup setup, Special special, int status, String code) { }

    /** {@code retryAfter}: the Retry-After answer header, or null (task 0040 part 2: only the busy answer has one). */
    record Answer(int status, String code, String body, String correlation, String retryAfter) { }

    private static Scenario ok(String op, String name, java.util.function.Supplier<MockHttpServletRequestBuilder> request,
                               Setup setup, int status) {
        return new Scenario(op, name, request, setup, null, status, null);
    }

    private static Scenario refused(String op, String name, java.util.function.Supplier<MockHttpServletRequestBuilder> request,
                                    Setup setup, int status, String code) {
        return new Scenario(op, name, request, setup, null, status, code);
    }

    private static final Setup NOTHING = test -> { };

    /** A valid request of each operation, signed in as the sending system. */
    private static MockHttpServletRequestBuilder base(String op) {
        return switch (op) {
            case "createSubmission" -> post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY);
            case "getSubmissionBySourceIdentity" -> get(SUBMISSIONS).param("sourceSubmissionId", "s-1");
            case "getSubmission" -> get(ONE);
            case "uploadDocument" -> upload().header("X-SDV-Source-Document-Id", "doc-1");
            case "listSubmissionDocuments" -> get(DOCUMENTS);
            case "retryDocumentFinalization" -> post(RETRY);
            default -> throw new IllegalArgumentException(op);
        };
    }

    private static MockHttpServletRequestBuilder upload() {
        return post(DOCUMENTS).contentType("application/pdf").content(PDF).header("X-SDV-Filename", "a.pdf");
    }

    private static MockHttpServletRequestBuilder upload(byte[] pdf) { return post(DOCUMENTS).contentType("application/pdf").content(pdf).header("X-SDV-Filename", "a.pdf"); }

    private static MockHttpServletRequestBuilder create(String body) {
        return post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static final List<String> OPERATIONS = List.of("createSubmission", "getSubmissionBySourceIdentity",
            "getSubmission", "uploadDocument", "listSubmissionDocuments", "retryDocumentFinalization");
    private static final Set<String> AUDITED = Set.of("createSubmission", "uploadDocument", "retryDocumentFinalization");

    private List<Scenario> scenarios() {
        List<Scenario> all = new ArrayList<>();
        // Answers every operation can give: sign-in, tenant and sending system, Vero's state.
        for (String op : OPERATIONS) {
            all.add(refused(op, "no token", () -> base(op).header("X-No-Token", "1"), NOTHING, 401, "SDV-AUT-001"));
            all.add(refused(op, "malformed Authorization header", () -> base(op).header("Authorization", "Bearer !!!"),
                    NOTHING, 401, "SDV-AUT-003"));
            all.add(refused(op, "expired token", () -> base(op).header("Authorization", "Bearer " + sign(KEY, expired())),
                    NOTHING, 401, "SDV-AUT-003"));
            all.add(refused(op, "token for another audience", () -> base(op).header("Authorization",
                    "Bearer " + sign(KEY, sendingSystem().audience("someone-else"))), NOTHING, 401, "SDV-AUT-003"));
            // Task 0041 (contract 1.1.0): the client binding, not a claim, gives the entity and the source.
            all.add(refused(op, "client-ID claims that disagree", () -> base(op).header("Authorization",
                    "Bearer " + sign(KEY, sendingSystem().claim("client_id", "another-client"))), NOTHING, 401, "SDV-AUT-005"));
            all.add(refused(op, "client not set up as a sending system", () -> base(op).header("Authorization",
                    "Bearer " + sign(KEY, sendingSystem().claim("azp", "unbound-client"))), NOTHING, 401, "SDV-AUT-011"));
            all.add(refused(op, "client's entity or login connection not active", () -> signed(base(op)),
                    test -> CONNECTION_ACTIVE.set(false), 401, "SDV-AUT-011"));
            all.add(refused(op, "entity claim not the client binding's", () -> base(op).header("Authorization",
                    "Bearer " + sign(KEY, sendingSystem().claim("sdv_entity_id", UUID.randomUUID().toString()))), NOTHING, 401, "SDV-AUT-012"));
            all.add(refused(op, "source-ID claim not the client binding's", () -> base(op).header("Authorization",
                    "Bearer " + sign(KEY, sendingSystem().claim("sdv_source_id", "not-a-uuid"))), NOTHING, 401, "SDV-AUT-012"));
            all.add(refused(op, "client binding names a sending system the entity doesn't have", () -> signed(base(op)),
                    test -> SOURCE_SET_UP.set(false), 401, "SDV-AUT-013"));
            all.add(refused(op, "a person's token, no sign-in connection", () -> base(op).header("Authorization",
                    "Bearer " + sign(KEY, person("svc-client", List.of("vero-users"))))
                    , test -> CONNECTION_ACTIVE.set(false), 401, "SDV-AUT-006"));
            all.add(refused(op, "a person's token, no Vero role", () -> base(op).header("Authorization",
                    "Bearer " + sign(KEY, person("svc-client", List.of("not-a-vero-group")))), NOTHING, 401, "SDV-AUT-007"));
            all.add(new Scenario(op, "sign-in switched off", () -> base(op), NOTHING, IngestionContractTest::signInOff,
                    401, "SDV-SYS-006"));
            all.add(refused(op, "a person's sign-in", () -> base(op).header("Authorization",
                    "Bearer " + sign(KEY, person("pat", List.of("vero-users")))), NOTHING, 403, "SDV-AUT-002"));
            all.add(refused(op, "tenant suspended", () -> signed(base(op)),
                    test -> TENANT_STATUS.set(TenantStatus.SUSPENDED), 403, "SDV-AUT-009"));
            all.add(refused(op, "sending system paused", () -> signed(base(op)),
                    test -> source(IngestionSourceStatus.DISABLED), 403, "SDV-INT-005"));
            all.add(refused(op, "Accept header Vero can't answer, after the work is done", () -> signed(base(op)).accept(MediaType.TEXT_PLAIN),
                    test -> succeeds(op), 406, "SDV-SYS-015"));
            all.add(refused(op, "unexpected failure", () -> signed(base(op)),
                    test -> when(TENANTS.findById(TENANT)).thenThrow(new RuntimeException("boom")), 500, "SDV-SYS-013"));
            all.add(refused(op, "internal check failed", () -> signed(base(op)),
                    test -> when(TENANTS.findById(TENANT)).thenThrow(new InternalCheckFailed("check")), 500, "SDV-SYS-016"));
            all.add(refused(op, "database older", () -> signed(base(op)), test -> schema(SchemaVersion.State.OLDER),
                    503, "SDV-SYS-001"));
            all.add(refused(op, "database newer", () -> signed(base(op)), test -> schema(SchemaVersion.State.NEWER),
                    503, "SDV-SYS-002"));
            all.add(refused(op, "database away", () -> signed(base(op)),
                    test -> when(TENANTS.findById(TENANT)).thenThrow(new DataAccessResourceFailureException("down")),
                    503, "SDV-SYS-014"));
            // Task 0040 part 2: the database connection pool is full (HikariCP's timeout, no connection failure).
            all.add(refused(op, "too busy: no database connection free", () -> signed(base(op)),
                    test -> when(TENANTS.findById(TENANT)).thenThrow(new CannotGetJdbcConnectionException(
                            "Failed to obtain JDBC Connection", new SQLTransientConnectionException(
                            "HikariPool-1 - Connection is not available, request timed out after 30000ms"))),
                    503, "SDV-SYS-018"));
            all.add(refused(op, "sign-in server's keys can't be fetched", () -> signed(base(op)),
                    test -> DECODER.set(decoder(closedPort())), 503, "SDV-AUT-004"));
            if (AUDITED.contains(op)) {
                all.add(refused(op, "audit not written", () -> signed(base(op)), test -> auditFails(op), 500, "SDV-SYS-017"));
            }
            if (!op.equals("createSubmission") && !op.equals("getSubmissionBySourceIdentity")) {
                String path = switch (op) {
                    case "getSubmission" -> SUBMISSIONS + "/not-a-uuid";
                    case "uploadDocument", "listSubmissionDocuments" -> SUBMISSIONS + "/not-a-uuid/documents";
                    default -> DOCUMENTS + "/not-a-uuid/retry-finalization";
                };
                MockHttpServletRequestBuilder template = base(op);
                String method = template.buildRequest(new org.springframework.mock.web.MockServletContext()).getMethod();
                all.add(refused(op, "ID in the address not a UUID", () -> {
                    MockHttpServletRequestBuilder r = method.equals("GET") ? get(path) : post(path);
                    if (op.equals("uploadDocument")) r = r.contentType("application/pdf").content(PDF).header("X-SDV-Filename", "a.pdf");
                    return signed(r);
                }, NOTHING, 400, "SDV-SYS-015"));
            }
        }

        // createSubmission
        String c = "createSubmission";
        all.add(ok(c, "created", () -> signed(create(CREATE_BODY)), NOTHING, 201));
        all.add(ok(c, "a retry of the same request", () -> signed(create(CREATE_BODY)), test -> test.alreadyCreated(CREATE_BODY), 200));
        all.add(refused(c, "count 0", () -> signed(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":0}")),
                NOTHING, 400, "SDV-INT-006"));
        all.add(refused(c, "count over the limit", () -> signed(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":101}")),
                NOTHING, 400, "SDV-INT-007"));
        all.add(refused(c, "unknown metadata field", () -> signed(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":1,\"metadata\":{\"colour\":\"red\"}}")),
                NOTHING, 400, "SDV-INT-008"));
        all.add(refused(c, "metadata value breaks its rule", () -> signed(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":1,\"metadata\":{\"amount\":\"x\"}}")),
                test -> fields(field("amount", MetadataFieldType.INTEGER, "{}")), 400, "SDV-INT-009"));
        all.add(refused(c, "unknown record level", () -> signed(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":1,\"recordSensitivityLevel\":\"confidential\"}")),
                NOTHING, 400, "SDV-SEN-002"));
        all.add(refused(c, "document level on the submission", () -> signed(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":1,\"sensitivityLevel\":\"STANDARD\"}")),
                NOTHING, 400, "SDV-SEN-006"));
        all.add(refused(c, "JSON that doesn't parse", () -> signed(create("{\"sourceSubmissionId\":")), NOTHING, 400, "SDV-SYS-015"));
        all.add(refused(c, "a retry with another body", () -> signed(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":2}")),
                test -> test.alreadyCreated(CREATE_BODY), 409, "SDV-INT-011"));
        all.add(refused(c, "a retry with another record level", () -> signed(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":1,\"recordSensitivityLevel\":\"CONFIDENTIAL\"}")),
                test -> test.alreadyCreated(CREATE_BODY), 409, "SDV-SEN-004"));
        all.add(refused(c, "not JSON", () -> signed(post(SUBMISSIONS).contentType(MediaType.TEXT_PLAIN).content(CREATE_BODY)),
                NOTHING, 415, "SDV-SYS-015"));
        all.add(refused(c, "a field's rule is broken", () -> signed(create("{\"sourceSubmissionId\":\"s-1\",\"expectedDocumentCount\":1,\"metadata\":{\"code\":\"x\"}}")),
                test -> fields(field("code", MetadataFieldType.TEXT, "{\"pattern\":\"[\"}")), 500, "SDV-INT-010"));

        // getSubmissionBySourceIdentity
        String b = "getSubmissionBySourceIdentity";
        all.add(ok(b, "found", () -> signed(get(SUBMISSIONS).param("sourceSubmissionId", "sub-1")),
                test -> when(SUBMISSION_STORE.findBySourceIdentity(TENANT, SOURCE, "sub-1")).thenReturn(Optional.of(submission())), 200));
        all.add(refused(b, "parameter missing", () -> signed(get(SUBMISSIONS)), NOTHING, 400, "SDV-SYS-015"));
        all.add(refused(b, "parameter too long", () -> signed(get(SUBMISSIONS).param("sourceSubmissionId", "x".repeat(201))),
                NOTHING, 400, "SDV-INT-006"));
        all.add(refused(b, "no such submission", () -> signed(get(SUBMISSIONS).param("sourceSubmissionId", "nope")),
                NOTHING, 404, "SDV-INT-004"));

        // getSubmission
        all.add(ok("getSubmission", "found", () -> signed(get(ONE)), NOTHING, 200));
        all.add(refused("getSubmission", "no such submission", () -> signed(get(SUBMISSIONS + "/" + UUID.randomUUID())),
                NOTHING, 404, "SDV-INT-004"));

        // uploadDocument
        // Task 0075: the licence stops new work, before anything of the request is read; status queries go on.
        for (String op : AUDITED) {
            all.add(refused(op, "the licence doesn't allow intake", () -> signed(base(op)), test -> LICENCE.set(() -> false), 503, "SDV-LIC-001"));
            all.add(refused(op, "the licence can't be checked", () -> signed(base(op)), test -> LICENCE.set(() -> {
                throw new com.documentplatform.licensing.LicenceException(com.documentplatform.licensing.LicenceRefusal.LICENCE_TAMPERED);
            }), 503, "SDV-LIC-002"));
        }
        String u = "uploadDocument";
        all.add(ok(u, "received", () -> signed(upload().header("X-SDV-Source-Document-Id", "doc-1")), NOTHING, 201));
        all.add(ok(u, "a retry of the same upload", () -> signed(upload().header("X-SDV-Source-Document-Id", "doc-1")),
                test -> stored("a.pdf", SensitivityLevel.STANDARD), 200));
        all.add(ok(u, "an exact duplicate", () -> signed(upload().header("X-SDV-Source-Document-Id", "doc-2")),
                test -> originalExists(), 200));
        all.add(refused(u, "unknown level", () -> signed(upload().header("X-SDV-Source-Document-Id", "doc-1")
                .header("X-SDV-Sensitivity-Level", "confidential")), NOTHING, 400, "SDV-SEN-001"));
        all.add(refused(u, "record level on the upload", () -> signed(upload().header("X-SDV-Source-Document-Id", "doc-1")
                .header("X-SDV-Record-Sensitivity-Level", "CONFIDENTIAL")), NOTHING, 400, "SDV-SEN-005"));
        all.add(refused(u, "no identity", () -> signed(upload()), NOTHING, 400, "SDV-INT-012"));
        all.add(refused(u, "file name is a path", () -> signed(post(DOCUMENTS).contentType("application/pdf").content(PDF)
                .header("X-SDV-Filename", "../a.pdf").header("X-SDV-Source-Document-Id", "doc-1")), NOTHING, 400, "SDV-INT-013"));
        all.add(refused(u, "not a PDF", () -> signed(post(DOCUMENTS).contentType("application/pdf")
                .content("hello".getBytes(StandardCharsets.US_ASCII)).header("X-SDV-Filename", "a.pdf")
                .header("X-SDV-Source-Document-Id", "doc-1")), NOTHING, 400, "SDV-INT-015"));
        all.add(refused(u, "over the submission's total size", () -> signed(upload().header("X-SDV-Source-Document-Id", "doc-1")),
                test -> tenant(new TenantGuardrails(1_000_000, 2_000, 100, 5, 24, 90, 95, null)), 400, "SDV-INT-016"));
        all.add(refused(u, "X-SDV-Filename missing", () -> signed(post(DOCUMENTS).contentType("application/pdf").content(PDF)
                .header("X-SDV-Source-Document-Id", "doc-1")), NOTHING, 400, "SDV-SYS-015"));
        all.add(refused(u, "no such submission", () -> signed(post(SUBMISSIONS + "/" + UUID.randomUUID() + "/documents")
                .contentType("application/pdf").content(PDF).header("X-SDV-Filename", "a.pdf")
                .header("X-SDV-Source-Document-Id", "doc-1")), NOTHING, 404, "SDV-INT-004"));
        all.add(refused(u, "submission already full", () -> signed(upload().header("X-SDV-Source-Document-Id", "doc-3")),
                test -> when(DOCUMENT_STORE.countReceived(TENANT, SUBMISSION)).thenReturn(2L), 409, "SDV-INT-016"));
        all.add(refused(u, "a retry with another file name", () -> signed(upload().header("X-SDV-Source-Document-Id", "doc-1")),
                test -> stored("other-name.pdf", SensitivityLevel.STANDARD), 409, "SDV-INT-017"));
        all.add(refused(u, "a retry with another level", () -> signed(upload().header("X-SDV-Source-Document-Id", "doc-1")),
                test -> stored("a.pdf", SensitivityLevel.CONFIDENTIAL), 409, "SDV-SEN-003"));
        all.add(refused(u, "storage volume with no provider", () -> signed(upload().header("X-SDV-Source-Document-Id", "doc-1")),
                test -> when(STORAGE.providerId()).thenReturn("SOMETHING_ELSE"), 503, "SDV-STO-004"));
        all.add(refused(u, "PDF too large", () -> signed(upload().header("X-SDV-Source-Document-Id", "doc-1")),
                test -> tenant(new TenantGuardrails(10, 2_000, 100, 1_000_000, 24, 90, 95, null)), 413, "SDV-INT-014"));
        all.add(refused(u, "not application/pdf", () -> signed(post(DOCUMENTS).contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content(PDF).header("X-SDV-Filename", "a.pdf").header("X-SDV-Source-Document-Id", "doc-1")),
                NOTHING, 415, "SDV-SYS-015"));
        all.add(refused(u, "storage full", () -> signed(upload().header("X-SDV-Source-Document-Id", "doc-1")),
                test -> when(PLACEMENT.select(eq(TENANT), anyLong())).thenThrow(new NoWritableStorageException("full")),
                503, "SDV-STO-001"));

        all.add(refused("createSubmission", "larger than Vero takes", () -> signed(post(SUBMISSIONS).contentType(MediaType.APPLICATION_JSON)
                .content(" ".repeat(8_388_609))), NOTHING, 413, "SDV-SYS-019"));

        // Task 0063: the checks of the PDF itself.
        all.add(refused(u, "not readable", () -> signed(upload("%PDF-1.7\nnot really\n%%EOF".getBytes(StandardCharsets.US_ASCII))
                .header("X-SDV-Source-Document-Id", "doc-1")), NOTHING, 400, "SDV-INT-019"));
        all.add(refused(u, "encrypted", () -> signed(upload(TestPdfs.pdf(1, pdf -> pdf.protect(
                new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner", "open", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()))))
                .header("X-SDV-Source-Document-Id", "doc-1")), NOTHING, 400, "SDV-INT-020"));
        all.add(refused(u, "active content", () -> signed(upload(TestPdfs.pdf(1, pdf -> pdf.getDocumentCatalog().setOpenAction(
                new org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript("app.alert(1)"))))
                .header("X-SDV-Source-Document-Id", "doc-1")), NOTHING, 400, "SDV-INT-021"));
        all.add(refused(u, "too many pages", () -> signed(upload(TestPdfs.pdf(2, pdf -> { })).header("X-SDV-Source-Document-Id", "doc-1")),
                test -> tenant(new TenantGuardrails(1_000_000, 1, 100, 1_000_000, 24, 90, 95, null)), 400, "SDV-INT-022"));

        // listSubmissionDocuments
        all.add(ok("listSubmissionDocuments", "listed", () -> signed(get(DOCUMENTS)),
                test -> when(DOCUMENT_STORE.findDocuments(TENANT, SOURCE, SUBMISSION))
                        .thenReturn(List.of(document("a.pdf", DocumentStatus.AVAILABLE, SensitivityLevel.STANDARD))), 200));
        all.add(refused("listSubmissionDocuments", "no such submission",
                () -> signed(get(SUBMISSIONS + "/" + UUID.randomUUID() + "/documents")), NOTHING, 404, "SDV-INT-004"));

        // retryDocumentFinalization
        String r = "retryDocumentFinalization";
        all.add(ok(r, "finished", () -> signed(post(RETRY)), test -> {
            when(DOCUMENT_STORE.findDocumentById(TENANT, SOURCE, SUBMISSION, DOCUMENT))
                    .thenReturn(Optional.of(document("a.pdf", DocumentStatus.PROCESSING_FAILED, SensitivityLevel.STANDARD)));
            when(FINALIZATION.finalizeDocument(eq(TENANT), eq(SOURCE), eq(SUBMISSION), eq(DOCUMENT), any(), any()))
                    .thenReturn(document("a.pdf", DocumentStatus.AVAILABLE, SensitivityLevel.STANDARD));
        }, 200));
        all.add(refused(r, "no such document", () -> signed(post(RETRY)), NOTHING, 404, "SDV-INT-018"));
        all.add(refused(r, "state can't be finished again", () -> signed(post(RETRY)),
                test -> when(DOCUMENT_STORE.findDocumentById(TENANT, SOURCE, SUBMISSION, DOCUMENT))
                        .thenReturn(Optional.of(document("a.pdf", DocumentStatus.PROCESSING, SensitivityLevel.STANDARD))),
                409, "SDV-INT-018"));
        return all;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Running a request

    private static MockHttpServletRequestBuilder signed(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + sign(KEY, sendingSystem()));
    }

    private static JWTClaimsSet.Builder expired() {
        Instant now = Instant.now();
        return sendingSystem().issueTime(Date.from(now.minusSeconds(900))).expirationTime(Date.from(now.minusSeconds(600)));
    }

    /** The client's answer, as SignInTestKit.answer gives it, with the error code and the X-Correlation-Id header. */
    private Answer call(MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletResponse original;
        String body;
        String retryAfter;
        int status;
        try {
            MvcResult result = mvc.perform(request).andReturn();
            original = result.getResponse();
            status = original.getStatus();
            if (original.getErrorMessage() == null) {
                body = original.getContentAsString();
                retryAfter = original.getHeader("Retry-After");
            } else {
                MockHttpServletRequest same = (MockHttpServletRequest) result.getRequest();
                same.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, original.getStatus());
                MockHttpServletResponse page = new MockHttpServletResponse();
                new SdvErrorController().error(same, page);
                body = page.getContentAsString();
                retryAfter = page.getHeader("Retry-After");
            }
        } catch (Exception ex) {
            MockHttpServletRequest error = new MockHttpServletRequest("GET", "/error");
            error.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 500);
            error.setAttribute(RequestDispatcher.ERROR_EXCEPTION, ex);
            MockHttpServletResponse page = new MockHttpServletResponse();
            new SdvErrorController().error(error, page);
            return answer(page.getStatus(), page.getContentAsString(), null, page.getHeader("Retry-After"));
        }
        return answer(status, body, original.getHeader(CorrelationIdFilter.HEADER), retryAfter);
    }

    private Answer answer(int status, String body, String correlation, String retryAfter) throws Exception {
        String code = null;
        if (status >= 400 && body != null && !body.isBlank()) code = json.readTree(body).path("code").asString(null);
        return new Answer(status, code, body, correlation, retryAfter);
    }

    /** Sign-in switched off: the entry point the disabled chain uses, then Vero's error page (as SignInCodesTest does). */
    private static Answer signInOff(IngestionContractTest test, MockHttpServletRequestBuilder request) throws Exception {
        MockHttpServletRequest built = request.buildRequest(new org.springframework.mock.web.MockServletContext());
        built.setAttribute(CorrelationIdFilter.REFERENCE_ATTRIBUTE, "ref-off");
        MockHttpServletResponse response = new MockHttpServletResponse();
        SignInAnswers.entryPoint("disabled").commence(built, response, new InsufficientAuthenticationException("anonymous"));
        built.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, response.getStatus());
        MockHttpServletResponse page = new MockHttpServletResponse();
        new SdvErrorController().error(built, page);
        return test.answer(page.getStatus(), page.getContentAsString(), "ref-off", page.getHeader("Retry-After"));
    }

    // ----------------------------------------------------------------------------------------------------------------
    // The stand-ins' defaults and changes

    private void defaults() throws Exception {
        LICENCE.set(() -> true);
        reset(TENANTS, SOURCES, SUBMISSION_STORE, DOCUMENT_STORE, DEFINITIONS, RECORD_LEVELS, AUDIT, PLACEMENT, STORAGE,
                CRYPTO, FINALIZATION, RECORDS, LEVELS, SCHEMA);
        DECODER.set(decoder(HUB.getAddress().getPort()));
        resetPlatform();
        TENANT_STATUS.set(TenantStatus.ACTIVE);
        when(SCHEMA.current()).thenReturn(true);
        tenant(TenantGuardrails.defaults());
        source(IngestionSourceStatus.ACTIVE);
        INSERTED.clear();
        when(SUBMISSION_STORE.findById(eq(TENANT), eq(SOURCE), any())).thenAnswer(call -> {
            UUID id = call.getArgument(2);
            return SUBMISSION.equals(id) ? Optional.of(submission()) : Optional.ofNullable(INSERTED.get(id));
        });
        when(SUBMISSION_STORE.insertIfAbsent(any())).thenAnswer(call -> {
            Submission inserted = call.getArgument(0);
            INSERTED.put(inserted.submissionId(), inserted);
            return new SubmissionRepository.InsertResult(inserted, true);
        });
        when(DOCUMENT_STORE.lockSubmission(TENANT, SOURCE, SUBMISSION)).thenReturn(submission());
        when(RECORDS.ofSubmission(TENANT, SOURCE, SUBMISSION)).thenReturn(
                new RecordReferences.ResolvedRecord(SOURCE, SUBMISSION, List.of(SUBMISSION)));
        StorageVolume volume = new StorageVolume(UUID.randomUUID(), TENANT, "LOCAL_FILESYSTEM", "primary-a", "/tmp",
                StorageVolumeState.values()[0], 1, 80, 95, NOW, NOW);
        StoragePlacementService.PlacementDecision decision = mock(StoragePlacementService.PlacementDecision.class);
        when(decision.volume()).thenReturn(volume);
        when(PLACEMENT.select(eq(TENANT), anyLong())).thenReturn(decision);
        when(STORAGE.providerId()).thenReturn("LOCAL_FILESYSTEM");
        when(STORAGE.store(eq(TENANT), eq(volume), any())).thenAnswer(call -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            call.getArgument(2, StorageProvider.ContentWriter.class).write(out);
            return new StorageProvider.StoredObject("obj", out.size());
        });
        when(CRYPTO.encrypt(org.mockito.ArgumentMatchers.argThat(b -> b != null && b.entityId().equals(TENANT)), any(), any())).thenAnswer(call -> {
            byte[] plain = call.getArgument(1, InputStream.class).readAllBytes();
            call.getArgument(2, OutputStream.class).write(plain);
            return new StreamingCryptoPort.CryptoEnvelope("p", 1, "k", "ref", "v1", plain.length, "c");
        });
        when(FINALIZATION.finalizeDocument(eq(TENANT), eq(SOURCE), eq(SUBMISSION), any(), any(), any())).thenAnswer(call -> {
            ArgumentCaptor<Document> stored = ArgumentCaptor.forClass(Document.class);
            verify(DOCUMENT_STORE, atLeastOnce()).insertDocument(stored.capture());
            return stored.getValue();
        });
    }

    private static JwtDecoder decoder(int port) {
        return new OidcJwtConfiguration().enterpriseJwtDecoder(ISSUER, jwks(port), AUDIENCE, 7200);
    }

    private static void schema(SchemaVersion.State state) {
        when(SCHEMA.current()).thenReturn(false);
        when(SCHEMA.status()).thenReturn(new SchemaVersion.Status(state, List.of(), List.of(), 0));
    }

    private static void tenant(TenantGuardrails guardrails) {
        when(TENANTS.findById(TENANT)).thenReturn(Optional.of(
                new Tenant(TENANT, "acme", "Acme", TenantStatus.ACTIVE, guardrails, NOW, NOW)));
    }

    private static void source(IngestionSourceStatus status) {
        when(SOURCES.findById(TENANT, SOURCE)).thenReturn(Optional.of(new IngestionSource(SOURCE, TENANT, "loans", "Loans",
                status, IngestionSourceAuthType.values()[0], null, null, NOW, NOW)));
    }

    private static Submission submission() {
        return new Submission(SUBMISSION, TENANT, SOURCE, "sub-1", 2, "sha", SubmissionStatus.OPEN, NOW, NOW, null);
    }

    private static MetadataFieldDefinition field(String key, MetadataFieldType type, String rules) {
        return new MetadataFieldDefinition(UUID.randomUUID(), TENANT, SOURCE, key, 1, key, type, false, false, false,
                MetadataNormalization.NONE, MetadataSearchMode.EXACT, rules, true, NOW, null, NOW);
    }

    private static void fields(MetadataFieldDefinition... definitions) {
        when(DEFINITIONS.findCurrent(TENANT, SOURCE)).thenReturn(List.of(definitions));
    }

    private static Document document(String filename, DocumentStatus status, SensitivityLevel level) {
        return new Document(DOCUMENT, TENANT, SUBMISSION, SOURCE, "doc-1", null, filename, "application/pdf",
                SHA, (long) PDF.length, status, null, NOW, null, null, 0, null, null, level);
    }

    /** A document with this identity is already stored (a retry of the same upload when name and level match). */
    private static void stored(String filename, SensitivityLevel level) {
        when(DOCUMENT_STORE.findBySourceDocumentId(TENANT, SOURCE, SUBMISSION, "doc-1"))
                .thenReturn(Optional.of(document(filename, DocumentStatus.AVAILABLE, level)));
    }

    /** The same file is already stored for this record under another delivery. */
    private static void originalExists() {
        Document original = new Document(ORIGINAL, TENANT, SUBMISSION, SOURCE, "first-delivery", null, "contract.pdf",
                "application/pdf", SHA, (long) PDF.length, DocumentStatus.AVAILABLE, null, NOW, null, NOW, 1, null, null,
                SensitivityLevel.STANDARD);
        when(DOCUMENT_STORE.findDuplicateOf(TENANT, SOURCE, List.of(SUBMISSION), SHA)).thenReturn(Optional.of(original));
        when(LEVELS.lockDocument(TENANT, ORIGINAL)).thenReturn(Optional.of(
                new AccessRequestRepository.LockedDocument(ORIGINAL, "AVAILABLE", SensitivityLevel.STANDARD)));
        when(DOCUMENT_STORE.findDocumentByIdForTenant(TENANT, ORIGINAL)).thenReturn(Optional.of(original));
    }

    /** The first create of {@code body} went through: later lookups by sourceSubmissionId find it. */
    private void alreadyCreated(String body) throws Exception {
        Answer first = call(signed(create(body)));
        assertEquals(201, first.status(), first.body());
        ArgumentCaptor<Submission> created = ArgumentCaptor.forClass(Submission.class);
        verify(SUBMISSION_STORE).insertIfAbsent(created.capture());
        when(SUBMISSION_STORE.findBySourceIdentity(TENANT, SOURCE, "s-1")).thenReturn(Optional.of(created.getValue()));
    }

    /** What a successful answer of {@code op} reads (the base request of the other operations succeeds as is). */
    private static void succeeds(String op) {
        if (op.equals("getSubmissionBySourceIdentity")) {
            when(SUBMISSION_STORE.findBySourceIdentity(TENANT, SOURCE, "s-1")).thenReturn(Optional.of(submission()));
        }
        if (op.equals("retryDocumentFinalization")) {
            when(DOCUMENT_STORE.findDocumentById(TENANT, SOURCE, SUBMISSION, DOCUMENT))
                    .thenReturn(Optional.of(document("a.pdf", DocumentStatus.PROCESSING_FAILED, SensitivityLevel.STANDARD)));
            when(FINALIZATION.finalizeDocument(eq(TENANT), eq(SOURCE), eq(SUBMISSION), eq(DOCUMENT), any(), any()))
                    .thenReturn(document("a.pdf", DocumentStatus.AVAILABLE, SensitivityLevel.STANDARD));
        }
    }

    private static void auditFails(String op) {
        InternalCheckFailed notWritten = InternalCheckFailed.auditNotWritten("audit store down", null);
        if (op.equals("retryDocumentFinalization")) {
            when(DOCUMENT_STORE.findDocumentById(TENANT, SOURCE, SUBMISSION, DOCUMENT))
                    .thenReturn(Optional.of(document("a.pdf", DocumentStatus.PROCESSING_FAILED, SensitivityLevel.STANDARD)));
            when(FINALIZATION.finalizeDocument(any(), any(), any(), any(), any(), any())).thenThrow(notWritten);
        } else {
            doThrow(notWritten).when(AUDIT).append(any());
        }
    }

    private static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Reading the file and the controllers

    record Operation(String id, String method, String path, JsonNode node) { }

    private List<Operation> operations() {
        List<Operation> out = new ArrayList<>();
        for (Iterator<Map.Entry<String, JsonNode>> paths = api.path("paths").properties().iterator(); paths.hasNext(); ) {
            Map.Entry<String, JsonNode> path = paths.next();
            for (Iterator<Map.Entry<String, JsonNode>> ops = path.getValue().properties().iterator(); ops.hasNext(); ) {
                Map.Entry<String, JsonNode> op = ops.next();
                out.add(new Operation(op.getValue().path("operationId").asString(), op.getKey().toUpperCase(Locale.ROOT),
                        path.getKey(), op.getValue()));
            }
        }
        return out;
    }

    private JsonNode resolve(JsonNode node) {
        while (node != null && node.has("$ref")) {
            String ref = node.path("$ref").asString();
            assertTrue(ref.startsWith("#/"), "local reference: " + ref);
            JsonNode target = api;
            for (String part : ref.substring(2).split("/")) target = target.path(part);
            assertFalse(target.isMissingNode(), "reference resolves: " + ref);
            node = target;
        }
        return node;
    }

    private JsonNode schema(String name) {
        JsonNode schema = api.path("components").path("schemas").path(name);
        assertFalse(schema.isMissingNode(), "schema " + name);
        return schema;
    }

    record Handler(String method, String path, Set<String> parameters, Set<String> consumes, Class<?> answerType) { }

    /** Every handler method of Vero's controllers whose address is under /api/v1/ingestion. */
    private static List<Handler> ingestionHandlers() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Handler> out = new ArrayList<>();
        for (var candidate : scanner.findCandidateComponents("com.sdv.platform.web")) {
            Class<?> type;
            try {
                type = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException ex) {
                throw new IllegalStateException(ex);
            }
            if (type.getName().contains("Test") || type.getEnclosingClass() != null) continue;
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String prefix = base == null || base.path().length == 0 ? "" : base.path()[0];
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                String path = prefix + (mapping.path().length == 0 ? "" : mapping.path()[0]);
                if (!path.startsWith(INGESTION)) continue;
                assertEquals(1, mapping.method().length, path + " has one method");
                Set<String> parameters = new TreeSet<>();
                for (Parameter parameter : method.getParameters()) {
                    RequestHeader header = parameter.getAnnotation(RequestHeader.class);
                    RequestParam param = parameter.getAnnotation(RequestParam.class);
                    PathVariable variable = parameter.getAnnotation(PathVariable.class);
                    if (header != null) parameters.add("header " + header.name().toLowerCase(Locale.ROOT) + (header.required() ? " required" : ""));
                    if (param != null) parameters.add("query " + (param.name().isEmpty() ? parameter.getName() : param.name()) + (param.required() ? " required" : ""));
                    if (variable != null) parameters.add("path " + (variable.name().isEmpty() ? parameter.getName() : variable.name()) + " required");
                }
                Set<String> consumes = new TreeSet<>(Arrays.asList(mapping.consumes()));
                boolean jsonBody = Arrays.stream(method.getParameters()).anyMatch(p -> p.isAnnotationPresent(RequestBody.class));
                if (jsonBody && consumes.isEmpty()) consumes.add(MediaType.APPLICATION_JSON_VALUE);
                out.add(new Handler(mapping.method()[0].name(), path, parameters, consumes, answerType(method)));
            }
        }
        return out;
    }

    /** The record a handler answers with (inside ResponseEntity or List when it is wrapped). */
    private static Class<?> answerType(Method method) {
        Type type = method.getGenericReturnType();
        while (type instanceof ParameterizedType parameterized
                && (parameterized.getRawType() == ResponseEntity.class || parameterized.getRawType() == List.class)) {
            type = parameterized.getActualTypeArguments()[0];
        }
        return (Class<?>) type;
    }

    private static Set<String> components(Class<?> record) {
        Set<String> out = new TreeSet<>();
        for (RecordComponent component : record.getRecordComponents()) out.add(component.getName());
        return out;
    }

    private static Set<String> names(JsonNode object) {
        Set<String> out = new TreeSet<>();
        object.propertyNames().iterator().forEachRemaining(out::add);
        return out;
    }

    private static Set<String> texts(JsonNode array) {
        Set<String> out = new TreeSet<>();
        if (array.isArray()) for (JsonNode item : array) out.add(item.asString());
        else if (array.isString()) out.add(array.asString());
        return out;
    }

    private static Set<String> names(Enum<?>[] values) {
        Set<String> out = new TreeSet<>();
        for (Enum<?> value : values) out.add(value.name());
        return out;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // The stack

    /** Vero's chain in enterprise-jwt mode (as SignInTestKit.JwtStack), with the real ingestion controllers. */
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import(SecurityConfiguration.class)
    static class ContractStack {
        /** Task 0075: the licence's answer; intake is refused by it as in the running app. */
        @org.springframework.context.annotation.Bean
        org.springframework.web.servlet.config.annotation.WebMvcConfigurer licenceGate() {
            return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer() {
                @Override
                public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
                    registry.addInterceptor(new com.sdv.platform.web.licensing.LicenceGate(() -> LICENCE.get().getAsBoolean()));
                }
            };
        }

        @Bean JwtDecoder enterpriseJwtDecoder(@Value("${test.jwks}") String jwks) {
            return token -> DECODER.get().decode(token);
        }

        @Bean OidcJwtAuthenticationConverter converter() {
            return SignInTestKit.converter();
        }

        @Bean ActiveTenantCache tenantCheck() {
            return new ActiveTenantCache(tenantId -> {
                var status = TENANT_STATUS.get();
                return status == null || !TENANT.equals(tenantId) ? Optional.empty()
                        : Optional.of(new Tenant(TENANT, "acme", "Acme", status, TenantGuardrails.defaults(), Instant.EPOCH, Instant.EPOCH));
            }, 0);
        }

        @Bean FoundationExceptionHandler errors() { return new FoundationExceptionHandler(); }

        @Bean SubmissionController submissionController() {
            return new SubmissionController(new SecurityContextFacade(), new SubmissionService(TENANTS, SOURCES, DEFINITIONS,
                    SUBMISSION_STORE, DOCUMENT_STORE, RECORD_LEVELS, AUDIT, com.sdv.platform.application.port.RecordValuesPort.NONE, null));
        }

        @Bean DocumentIntakeController documentIntakeController() {
            return new DocumentIntakeController(new SecurityContextFacade(), new DocumentIntakeService(TENANTS, SOURCES,
                    SUBMISSION_STORE, DOCUMENT_STORE, PLACEMENT, List.of(STORAGE), CRYPTO, FINALIZATION, AUDIT, RECORDS,
                    LEVELS, mock(PlatformTransactionManager.class), CHECKS));
        }
    }
}
