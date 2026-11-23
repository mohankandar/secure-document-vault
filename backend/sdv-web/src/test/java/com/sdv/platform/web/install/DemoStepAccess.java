package com.sdv.platform.web.install;

import com.documentplatform.licensing.TrustedKeys;
import com.documentplatform.licensing.usage.UsageLedger;
import com.sdv.platform.web.security.PlatformIdentityConfigurationAccess;

import javax.sql.DataSource;
import java.io.PrintStream;
import java.io.OutputStream;
import java.time.Clock;
import java.util.Map;

/** Test access to the demo step's package-private apply. */
public final class DemoStepAccess {
    private DemoStepAccess() { }

    public static int apply(DataSource ds, TrustedKeys keys, Map<String, String> slots) throws Exception {
        PrintStream quiet = new PrintStream(OutputStream.nullOutputStream());
        return new DemoStep(quiet, quiet, Clock.systemUTC()).apply(ds, keys, slots);
    }

    public static UsageLedger noUsage() {
        return PlatformIdentityConfigurationAccess.noUsage();
    }
}
