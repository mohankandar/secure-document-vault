package com.sdv.platform.web.monitoring;

import com.documentplatform.eraselog.EraseGuard;
import com.documentplatform.monitoring.Verdict;

/** For tests of other packages: what Health shows about the erase log. */
public final class EraseLogVerdict {
    private EraseLogVerdict() { }

    public static Verdict of(EraseGuard.State state) {
        return VeroMonitoringConfiguration.eraseLog(state);
    }
}
