package com.sdv.platform.web.startup;

import com.sdv.platform.domain.error.StartupFailure;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Task 0037: a start that stops because of a {@link StartupFailure} is reported as one ERROR block, "APPLICATION
 * FAILED TO START", with the code, the catalog's sentence, the exact cause and what to do, and no stack trace.
 * Registered in META-INF/spring.factories.
 */
public class StartupFailureAnalyzer extends AbstractFailureAnalyzer<StartupFailure> {
    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, StartupFailure cause) {
        return new FailureAnalysis(description(cause), cause.action(), cause);
    }

    static String description(StartupFailure f) {
        return f.errorCode().code() + " " + f.sentence() + " Cause: " + f.detail();
    }
}
