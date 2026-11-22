package com.sdv.platform.web.foundation;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 0039 part 2 (owner answer 3 to report 0039): the caller's network address, as Vero records it (for now only
 * for unidentified intake attempts in Problems).
 * <p>
 * {@code X-Forwarded-For} is trusted <b>only</b> when the request reached Vero from one of the proxy addresses in
 * {@code sdv.trusted-proxies} ({@code SDV_TRUSTED_PROXIES}): IP addresses, separated by commas or spaces. Empty by
 * default, which trusts no proxy: the address is then always the one Vero's server sees. An entry that isn't an IP
 * address (a host name, a range, anything else) stops the start (SDV-SYS-005); host names are never looked up.
 * <p>
 * From a trusted proxy the header is read from the right: trusted proxies are skipped, and the first address that
 * isn't one of them is the caller. A value that isn't an IP address ends the walk, and the last trusted hop is used
 * (fail closed: a value the caller could have written is never recorded as their address).
 */
@Component
public class CallerAddress {
    /** At most this many header entries are read; a longer chain keeps the last trusted hop. */
    static final int MAX_HOPS = 20;
    private static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern IPV4_PORT = Pattern.compile("^([0-9.]+):\\d{1,5}$");
    private static final Pattern BRACKETED_IPV6 = Pattern.compile("^\\[([0-9A-Fa-f:.]+)](:\\d{1,5})?$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+$");

    /** Trusts no proxy. */
    public static final CallerAddress DIRECT = new CallerAddress(Set.of());

    private final Set<InetAddress> trusted;

    @Autowired
    public CallerAddress(@Value("${sdv.trusted-proxies:}") String trustedProxies) {
        this(parse(trustedProxies));
    }

    private CallerAddress(Set<InetAddress> trusted) {
        this.trusted = Collections.unmodifiableSet(trusted);
    }

    /** The configured proxies, for tests and the start log. */
    public Set<InetAddress> trustedProxies() { return trusted; }

    /** The caller's address: the server's view, or from X-Forwarded-For when that came from a trusted proxy. */
    public String of(HttpServletRequest request) {
        if (request == null) return null;
        String remote = request.getRemoteAddr();
        InetAddress hop = literal(remote);
        if (hop == null || trusted.isEmpty() || !trusted.contains(hop)) return remote;
        List<String> entries = forwardedEntries(request);
        int read = 0;
        for (int i = entries.size() - 1; i >= 0 && read < MAX_HOPS; i--, read++) {
            InetAddress next = literal(entries.get(i));
            if (next == null) return hop.getHostAddress(); // not an address: keep the last trusted hop
            if (!trusted.contains(next)) return next.getHostAddress();
            hop = next;
        }
        return hop.getHostAddress(); // every hop is a trusted proxy, or the chain is too long
    }

    private static List<String> forwardedEntries(HttpServletRequest request) {
        List<String> entries = new ArrayList<>();
        Enumeration<String> values = request.getHeaders("X-Forwarded-For");
        if (values == null) return entries;
        while (values.hasMoreElements()) {
            String value = values.nextElement();
            if (value == null) continue;
            for (String part : value.split(",")) entries.add(part.trim());
        }
        return entries;
    }

    static Set<InetAddress> parse(String setting) {
        Set<InetAddress> out = new LinkedHashSet<>();
        if (setting == null || setting.isBlank()) return out;
        for (String entry : setting.trim().split("[,\\s]+")) {
            if (entry.isEmpty()) continue;
            InetAddress address = literal(entry);
            if (address == null || entry.contains("[") || IPV4_PORT.matcher(entry).matches()) {
                throw new StartupFailure(SdvErrorCode.SYS_005, "sdv.trusted-proxies (SDV_TRUSTED_PROXIES) must list IP"
                        + " addresses separated by commas; \"" + printable(entry) + "\" is not one (no host names,"
                        + " ranges or ports).");
            }
            out.add(address);
        }
        return out;
    }

    /**
     * An IP address written as one (IPv4 dotted, IPv6, IPv6 in brackets, either with a port), or null. Never a name
     * lookup: only strings made of address characters reach {@link InetAddress#getByName}, and an IPv4 form must match
     * exactly, so nothing is ever resolved through DNS.
     */
    static InetAddress literal(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.isEmpty() || v.length() > 64) return null;
        Matcher bracketed = BRACKETED_IPV6.matcher(v);
        if (bracketed.matches()) v = bracketed.group(1);
        else {
            Matcher withPort = IPV4_PORT.matcher(v);
            if (withPort.matches()) v = withPort.group(1);
        }
        boolean v4 = IPV4.matcher(v).matches();
        boolean v6 = !v4 && v.indexOf(':') >= 0 && IPV6.matcher(v).matches();
        if (!v4 && !v6) return null;
        try {
            return InetAddress.getByName(v);
        } catch (Exception e) {
            return null;
        }
    }

    private static String printable(String value) {
        String v = value.codePoints().filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return v.length() > 64 ? v.substring(0, 64) : v;
    }
}
