package com.sdv.platform.web;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0058: the HTTP client Vero runs with is at least the fixed release (CVE-2026-64607, CVE-2026-54399,
 * CVE-2026-54428): httpclient5 5.6.5, httpcore5 and httpcore5-h2 5.4.4, as platform-core 0.6.1 asks. The override is in
 * {@code backend/pom.xml}; this fails if it is dropped while Spring Boot still pins older versions, or if a second copy
 * of a jar lands on the class path.
 */
class HttpComponentsVersionTest {

    @Test
    void theHttpClientIsAtLeastTheFixedRelease() throws Exception {
        assertAtLeast("org.apache.httpcomponents.client5", "httpclient5", "5.6.5");
        assertAtLeast("org.apache.httpcomponents.core5", "httpcore5", "5.4.4");
        assertAtLeast("org.apache.httpcomponents.core5", "httpcore5-h2", "5.4.4");
    }

    static void assertAtLeast(String group, String artifact, String floor) throws Exception {
        String path = "META-INF/maven/" + group + "/" + artifact + "/pom.properties";
        List<URL> copies = Collections.list(HttpComponentsVersionTest.class.getClassLoader().getResources(path));
        assertEquals(1, copies.size(), artifact + " on the class path: " + copies);
        Properties p = new Properties();
        try (InputStream in = copies.get(0).openStream()) {
            p.load(in);
        }
        String version = p.getProperty("version");
        assertTrue(compare(version, floor) >= 0, artifact + " is " + version + ", the fixed release is " + floor);
    }

    /** Compares dotted release numbers (5.6.5 against 5.10.0, numerically); anything after a '-' is ignored. */
    static int compare(String a, String b) {
        String[] x = a.split("-")[0].split("\\."), y = b.split("-")[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int l = i < x.length ? Integer.parseInt(x[i]) : 0, r = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (l != r) return Integer.compare(l, r);
        }
        return 0;
    }
}
