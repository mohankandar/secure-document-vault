package com.sdv.platform.web.licensing;

import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.fingerprint.Fingerprinter;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.security.MessageDigest;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0090, on a real PostgreSQL (changeset 046): named seats survive a restart, count once, and name nobody. */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class NamedSeatsPostgresTest {
    private static final UUID A = UUID.fromString("10000000-0000-0000-0000-000000009001"), B = UUID.fromString("10000000-0000-0000-0000-000000009002");
    private static final CustomerId CUSTOMER = CustomerId.fromTenantId("TEN-NAMED-SEATS-0001");
    private static final YearMonth MARCH = YearMonth.of(2027, 3);
    private final JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD")));
    private final InMemoryKeyService keys = new InMemoryKeyService().createKeysOnDemand();

    /** A Vero process: its own objects, the same database and key service. */
    private NamedSeats process() {
        return new NamedSeats(db, new Fingerprinter(keys), CUSTOMER);
    }

    @Test
    void aRestartKeepsTheCountAndTwoProcessesCountOnce() throws Exception {
        for (UUID e : List.of(A, B)) db.update("insert into tenants(tenant_id, tenant_key, display_name, status) values (?, ?, 'Seats', 'ACTIVE')", e, "named-seats-" + e);
        process().add(A, MARCH, Set.of("dana", "priya@example.test"));
        assertEquals(2, process().count(A, MARCH), "after a restart");
        NamedSeats other = process();
        other.add(A, MARCH, Set.of("dana", "jonas", "Dana"));
        assertEquals(4, other.count(A, MARCH), "dana once; another spelling is another subject");
        other.add(A, MARCH, Set.of());
        // A rotation in the middle of the month counts nobody twice.
        keys.rotateFingerprintKey(CUSTOMER);
        process().add(A, MARCH, Set.of("dana", "jonas"));
        assertEquals(4, process().count(A, MARCH));

        // Another month and another entity are their own, and their hashes have nothing in common.
        process().add(A, MARCH.plusMonths(1), Set.of("dana"));
        process().add(B, MARCH, Set.of("dana"));
        List<String> hashes = db.queryForList("select person_hash from usage_named_seats where tenant_id in (?, ?)", String.class, A, B);
        assertEquals(6, new HashSet<>(hashes).size());
        for (String hash : hashes) {
            assertTrue(hash.startsWith("f"), hash);
            for (String person : List.of("dana", "Dana", "priya@example.test", "jonas")) {
                assertFalse(hash.contains(person));
                assertFalse(hash.contains(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(person.getBytes()))), "not a plain hash of a name");
            }
        }
        // Without the customer's key nobody can make the same hash.
        NamedSeats foreign = new NamedSeats(db, new Fingerprinter(new InMemoryKeyService().createKeysOnDemand()), CUSTOMER);
        foreign.add(A, MARCH, Set.of("dana"));
        assertEquals(5, process().count(A, MARCH));

        // The key service away: nothing is written, and it says so to the caller, who keeps the people.
        keys.down(true);
        assertThrows(CryptoException.class, () -> process().add(A, MARCH, Set.of("new")));
        keys.down(false);
        assertFalse(new NamedSeats(db, (Fingerprinter) null, (CustomerId) null).usable());

        assertEquals(6, process().deleteOlder(MARCH.plusMonths(2)), "March of both entities; April stays");
        assertEquals(1, process().count(A, MARCH.plusMonths(1)));
        assertEquals(0, process().deleteOlder(MARCH.plusMonths(2)));
    }
}
