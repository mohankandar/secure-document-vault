package com.sdv.platform.web.notifications;

import com.sdv.platform.application.notifications.RecipientListService;
import com.sdv.platform.application.notifications.RoleHolderService;
import com.sdv.platform.application.notifications.RoleSeenWindow;
import com.sdv.platform.application.port.AuditEventPort;
import com.sdv.platform.persistence.notifications.JdbcNotificationRecipientRepository;
import com.sdv.platform.persistence.notifications.JdbcRoleHolderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Task 0100 (VSEC-203), on a real PostgreSQL (changeset 051): who holds which list role; a list takes only them; a
 * sign-in without the role takes them off in the same transaction; messages go only to them.
 */
@EnabledIfEnvironmentVariable(named = "SDV_IT_JDBC_URL", matches = "jdbc:postgresql://localhost:5433/sdv_schema_test(_[1-9])?")
class RoleHoldersPostgresTest {
    private static final UUID A = UUID.fromString("10000000-0000-0000-0000-000000010001"), B = UUID.fromString("10000000-0000-0000-0000-000000010002");
    private static final String CUSTOMER = "TEN-ROLE-HOLDERS-0100";

    private final DriverManagerDataSource dataSource = new DriverManagerDataSource(
            System.getenv("SDV_IT_JDBC_URL"), System.getenv("SDV_IT_JDBC_USER"), System.getenv("SDV_IT_JDBC_PASSWORD"));
    private final NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
    private final DataSourceTransactionManager tx = new DataSourceTransactionManager(dataSource);
    private final JdbcRoleHolderRepository holders = new JdbcRoleHolderRepository(jdbc);
    private final JdbcNotificationRecipientRepository lists = new JdbcNotificationRecipientRepository(jdbc);
    private final AuditEventPort audit = mock(AuditEventPort.class);
    private final RoleHolderService service = new RoleHolderService(holders, lists, audit, tx);
    private final RecipientListService screen = new RecipientListService(lists, holders, new RoleSeenWindow("30"), audit, tx);
    private final RecipientListService.Actor admin = new RecipientListService.Actor(A, "ba-1", "Bea Admin", "corr");

    @Test
    void holdersListsAndRemovalAgreeOnTheDatabase() {
        for (UUID e : List.of(A, B)) {
            jdbc.getJdbcTemplate().update("insert into tenants(tenant_id, tenant_key, display_name, status) values (?, ?, 'Roles', 'ACTIVE')", e, "role-holders-" + e);
        }
        service.seen(CUSTOMER, A, "u1", Set.of("BUSINESS_ADMIN", "DOCUMENT_STEWARD", "PLATFORM_OPERATIONS_ADMIN"));
        service.seen(CUSTOMER, B, "u1", Set.of("IT_ADMIN"));
        assertEquals(Set.of("BUSINESS_ADMIN", "DOCUMENT_STEWARD"), holders.roles(A, "u1"));
        assertEquals(Set.of("u1"), holders.holders(CUSTOMER, null, "IT_ADMIN", Instant.EPOCH), "any entity of the customer");
        assertEquals(Set.of(), holders.holders(CUSTOMER, A, "IT_ADMIN", Instant.EPOCH));

        screen.add(admin, CUSTOMER, A, "BUSINESS_ADMIN", "u1", "u1@example.test");
        screen.add(admin, CUSTOMER, null, "IT_ADMIN", "u1", "u1@example.test");
        var recipients = new ListRecipients(lists, holders, new RoleSeenWindow("30"));
        var tenant = com.documentplatform.notifications.TenantId.of(CUSTOMER);
        var entity = com.documentplatform.notifications.EntityId.of(A.toString());
        assertEquals(1, recipients.resolve(tenant, entity, VeroCatalogue.BUSINESS_ADMIN).size());
        // Answer Q-5: not seen at a sign-in for 31 days counts as not confirmed.
        jdbc.getJdbcTemplate().update("update role_holders set seen_at = now() - interval '31 days' where entity_id = ? and user_id = 'u1'", A);
        assertTrue(recipients.resolve(tenant, entity, VeroCatalogue.BUSINESS_ADMIN).isEmpty());
        assertFalse(screen.list(CUSTOMER, A).getFirst().confirmed());
        service.seen(CUSTOMER, A, "u1", Set.of("BUSINESS_ADMIN", "DOCUMENT_STEWARD"));
        assertEquals(1, recipients.resolve(tenant, entity, VeroCatalogue.BUSINESS_ADMIN).size(), "a sign-in confirms again");

        // The Business Admin role taken away in the login system: the next sign-in shows it.
        var removed = service.seen(CUSTOMER, A, "u1", Set.of("DOCUMENT_STEWARD"));
        assertEquals(List.of("BUSINESS_ADMIN"), removed.stream().map(e -> e.role()).toList());
        assertTrue(recipients.resolve(tenant, entity, VeroCatalogue.BUSINESS_ADMIN).isEmpty());
        assertEquals(1, lists.list(CUSTOMER, null).size(), "still IT Admin in B");
        assertFalse(screen.list(CUSTOMER, null).isEmpty() || !screen.list(CUSTOMER, null).getFirst().confirmed());

        service.seen(CUSTOMER, B, "u1", Set.of());
        assertTrue(lists.list(CUSTOMER, null).isEmpty(), "no entity shows IT Admin any more");
        assertEquals(Set.of(), holders.roles(B, "u1"));
        holders.replace(CUSTOMER, B, "u1", Set.of(), Instant.now());
        assertEquals(Set.of(), holders.roles(B, "u1"), "replacing with nothing twice is fine");
    }
}
