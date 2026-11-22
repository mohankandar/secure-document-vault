package com.sdv.platform.web.foundation;

import com.sdv.platform.domain.error.StartupFailure;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0039 part 2 (owner answer 3 to report 0039): X-Forwarded-For is trusted only from a configured proxy address
 * (sdv.trusted-proxies, SDV_TRUSTED_PROXIES); empty by default, which trusts none.
 */
class CallerAddressTest {
    private static MockHttpServletRequest from(String remote, String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ingestion/submissions");
        request.setRemoteAddr(remote);
        for (String value : forwardedFor) request.addHeader("X-Forwarded-For", value);
        return request;
    }

    @Test
    void byDefaultNoProxyIsTrustedAndTheHeaderIsIgnored() {
        CallerAddress callers = new CallerAddress("");
        assertTrue(callers.trustedProxies().isEmpty());
        assertEquals("10.0.0.5", callers.of(from("10.0.0.5", "198.51.100.7")));
        assertEquals("10.0.0.5", CallerAddress.DIRECT.of(from("10.0.0.5", "198.51.100.7")));
        assertEquals("10.0.0.5", new CallerAddress(null).of(from("10.0.0.5", "198.51.100.7")));
    }

    @Test
    void fromATrustedProxyTheCallerIsTheFirstAddressFromTheRightThatIsNotAProxy() {
        CallerAddress callers = new CallerAddress("10.0.0.5, 10.0.0.6");
        assertEquals("198.51.100.7", callers.of(from("10.0.0.5", "198.51.100.7")));
        // Two proxies in a row; whatever the caller put in front is not believed.
        assertEquals("198.51.100.7", callers.of(from("10.0.0.5", "1.2.3.4, 198.51.100.7, 10.0.0.6")));
        // The header sent more than once counts as one list.
        assertEquals("198.51.100.7", callers.of(from("10.0.0.5", "1.2.3.4", "198.51.100.7")));
        // IPv6, with brackets and a port, and IPv4 with a port.
        assertEquals("2001:db8:0:0:0:0:0:7", callers.of(from("10.0.0.5", "[2001:db8::7]:4711")));
        assertEquals("198.51.100.7", callers.of(from("10.0.0.5", "198.51.100.7:4711")));
    }

    @Test
    void aRequestThatDidNotComeFromATrustedProxyKeepsItsOwnAddress() {
        CallerAddress callers = new CallerAddress("10.0.0.5");
        assertEquals("203.0.113.9", callers.of(from("203.0.113.9", "198.51.100.7")), "a caller can't name itself");
    }

    @Test
    void aValueThatIsNotAnAddressIsNeverRecordedAndTheLastTrustedHopIsKept() {
        CallerAddress callers = new CallerAddress("10.0.0.5,10.0.0.6");
        assertEquals("10.0.0.6", callers.of(from("10.0.0.5", "198.51.100.7, evil.example, 10.0.0.6")));
        assertEquals("10.0.0.5", callers.of(from("10.0.0.5", "<script>")));
        assertEquals("10.0.0.5", callers.of(from("10.0.0.5", "unknown")));
        assertEquals("10.0.0.5", callers.of(from("10.0.0.5")), "no header: the proxy's address");
        assertEquals("10.0.0.5", callers.of(from("10.0.0.5", "")));
        // Only proxies in the header: the last one.
        assertEquals("10.0.0.6", callers.of(from("10.0.0.5", "10.0.0.6")));
        // A very long chain of proxies stops after 20 hops.
        assertEquals("10.0.0.6", callers.of(from("10.0.0.5", "198.51.100.7, " + "10.0.0.6, ".repeat(30) + "10.0.0.6")));
    }

    @Test
    void anEntryThatIsNotAnIpAddressStopsTheStart() {
        for (String bad : new String[]{"proxy.example.com", "10.0.0.0/8", "10.0.0.5:8080", "[::1]", "10.0.0.256", "localhost", "abc"}) {
            StartupFailure failure = assertThrows(StartupFailure.class, () -> new CallerAddress("10.0.0.5, " + bad), bad);
            assertTrue(failure.getMessage().contains("SDV_TRUSTED_PROXIES"), failure.getMessage());
        }
        assertEquals(3, new CallerAddress(" 10.0.0.5 10.0.0.6,::1 ").trustedProxies().size(), "commas or spaces");
    }

    @Test
    void namesAreNeverLookedUp() {
        assertNull(CallerAddress.literal("localhost"));
        assertNull(CallerAddress.literal("example.com"));
        assertNull(CallerAddress.literal("deadbeef"));
        assertNotNull(CallerAddress.literal("::1"));
        assertNotNull(CallerAddress.literal("127.0.0.1"));
    }
}
