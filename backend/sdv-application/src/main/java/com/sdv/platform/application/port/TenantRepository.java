package com.sdv.platform.application.port;

import com.sdv.platform.domain.tenant.Tenant;

import java.util.Optional;
import java.util.UUID;

public interface TenantRepository {
    Optional<Tenant> findById(UUID tenantId);
}
