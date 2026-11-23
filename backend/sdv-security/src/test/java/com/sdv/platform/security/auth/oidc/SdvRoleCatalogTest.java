package com.sdv.platform.security.auth.oidc;

import com.sdv.platform.security.SdvRole;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0041: one list of roles. The product-roles file setup reads and Vero's {@link SdvRole} must agree exactly. */
class SdvRoleCatalogTest {
    private final SdvRoleCatalog catalog = SdvRoleCatalog.shipped();

    @Test
    void theShippedFileListsExactlyVerosRolesInOrder() {
        assertEquals(Arrays.stream(SdvRole.values()).map(Enum::name).toList(), catalog.allRoles());
    }

    @Test
    void platformOperationsAdminIsThePlatformRoleAndNoEntityRole() {
        assertEquals(java.util.Set.of("PLATFORM_OPERATIONS_ADMIN"), catalog.platformRoles());
        assertTrue(catalog.isPlatformRole("PLATFORM_OPERATIONS_ADMIN"));
        assertFalse(catalog.entityRoles().contains("PLATFORM_OPERATIONS_ADMIN"));
    }

    @Test
    void rolesAreFoundIgnoringCaseAndUnknownOrOldNamesAreNot() {
        assertEquals(Optional.of("IT_ADMIN"), catalog.canonicalEntityRole("it_admin"));
        assertEquals(Optional.empty(), catalog.canonicalEntityRole("TENANT_IT_ADMIN"), "the old name is gone (owner answer 4a)");
        assertEquals(Optional.empty(), catalog.canonicalEntityRole("SUPER_ADMIN"));
        assertEquals(Optional.empty(), catalog.canonicalEntityRole(null));
    }

    @Test
    void theProductCodeIsSdv() {
        assertEquals("SDV", SdvRoleCatalog.PRODUCT_CODE);
        assertEquals(List.of("BUSINESS_USER", "DOCUMENT_STEWARD", "RESTRICTED_STEWARD", "BUSINESS_ADMIN", "IT_ADMIN"),
                List.copyOf(catalog.entityRoles()));
    }
}
