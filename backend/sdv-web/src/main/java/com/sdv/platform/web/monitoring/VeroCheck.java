package com.sdv.platform.web.monitoring;

import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.Verdict;

import java.time.Duration;
import java.util.concurrent.Callable;

/** One of Vero's checks. Never required at the start: Vero stays up and says what is wrong. */
record VeroCheck(String name, String group, Duration every, Callable<Verdict> what) implements DependencyCheck {
    @Override public boolean requiredAtStartup() { return false; }
    @Override public Verdict check() throws Exception { return what.call(); }
}
