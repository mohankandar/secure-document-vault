package com.sdv.platform.web.admin;

import com.documentplatform.identity.store.SlotActivation;
import com.documentplatform.licensing.LicenceBuild;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceVerifier;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.application.port.TenantRepository;
import com.sdv.platform.domain.error.CodedRefusal;
import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.persistence.identity.PlatformIdentityReads;
import com.sdv.platform.security.auth.EntityChoicePrincipal;
import com.sdv.platform.security.auth.oidc.OidcJwtAuthenticationConverter;
import com.sdv.platform.web.security.VeroLicenceBuild;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

/**
 * Task 0098: the first-start screen's two addresses, for a person signed in without an entity
 * ({@link EntityChoicePrincipal}; the entity-choice filter lets them through).
 * <ul>
 *   <li>{@code GET /api/v1/first-start}: the slots this person may activate first (they are IT Admin of them).</li>
 *   <li>{@code POST /api/v1/first-start/activate} ({@code slotId}, {@code name}, {@code licence}: the licence file's
 *       text): activates the first entity ({@link FirstStart}).</li>
 * </ul>
 * Once any entity is ACTIVE both answer exactly as an address with nothing behind it (404), for everyone.
 */
@RestController
@RequestMapping("/api/v1/first-start")
public class FirstStartController {
    private final FirstStart firstStart;
    private final OidcJwtAuthenticationConverter.SignInLookups lookups;

    public FirstStartController(FirstStart firstStart, OidcJwtAuthenticationConverter.SignInLookups lookups) {
        this.firstStart = firstStart;
        this.lookups = lookups;
    }

    public record Open(List<String> slots) { }

    public record ActivateRequest(String slotId, String name, String licence) { }

    @GetMapping
    public Open open() throws NoResourceFoundException {
        try {
            return new Open(firstStart.open(person(HttpMethod.GET, "")));
        } catch (FirstStart.Gone gone) {
            throw nothingHere(HttpMethod.GET, "");
        }
    }

    @PostMapping("/activate")
    public FirstStart.Activated activate(@RequestBody(required = false) ActivateRequest body) throws NoResourceFoundException {
        FirstStart.Person person = person(HttpMethod.POST, "/activate");
        try {
            return firstStart.activate(person, body == null ? null : body.slotId(), body == null ? null : body.name(),
                    body == null ? null : body.licence());
        } catch (FirstStart.Gone gone) {
            throw nothingHere(HttpMethod.POST, "/activate");
        }
    }

    /** Only someone signed in without an entity; anyone else works in an ACTIVE entity, so there is no first start. */
    private FirstStart.Person person(HttpMethod method, String path) throws NoResourceFoundException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal == null) throw new CodedRefusal.Unauthorized(SdvErrorCode.AUT_001, "Please sign in again.", "no sign-in");
        if (!(principal instanceof EntityChoicePrincipal choice)
                || !(authentication.getDetails() instanceof OidcJwtAuthenticationConverter.VerifiedSignIn signIn)) {
            throw nothingHere(method, path);
        }
        // Fresh from the grants, not what the sign-in saw: a grant taken away since counts at once.
        return new FirstStart.Person(choice.subject(), choice.displayName(), lookups.firstStartSlots(signIn.key(), signIn.groups()));
    }

    private static NoResourceFoundException nothingHere(HttpMethod method, String path) {
        String address = "api/v1/first-start" + path;
        return new NoResourceFoundException(method, address, address);
    }

    @Configuration
    static class Wiring {
        /** The install-wide lock for the first activation. */
        static final long LOCK = 0x5644525f46495253L;

        @Bean
        FirstStart firstStart(PlatformIdentityReads reads, LicenceService licence, ObjectProvider<LicenceBuild> own, SlotActivation activation,
                              TenantRepository tenants, AuditEventPort audit, DataSource dataSource,
                              org.springframework.jdbc.core.JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager transactions) {
            LicenceVerifier verifier = java.util.Optional.ofNullable(own.getIfAvailable()).or(VeroLicenceBuild::load)
                    .map(b -> new LicenceVerifier(b.keys())).orElse(null);
            return new FirstStart(reads::anyEntityActive, () -> {
                try {
                    return licence.status().licence();
                } catch (RuntimeException none) {
                    return null;
                }
            }, (bytes, customer) -> {
                if (verifier == null) throw new IllegalStateException("no licence keys in this build");
                return verifier.verify(bytes, customer);
            }, activation, tenants, audit, postgresLock(dataSource), Clock.systemUTC(), new JdbcFirstStartAttempts(jdbc),
                    new org.springframework.transaction.support.TransactionTemplate(transactions));
        }

        static FirstStart.Lock postgresLock(DataSource dataSource) {
            return new FirstStart.Lock() {
                @Override
                public <T> T exclusively(Supplier<T> work) {
                    try (Connection c = dataSource.getConnection()) {
                        call(c, "select pg_advisory_lock(?)");
                        try {
                            return work.get();
                        } finally {
                            call(c, "select pg_advisory_unlock(?)");
                        }
                    } catch (SQLException ex) {
                        throw new IllegalStateException("The first-start lock could not be taken.", ex);
                    }
                }
            };
        }

        private static void call(Connection c, String sql) throws SQLException {
            try (PreparedStatement st = c.prepareStatement(sql)) {
                st.setLong(1, LOCK);
                st.execute();
            }
        }
    }
}
