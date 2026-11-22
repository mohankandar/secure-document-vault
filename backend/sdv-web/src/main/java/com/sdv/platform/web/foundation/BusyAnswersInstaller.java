package com.sdv.platform.web.foundation;

import com.sdv.platform.application.operations.BusyAnswers;
import org.springframework.stereotype.Component;

/**
 * Task 0040 part 2: gives {@link ErrorReplies} (static, used by the exception handler, the error page and filters)
 * Vero's {@link BusyAnswers}: the Retry-After setting and the one count Problems shows.
 */
@Component
public class BusyAnswersInstaller {
    public BusyAnswersInstaller(BusyAnswers busyAnswers) {
        ErrorReplies.use(busyAnswers);
    }
}
