package com.sdv.platform.application.port;

import java.time.Instant;

public interface KeyServiceHealthProbe {
    Health check();

    enum Status { UP, DOWN, NOT_CONFIGURED }

    record Health(Status status, String providerId, String message, Instant observedAt) { }
}
