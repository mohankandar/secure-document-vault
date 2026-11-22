package com.sdv.platform.web.foundation;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
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
 * {@code X-Forwarded-For} is trusted <b>only</b> when the request reached Vero from one of the proxies in
 * {@code sdv.trusted-proxies} ({@code SDV_TRUSTED_PROXIES}): IP addresses and, since task 0040 (owner answer 2 to
 * report 0039 part 2), address ranges in CIDR form (IPv4 or IPv6, for example {@code 10.0.4.0/24} or
 * {@code 2001:db8:4::/64}), mixed, separated by commas or spaces. Empty by default, which trusts no proxy: the address
 * is then always the one Vero's server sees.
 * <p>
 * An entry that isn't an IP address or a valid range stops the start (SDV-SYS-005): a host name, a port, brackets, a
 * prefix past the family's size, a prefix of 0 (that would trust every address), a range whose address has bits set
 * past the prefix (likely a typing mistake, so it is refused, not guessed), or an IPv4 address written in IPv6 form
 * with a prefix. Host names are never looked up.
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
    private static final Pattern PREFIX = Pattern.compile("^(0|[1-9]\\d{0,2})$");

    /** Trusts no proxy. */
    public static final CallerAddress DIRECT = new CallerAddress(List.of());

    /** One trusted address (the prefix is every bit of its family) or range. */
    record Range(InetAddress network, int prefix) {
        boolean contains(InetAddress address) {
            byte[] a = address.getAddress();
            byte[] n = network.getAddress();
            if (a.length != n.length) return false; // an IPv4 address never matches an IPv6 range, nor the reverse
            int full = prefix / 8;
            for (int i = 0; i < full; i++) if (a[i] != n[i]) return false;
            int rest = prefix % 8;
            if (rest == 0) return true;
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (a[full] & mask) == (n[full] & mask);
        }

        @Override
        public String toString() {
            int bits = network.getAddress().length * 8;
            return network.getHostAddress() + (prefix == bits ? "" : "/" + prefix);
        }
    }

    private final List<Range> trusted;

    @Autowired
    public CallerAddress(@Value("${sdv.trusted-proxies:}") String trustedProxies) {
        this(parse(trustedProxies));
    }

    private CallerAddress(List<Range> trusted) {
        this.trusted = List.copyOf(trusted);
    }

    /** The configured proxies and ranges, as text, for tests and the start log. */
    public List<String> trustedProxies() {
        return trusted.stream().map(Range::toString).toList();
    }

    private boolean isTrusted(InetAddress address) {
        for (Range range : trusted) if (range.contains(address)) return true;
        return false;
    }

    /** The caller's address: the server's view, or from X-Forwarded-For when that came from a trusted proxy. */
    public String of(HttpServletRequest request) {
        if (request == null) return null;
        String remote = request.getRemoteAddr();
        InetAddress hop = literal(remote);
        if (hop == null || trusted.isEmpty() || !isTrusted(hop)) return remote;
        List<String> entries = forwardedEntries(request);
        int read = 0;
        for (int i = entries.size() - 1; i >= 0 && read < MAX_HOPS; i--, read++) {
            InetAddress next = literal(entries.get(i));
            if (next == null) return hop.getHostAddress(); // not an address: keep the last trusted hop
            if (!isTrusted(next)) return next.getHostAddress();
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

    static List<Range> parse(String setting) {
        Set<Range> out = new LinkedHashSet<>();
        if (setting == null || setting.isBlank()) return List.of();
        for (String entry : setting.trim().split("[,\\s]+")) {
            if (entry.isEmpty()) continue;
            out.add(entry.indexOf('/') >= 0 ? range(entry) : single(entry));
        }
        return new ArrayList<>(out);
    }

    private static Range single(String entry) {
        InetAddress address = literal(entry);
        if (address == null || entry.contains("[") || IPV4_PORT.matcher(entry).matches()) {
            throw badEntry(entry, "is not an IP address or an address range");
        }
        return new Range(address, address.getAddress().length * 8);
    }

    /** {@code address/prefix}: the address written plainly (no brackets, no port), the prefix within its family. */
    private static Range range(String entry) {
        int slash = entry.indexOf('/');
        String addressPart = entry.substring(0, slash);
        String prefixPart = entry.substring(slash + 1);
        InetAddress network = literal(addressPart);
        if (network == null || addressPart.contains("[") || IPV4_PORT.matcher(addressPart).matches()
                || !PREFIX.matcher(prefixPart).matches()) {
            throw badEntry(entry, "is not an IP address or an address range");
        }
        int bits = network.getAddress().length * 8;
        // Java turns an IPv4 address written in IPv6 form (::ffff:a.b.c.d) into IPv4; its prefix would then count
        // the wrong bits, so it is refused rather than guessed.
        if (bits == 32 && addressPart.indexOf(':') >= 0) {
            throw badEntry(entry, "is an IPv4 address in IPv6 form; write the IPv4 range");
        }
        int prefix = Integer.parseInt(prefixPart);
        if (prefix > bits) throw badEntry(entry, "has a prefix larger than " + bits);
        if (prefix == 0) throw badEntry(entry, "would trust every address");
        byte[] a = network.getAddress();
        for (int bit = prefix; bit < bits; bit++) {
            if ((a[bit / 8] & (0x80 >> (bit % 8))) != 0) {
                throw badEntry(entry, "has address bits set past the prefix (write the range's first address)");
            }
        }
        return new Range(network, prefix);
    }

    private static StartupFailure badEntry(String entry, String why) {
        return new StartupFailure(SdvErrorCode.SYS_005, "sdv.trusted-proxies (SDV_TRUSTED_PROXIES) must list IP"
                + " addresses or address ranges (CIDR, for example 10.0.4.0/24) separated by commas; \""
                + printable(entry) + "\" " + why + " (no host names or ports).");
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
