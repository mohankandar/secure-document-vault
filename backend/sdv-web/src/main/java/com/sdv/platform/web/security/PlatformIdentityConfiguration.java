package com.sdv.platform.web.security;

import com.documentplatform.identity.admission.ActiveEntityCandidates;
import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityGroupGrants;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.IdentityClaims;
import com.documentplatform.identity.jdbc.LicenceSlots;
import com.documentplatform.identity.jdbc.PostgresEntityStore;
import com.documentplatform.identity.jdbc.PostgresGrantHistory;
import com.documentplatform.identity.jdbc.PostgresIdentityLookup;
import com.documentplatform.identity.jdbc.PostgresLoginConnectionStore;
import com.documentplatform.identity.jdbc.PostgresMachineClients;
import com.documentplatform.identity.jdbc.PostgresMachineEntityBindings;
import com.documentplatform.identity.store.EntityStore;
import com.documentplatform.identity.store.GrantHistory;
import com.documentplatform.identity.store.LicensedSlots;
import com.documentplatform.identity.store.LoginConnectionStore;
import com.documentplatform.identity.store.SlotActivation;
import com.documentplatform.licensing.ClockGuard;
import com.documentplatform.licensing.LastSeenStore;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceStore;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.TrustedKeys;
import com.documentplatform.licensing.jdbc.PostgresLastSeenStore;
import com.documentplatform.licensing.jdbc.PostgresLicenceStore;
import com.documentplatform.licensing.usage.ChainHead;
import com.documentplatform.licensing.usage.UsageEntry;
import com.documentplatform.licensing.usage.UsageLedger;
import com.documentplatform.licensing.usage.UsageRecord;
import com.sdv.platform.application.port.IngestionSourceRepository;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.domain.identity.VeroProduct;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import com.sdv.platform.security.SdvRole;
import com.sdv.platform.security.auth.oidc.OidcJwtAuthenticationConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Task 0041: Vero on platform-core identity. The idn_ tables are platform-core's (identity schema V1 to V3, made by
 * the schema step, filled by setup); these beans read them for Vero's product {@value VeroProduct#CODE}.
 * <p>
 * Licensing is not turned on (owner answer 15). Slot activation needs to know which slots the stored licence lists and
 * whether it is in force: that is read only ({@link #licensedSlots}), with the release's trusted keys
 * ({@code sdv.identity.trusted-keys-file}). Nothing is counted, limited or enforced; the clock check stores nothing,
 * and no waiting licence is promoted from here.
 */
@Configuration
public class PlatformIdentityConfiguration {
    private static final Logger log = LoggerFactory.getLogger(PlatformIdentityConfiguration.class);
    /** As the old connection cache: a grant or connection change reaches sign-in within 30 seconds. */
    static final Duration CACHE = Duration.ofSeconds(30);

    /** The only EntityGroupGrants and ActiveEntityCandidates bean: platform-core's lookup behind a 30 s cache. */
    @Bean
    CachedIdentityLookup cachedIdentityLookup(DataSource ds) {
        PostgresIdentityLookup lookup = new PostgresIdentityLookup(ds, VeroProduct.CODE);
        return new CachedIdentityLookup(lookup, lookup, CACHE, Clock.systemUTC());
    }

    @Bean
    OidcJwtAuthenticationConverter.Senders platformClientBindings(DataSource ds) {
        PostgresMachineClients clients = new PostgresMachineClients(ds, VeroProduct.CODE);
        PostgresMachineEntityBindings bindings = new PostgresMachineEntityBindings(ds, VeroProduct.CODE, clients);
        return new OidcJwtAuthenticationConverter.Senders() {
            @Override
            public Optional<com.documentplatform.identity.store.MachineBinding> activeBinding(String clientId) {
                return clients.activeBinding(clientId);
            }

            @Override
            public Set<EntityId> admissible(ConnectionKey key, String clientId) {
                return bindings.find(key, clientId);
            }
        };
    }

    @Bean
    OidcJwtAuthenticationConverter.Sources veroSendingSystems(IngestionSourceRepository sources) {
        return (entity, key) -> sources.findByKey(entity, key).map(s -> s.sourceId());
    }

    @Bean
    OidcJwtAuthenticationConverter.SignInLookups platformSignInLookups(PlatformIdentityReads reads) {
        return new OidcJwtAuthenticationConverter.SignInLookups() {
            @Override
            public boolean connectionActive(ConnectionKey key) {
                return reads.connectionActive(key);
            }

            @Override
            public List<String> activatableSlots(ConnectionKey key, Object groups) {
                return reads.notActivatedSlotsGranting(key, IdentityClaims.values(groups), SdvRole.BUSINESS_ADMIN.name());
            }
        };
    }

    @Bean
    GrantHistory platformGrantHistory(DataSource ds) {
        return new PostgresGrantHistory(ds);
    }

    @Bean
    EntityStore platformEntityStore(DataSource ds) {
        return new PostgresEntityStore(ds);
    }

    @Bean
    LoginConnectionStore platformLoginConnections(DataSource ds) {
        return new PostgresLoginConnectionStore(ds);
    }

    @Bean
    SlotActivation slotActivation(EntityStore entities, LicensedSlots licensedSlots) {
        return new SlotActivation(entities, licensedSlots, Clock.systemUTC());
    }

    /**
     * The stored licence's slots, read only. Without {@code sdv.identity.trusted-keys-file} no licence can be checked,
     * so activation is refused (NO_LICENCE); Vero still starts.
     */
    @Bean
    LicensedSlots licensedSlots(DataSource ds, @Value("${sdv.identity.trusted-keys-file:}") String keysFile) {
        if (keysFile == null || keysFile.isBlank()) {
            log.info("sdv.identity.trusted-keys-file is not set: entity activation is refused until it is.");
            return Optional::empty;
        }
        TrustedKeys keys;
        try {
            keys = TrustedKeys.parse(Files.readString(Path.of(keysFile.trim()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new StartupFailure(SdvErrorCode.SYS_005, "sdv.identity.trusted-keys-file could not be read as a "
                    + "trusted-keys file (" + e.getClass().getSimpleName() + ").");
        }
        return LicenceSlots.of(readOnlyLicence(ds, keys));
    }

    /** The stored licence, read only: verified with {@code keys}; nothing stored, counted or promoted. */
    public static LicenceService readOnlyLicence(DataSource ds, TrustedKeys keys) {
        return new LicenceService(readOnly(new PostgresLicenceStore(ds)), new LicenceVerifier(keys),
                new ClockGuard(Clock.systemUTC(), readOnly(new PostgresLastSeenStore(ds))), NO_USAGE, VeroProduct.CODE, null);
    }

    static LicenceStore readOnly(LicenceStore store) {
        return new LicenceStore() {
            @Override public Optional<Stored> get(Slot slot) { return store.get(slot); }
            @Override public void put(Slot slot, Stored licence) { throw new UnsupportedOperationException("read only"); }
            @Override public void remove(Slot slot) { throw new UnsupportedOperationException("read only"); }
            /** A renewal waiting for its start date is promoted by licensing, once Vero turns it on; not here. */
            @Override public void promoteWaiting(String licenceId) { }
        };
    }

    static LastSeenStore readOnly(LastSeenStore store) {
        return new LastSeenStore() {
            @Override public Optional<Instant> lastSeen() { return store.lastSeen(); }
            @Override public void advanceTo(Instant seen) { }
            @Override public void resetTo(Instant to, String resetId) { throw new UnsupportedOperationException("read only"); }
            @Override public boolean resetApplied(String resetId) { return store.resetApplied(resetId); }
        };
    }

    /** Licensing is not on: nothing is ever counted. */
    static final UsageLedger NO_USAGE = new UsageLedger() {
        @Override public UsageRecord append(UsageEntry entry) { throw new UnsupportedOperationException("licensing is not on"); }
        @Override public List<UsageRecord> records() { return List.of(); }
        @Override public Optional<ChainHead> head() { return Optional.empty(); }
        @Override public Snapshot snapshot() { return new Snapshot(List.of(), Optional.empty()); }
    };

    /**
     * The grants and ACTIVE entities of a connection, kept up to {@link #CACHE} per connection key. A failed read
     * (null) is never kept, so sign-in is refused only while the database is away.
     */
    public static final class CachedIdentityLookup implements EntityGroupGrants, ActiveEntityCandidates {
        private record Entry<T>(T value, Instant until) { }

        private final EntityGroupGrants grants;
        private final ActiveEntityCandidates candidates;
        private final Duration ttl;
        private final Clock clock;
        private final Map<ConnectionKey, Entry<Map<EntityId, Map<String, Set<String>>>>> grantCache = new ConcurrentHashMap<>();
        private final Map<ConnectionKey, Entry<Set<EntityId>>> candidateCache = new ConcurrentHashMap<>();

        public CachedIdentityLookup(EntityGroupGrants grants, ActiveEntityCandidates candidates, Duration ttl, Clock clock) {
            this.grants = grants;
            this.candidates = candidates;
            this.ttl = ttl;
            this.clock = clock;
        }

        @Override
        public Map<EntityId, Map<String, Set<String>>> activeGrants(ConnectionKey key) {
            return cached(grantCache, key, grants::activeGrants);
        }

        @Override
        public Set<EntityId> find(ConnectionKey key) {
            return cached(candidateCache, key, candidates::find);
        }

        private <T> T cached(Map<ConnectionKey, Entry<T>> cache, ConnectionKey key, Function<ConnectionKey, T> read) {
            if (key == null) return read.apply(null);
            Instant now = clock.instant();
            Entry<T> hit = cache.get(key);
            if (hit != null && now.isBefore(hit.until())) return hit.value();
            T value = read.apply(key);
            if (value != null) {
                if (cache.size() > 256) cache.clear();
                cache.put(key, new Entry<>(value, now.plus(ttl)));
            }
            return value;
        }
    }
}
