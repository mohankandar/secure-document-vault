package com.sdv.platform.web.licensing;

import com.documentplatform.licensing.LicenceService;
import com.sdv.platform.domain.error.SdvErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.function.BooleanSupplier;

/**
 * Task 0075: the one place where the licence stops something: intake ({@link LicensedIntake}), after sign-in and before
 * anything of the request is read. A licence that can't be checked stops it too. Nothing else asks the licence.
 */
public final class LicenceGate implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(LicenceGate.class);
    private final BooleanSupplier intakeAllowed;

    public LicenceGate(LicenceService licence) {
        this(licence::intakeAllowed);
    }

    public LicenceGate(BooleanSupplier intakeAllowed) {
        this.intakeAllowed = intakeAllowed;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(LicensedIntake.class)) return true;
        boolean allowed;
        try {
            allowed = intakeAllowed.getAsBoolean();
        } catch (com.documentplatform.licensing.LicenceException ex) {
            log.error("code={} The stored licence can't be checked: {}", SdvErrorCode.LIC_002.code(), ex.code());
            throw new IntakeStopped(SdvErrorCode.LIC_002, IntakeStopped.UNKNOWN);
        }
        if (!allowed) throw new IntakeStopped(SdvErrorCode.LIC_001, IntakeStopped.ENDED);
        return true;
    }
}
