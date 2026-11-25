package com.sdv.platform.web.licensing;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.fingerprint.Fingerprinter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Task 0090: the named seats of a month, kept across restarts and shared by every Vero of the database. A person is a keyed hash
 * (HMAC with the customer's fingerprint key, in the key service) over the entity, the month and the sign-in's subject, so nobody
 * can turn it back or follow a person from month to month. Without a key service nothing is counted.
 */
@Component
public class NamedSeats {
    private final JdbcTemplate db;
    private final Fingerprinter fingerprints;
    private final CustomerId customer;

    @org.springframework.beans.factory.annotation.Autowired
    public NamedSeats(JdbcTemplate db, ObjectProvider<Fingerprinter> fingerprints, ObjectProvider<CustomerId> customer) {
        this(db, fingerprints.getIfAvailable(), customer.getIfAvailable());
    }

    NamedSeats(JdbcTemplate db, Fingerprinter fingerprints, CustomerId customer) {
        this.db = db;
        this.fingerprints = fingerprints;
        this.customer = customer;
    }

    boolean usable() {
        return fingerprints != null && customer != null;
    }

    /** Writes the people; the same one twice is one. Two calls of the key service, however many people. */
    void add(UUID entity, YearMonth month, Collection<String> subjects) {
        if (subjects.isEmpty()) return;
        // The oldest usable version: a rotation in the middle of a month doesn't count people twice.
        int version = Collections.min(fingerprints.usableVersions(customer));
        List<Fingerprinter.Input> inputs = subjects.stream()
                .map(s -> new Fingerprinter.Input("usage-named-seat:" + entity + ":" + month, sha256(s))).toList();
        db.batchUpdate("insert into usage_named_seats (tenant_id, month, person_hash) values (?, ?, ?) on conflict do nothing",
                fingerprints.ofAll(customer, version, inputs).stream().map(f -> new Object[]{entity, month.toString(), f.text()}).toList());
    }

    int count(UUID entity, YearMonth month) {
        Integer n = db.queryForObject("select count(*) from usage_named_seats where tenant_id = ? and month = ?", Integer.class, entity, month.toString());
        return n == null ? 0 : n;
    }

    /** The current and the previous month stay. */
    int deleteOlder(YearMonth current) {
        return db.update("delete from usage_named_seats where month < ?", current.minusMonths(1).toString());
    }

    /** Capitals and spaces of a subject count; the key service's fingerprint would not see them. */
    private static String sha256(String subject) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(subject.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
