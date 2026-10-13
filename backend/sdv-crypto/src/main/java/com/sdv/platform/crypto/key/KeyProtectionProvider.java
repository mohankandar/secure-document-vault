package com.sdv.platform.crypto.key;

import java.io.IOException;
import java.util.UUID;

public interface KeyProtectionProvider {
    String providerId();
    ProtectedDataKey generateDataKey(UUID tenantId) throws IOException;
    byte[] unprotectDataKey(UUID tenantId, String protectedReference) throws IOException;
}
