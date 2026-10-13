package com.sdv.platform.application.port;

import com.sdv.platform.domain.audit.AuditEvent;

public interface AuditEventPort {
    void append(AuditEvent event);
}
