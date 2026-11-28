package com.sdv.platform.web.notifications;

import com.sdv.platform.domain.error.StartupFailure;
import com.sdv.platform.domain.error.SdvErrorCode;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

/**
 * Task 0100 (VSEC-204): the address people open Vero on, from the install configuration only ({@code sdv.public-url},
 * {@code SDV_PUBLIC_URL}). Every message links under it; no screen can change it. It must be https, with a host, no
 * user, query or fragment; plain http only in a local start. A wrong value stops the start (SDV-SYS-005). When it is not
 * set, a local start uses {@value #LOCAL_DEFAULT}; any other start sends no messages until it is set (the IT screen says so).
 */
public record PublicUrl(Optional<URI> address) {
    public static final String LOCAL_DEFAULT = "http://localhost:5183";
    static final String INVALID = "sdv.public-url (SDV_PUBLIC_URL) must be the https address people open Vero on, such as "
            + "https://vero.example.com, without a user, query or #.";

    public PublicUrl {
        address = address == null ? Optional.empty() : address;
    }

    public static PublicUrl of(String value, boolean local) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) v = local ? LOCAL_DEFAULT : "";
        if (v.isEmpty()) return new PublicUrl(Optional.empty());
        URI uri;
        try {
            uri = URI.create(v);
        } catch (IllegalArgumentException ex) {
            throw new StartupFailure(SdvErrorCode.SYS_005, INVALID);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean schemeOk = "https".equals(scheme) || (local && "http".equals(scheme));
        if (!schemeOk || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || v.length() > 300) {
            throw new StartupFailure(SdvErrorCode.SYS_005, INVALID);
        }
        String text = uri.toString();
        return new PublicUrl(Optional.of(URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text)));
    }
}
