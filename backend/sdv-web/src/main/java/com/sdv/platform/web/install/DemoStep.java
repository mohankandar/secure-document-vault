package com.sdv.platform.web.install;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.jdbc.LicenceSlots;
import com.documentplatform.identity.jdbc.PostgresEntityStore;
import com.documentplatform.identity.store.ActivationOutcome;
import com.documentplatform.identity.store.SlotActivation;
import com.documentplatform.licensing.LicenceException;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.TrustedKeys;
import com.sdv.platform.web.UsageReporting;
import com.sdv.platform.web.security.PlatformIdentityConfiguration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import com.sdv.platform.persistence.foundation.JdbcFoundationRepository;
import com.sdv.platform.domain.audit.AuditEvent;
import com.sdv.platform.domain.audit.AuditOutcome;

import javax.sql.DataSource;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Vero's demo step (task 0041, owner answer 1 item 3): the demo-only records that setup does not make, written by a
 * command after setup, never at startup. It replaces the local seed changeset (002).
 *
 * <ol>
 *   <li>Checks that the licence setup stored is signed by a <b>development</b> key of the given trusted-keys file:
 *       the demo step never runs against a customer's licence.</li>
 *   <li>Activates each demo entity slot with its name through platform-core's {@link SlotActivation} (what a Business
 *       Admin does in the app), and makes Vero's row for it (table {@code tenants}, same UUID, derived from the slot).
 *       Each activation is audited (ENTITY_ACTIVATED, actor {@value #ACTOR}).</li>
 * </ol>
 *
 * <pre>
 * demo-step apply --keys development-trusted-keys.json [--slot ENT-...=Name ...]
 * </pre>
 * Default slots: {@code ENT-VERO-DEMO-0001=Acme Lending} and {@code ENT-VERO-DEMO-0002=Local Isolation Entity}.
 * Running it again changes nothing. Database settings in the environment only: {@code SDV_DEMO_DB_URL},
 * {@code SDV_DEMO_DB_USERNAME}, {@code SDV_DEMO_DB_PASSWORD}. Exit codes: 0 done, 1 failed, 2 refused. Output never
 * holds a password.
 */
public final class DemoStep {
    static final String ACTOR = "vero-demo-step";
    static final Map<String, String> DEFAULT_SLOTS = Map.of(
            "ENT-VERO-DEMO-0001", "Acme Lending",
            "ENT-VERO-DEMO-0002", "Local Isolation Entity");

    private final PrintStream out;
    private final PrintStream err;
    private final Clock clock;

    DemoStep(PrintStream out, PrintStream err, Clock clock) {
        this.out = out;
        this.err = err;
        this.clock = clock;
    }

    public static void main(String[] args) {
        UsageReporting.switchOff();
        System.exit(new DemoStep(System.out, System.err, Clock.systemUTC()).run(args, System.getenv()::get));
    }

    record Options(Path keys, Map<String, String> slots) { }

    static Options parse(String[] args) {
        if (args.length == 0 || !"apply".equals(args[0])) throw new IllegalArgumentException("The first word must be apply.");
        Path keys = null;
        Map<String, String> slots = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value after " + args[i] + ".");
            String value = args[i + 1];
            switch (args[i]) {
                case "--keys" -> keys = Path.of(value);
                case "--slot" -> {
                    int eq = value.indexOf('=');
                    if (eq < 0 || !EntityUuid.isSlotId(value.substring(0, eq))) {
                        throw new IllegalArgumentException("--slot must be ENT-...=Name.");
                    }
                    slots.put(value.substring(0, eq), value.substring(eq + 1));
                }
                default -> throw new IllegalArgumentException("Unknown option " + args[i] + ".");
            }
        }
        if (keys == null) throw new IllegalArgumentException("--keys is required (the development trusted-keys file).");
        if (slots.isEmpty()) slots.putAll(new java.util.TreeMap<>(DEFAULT_SLOTS));
        return new Options(keys, slots);
    }

    int run(String[] args, Function<String, String> env) {
        Options options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            err.println("DEMO_BAD_OPTION: " + e.getMessage());
            return 2;
        }
        String url = env.apply("SDV_DEMO_DB_URL");
        String user = env.apply("SDV_DEMO_DB_USERNAME");
        String password = env.apply("SDV_DEMO_DB_PASSWORD");
        if (url == null || user == null || password == null || !url.startsWith("jdbc:postgresql:")) {
            err.println("DEMO_DB_SETTINGS_MISSING: set SDV_DEMO_DB_URL (jdbc:postgresql:...), SDV_DEMO_DB_USERNAME and SDV_DEMO_DB_PASSWORD.");
            return 2;
        }
        TrustedKeys keys;
        try {
            keys = TrustedKeys.parse(Files.readString(options.keys(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            err.println("DEMO_KEYS_UNREADABLE: the trusted-keys file " + options.keys() + " could not be read.");
            return 2;
        }
        DataSource ds = new DriverManagerDataSource(url, user, password);
        try {
            return apply(ds, keys, options.slots());
        } catch (SQLException | RuntimeException e) {
            err.println("DEMO_FAILED: " + e.getClass().getSimpleName() + (e instanceof SQLException sql ? " (SQL state " + sql.getSQLState() + ")" : ""));
            return 1;
        }
    }

    int apply(DataSource ds, TrustedKeys keys, Map<String, String> slots) throws SQLException {
        LicenceService licence = PlatformIdentityConfiguration.readOnlyLicence(ds, keys);
        String keyId;
        try {
            keyId = licence.status().licence().keyId();
        } catch (LicenceException e) {
            err.println("DEMO_NO_LICENCE: no licence that these keys verify is stored (" + e.code() + "). Run setup apply first.");
            return 2;
        }
        if (!keys.isDevelopmentKey(keyId)) {
            err.println("DEMO_NOT_A_DEMO_LICENCE: the stored licence is not signed by a development key; the demo step runs only on demo installs.");
            return 2;
        }
        SlotActivation activation = new SlotActivation(new PostgresEntityStore(ds), LicenceSlots.of(licence), clock);
        for (Map.Entry<String, String> slot : slots.entrySet()) {
            ActivationOutcome outcome = activation.activate(slot.getKey(), slot.getValue(), ACTOR);
            if (outcome.refused() && outcome.result() != ActivationOutcome.Result.ALREADY_ACTIVATED) {
                err.println("DEMO_ACTIVATION_REFUSED: " + outcome.result() + ": " + outcome.message());
                return 2;
            }
            UUID entity = EntityUuid.fromSlotId(slot.getKey());
            String name = outcome.entity() != null && outcome.entity().displayName() != null ? outcome.entity().displayName() : slot.getValue();
            boolean created = createTenant(ds, entity, slot.getKey().toLowerCase(Locale.ROOT), name);
            out.println((outcome.refused() ? "  unchanged   " : "  activated   ") + slot.getKey() + " (entity " + entity + ")"
                    + (created ? ", Vero entity row made" : ""));
        }
        out.println("Demo step done.");
        return 0;
    }

    /** Vero's row for the entity, through the same repository the app's activation uses (with its first retention policy). */
    private boolean createTenant(DataSource ds, UUID entity, String key, String name) {
        JdbcFoundationRepository repository = new JdbcFoundationRepository(new NamedParameterJdbcTemplate(ds));
        boolean created = repository.createEntity(entity, key, name);
        if (created) {
            String slot = key.toUpperCase(Locale.ROOT);
            repository.append(new AuditEvent(UUID.randomUUID(), entity, "SYSTEM", ACTOR, "ENTITY_ACTIVATED", "ENTITY", slot,
                    clock.instant(), null, AuditOutcome.SUCCESS, "{\"slotId\":\"" + slot + "\",\"demoStep\":true}"));
        }
        return created;
    }
}
