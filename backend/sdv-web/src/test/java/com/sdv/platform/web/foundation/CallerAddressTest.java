package com.sdv.platform.web.foundation;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

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
        for (String bad : new String[]{"proxy.example.com", "10.0.0.5:8080", "[::1]", "10.0.0.256", "localhost", "abc"}) {
            StartupFailure failure = assertThrows(StartupFailure.class, () -> new CallerAddress("10.0.0.5, " + bad), bad);
            assertTrue(failure.getMessage().contains("SDV_TRUSTED_PROXIES"), failure.getMessage());
        }
        assertEquals(3, new CallerAddress(" 10.0.0.5 10.0.0.6,::1 ").trustedProxies().size(), "commas or spaces");
    }

    // Task 0040 (owner answer 2 to report 0039 part 2): ranges (CIDR, IPv4 and IPv6), mixed with single addresses.

    @Test
    void anAddressInsideATrustedRangeIsAProxy() {
        CallerAddress callers = new CallerAddress("10.0.4.0/24, 192.0.2.10, 2001:db8:4::/64");
        assertEquals(List.of("10.0.4.0/24", "192.0.2.10", "2001:db8:4:0:0:0:0:0/64"), callers.trustedProxies());
        assertEquals("198.51.100.7", callers.of(from("10.0.4.0", "198.51.100.7")), "first address of the range");
        assertEquals("198.51.100.7", callers.of(from("10.0.4.255", "198.51.100.7")), "last address of the range");
        assertEquals("198.51.100.7", callers.of(from("192.0.2.10", "198.51.100.7")), "single address still works");
        assertEquals("2001:db8:0:0:0:0:0:7", callers.of(from("2001:db8:4::abcd", "2001:db8::7")), "IPv6 range");
        // Hops inside the range are skipped too.
        assertEquals("198.51.100.7", callers.of(from("10.0.4.9", "198.51.100.7, 10.0.4.17, 10.0.4.200")));
        // A prefix that doesn't end on a byte.
        CallerAddress odd = new CallerAddress("172.16.0.0/12");
        assertEquals("198.51.100.7", odd.of(from("172.31.255.254", "198.51.100.7")));
        assertEquals("172.32.0.1", odd.of(from("172.32.0.1", "198.51.100.7")), "just past the range");
        // A whole address as a range: /32 and /128.
        assertEquals("198.51.100.7", new CallerAddress("10.0.0.5/32").of(from("10.0.0.5", "198.51.100.7")));
        assertEquals("198.51.100.7", new CallerAddress("::1/128").of(from("0:0:0:0:0:0:0:1", "198.51.100.7")));
    }

    @Test
    void anAddressOutsideEveryTrustedRangeKeepsItsOwnAddress() {
        CallerAddress callers = new CallerAddress("10.0.4.0/24, 2001:db8:4::/64");
        assertEquals("10.0.5.1", callers.of(from("10.0.5.1", "198.51.100.7")), "next block");
        assertEquals("10.0.3.255", callers.of(from("10.0.3.255", "198.51.100.7")), "block before");
        assertEquals("2001:db8:5::1", callers.of(from("2001:db8:5::1", "198.51.100.7")), "other IPv6 block");
        // An IPv4 address never matches an IPv6 range.
        assertEquals("10.0.4.1", new CallerAddress("::/1").of(from("10.0.4.1", "198.51.100.7")));
        // From a trusted proxy, the first hop outside the range is the caller, even when it is close by.
        assertEquals("10.0.5.3", callers.of(from("10.0.4.9", "198.51.100.7, 10.0.5.3, 10.0.4.17")));
    }

    @Test
    void aBadRangeStopsTheStart() {
        for (String bad : new String[]{"10.0.4.0/33", "2001:db8::/129", "10.0.4.0/", "/24", "10.0.4.0/-1",
                "10.0.4.0/24/8", "10.0.4.0/a", "10.0.4.0/024", "10.0.4.5/24", "2001:db8::1/64", "0.0.0.0/0", "::/0",
                "proxy.example.com/24", "10.0.4.0:80/24", "[2001:db8::]/64", "::ffff:10.0.4.0/120", "10.0.4/24"}) {
            StartupFailure failure = assertThrows(StartupFailure.class,
                    () -> new CallerAddress("10.0.0.5, " + bad), bad);
            assertTrue(failure.getMessage().contains("SDV_TRUSTED_PROXIES"), failure.getMessage());
            assertEquals(SdvErrorCode.SYS_005, failure.errorCode(), bad);
        }
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
