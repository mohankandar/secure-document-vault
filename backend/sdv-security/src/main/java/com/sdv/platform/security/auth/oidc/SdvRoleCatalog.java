package com.sdv.platform.security.auth.oidc;

import com.sdv.platform.application.json.VeroJson;
import com.documentplatform.identity.admission.EntityRoleCatalog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.sdv.platform.security.SdvRole;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Task 0041: Vero's roles for platform-core identity, read from the {@code product-roles.json} that the setup command
 * also reads ({@link #RESOURCE}, format {@code platform-product-roles/1}). One list of roles: {@link SdvRole} is the
 * Java side of the same list, and a test keeps the two equal.
 * <p>
 * {@code PLATFORM_OPERATIONS_ADMIN} is a platform role: it can never come from an entity's group grant (platform-core
 * refuses such a grant). Task 0057: setup's platform-level grants (the role mapping's * rows) give it for the whole
 * customer; {@link PlatformLevelGrants} applies them at sign-in.
 */
public final class SdvRoleCatalog implements EntityRoleCatalog {
    /** The classpath location of Vero's product-roles file. */
    public static final String RESOURCE = "setup/product-roles.json";
    /** Vero's product code in the idn_ tables (owner answer 3). */
    public static final String PRODUCT_CODE = com.sdv.platform.domain.identity.VeroProduct.CODE;

    private final Set<String> roles;
    private final Set<String> platformRoles;

    private SdvRoleCatalog(Set<String> roles, Set<String> platformRoles) {
        this.roles = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(roles));
        this.platformRoles = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(platformRoles));
    }

    /** The catalog of the product-roles file shipped with this release. */
    public static SdvRoleCatalog shipped() {
        try (InputStream in = SdvRoleCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("The product-roles file " + RESOURCE + " is missing from the package.");
            return parse(VeroJson.mapper().readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("The product-roles file " + RESOURCE + " could not be read.", e);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("The product-roles file " + RESOURCE + " could not be read.", e);
        }
    }

    static SdvRoleCatalog parse(JsonNode file) {
        if (!"platform-product-roles/1".equals(file.path("format").asString(""))) {
            throw new IllegalStateException("The product-roles file is not platform-product-roles/1.");
        }
        if (!PRODUCT_CODE.equals(file.path("productCode").asString(""))) {
            throw new IllegalStateException("The product-roles file is not for product " + PRODUCT_CODE + ".");
        }
        return new SdvRoleCatalog(names(file.path("roles")), names(file.path("platformRoles")));
    }

    private static Set<String> names(JsonNode list) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode n : list) out.add(VeroJson.text(n));
        return out;
    }

    /** Entity roles first, then platform roles, as the file lists them. */
    public List<String> allRoles() {
        List<String> all = new ArrayList<>(roles);
        all.addAll(platformRoles);
        return all;
    }

    public Set<String> entityRoles() {
        return roles;
    }

    public Set<String> platformRoles() {
        return platformRoles;
    }

    @Override
    public Optional<String> canonicalEntityRole(String configuredName) {
        if (configuredName == null) return Optional.empty();
        for (String role : allRoles()) {
            if (role.equalsIgnoreCase(configuredName.trim())) return Optional.of(role);
        }
        return Optional.empty();
    }

    @Override
    public boolean isPlatformRole(String canonicalRole) {
        return platformRoles.contains(canonicalRole);
    }

    /** The Java role for a canonical role name of this catalog. */
    public static SdvRole role(String canonical) {
        return SdvRole.valueOf(canonical);
    }
}
