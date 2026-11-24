package com.sdv.platform.security.auth;

import com.sdv.platform.domain.error.SdvErrorCode;
import com.sdv.platform.domain.error.StartupFailure;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * Task 0089: a local start is refused where a tunnel or a proxy brings callers from outside. In local mode a request's
 * headers say who you are, and a tunnel's calls come from this computer, so the loopback check can't tell them apart.
 * <ul>
 *   <li>a tunnel program runs on this computer (as far as this user may see it);</li>
 *   <li>a proxy is trusted (SDV_TRUSTED_PROXIES);</li>
 *   <li>Vero would listen on more than this computer (server.address).</li>
 * </ul>
 * It runs while the application context is built, so a refused start never opens its port. A tunnel started later is
 * met by {@link LocalHeaderAuthenticationFilter}, which refuses forwarded requests.
 */
@Component
@Profile("local")
public class LocalTunnelCheck {
    private static final List<String> TUNNELS = List.of("cloudflared", "ngrok");
    private static final List<String> THIS_COMPUTER = List.of("127.0.0.1", "localhost", "::1", "[::1]");

    @Autowired
    public LocalTunnelCheck(Environment environment) {
        this(environment.getProperty("sdv.trusted-proxies", environment.getProperty("SDV_TRUSTED_PROXIES", "")),
                environment.getProperty("server.address", ""),
                ProcessHandle.allProcesses().map(p -> p.info().command().orElse("")).toList());
    }

    LocalTunnelCheck(String trustedProxies, String serverAddress, Collection<String> commands) {
        for (String command : commands) {
            String name = program(command);
            if (TUNNELS.contains(name)) throw refused("The tunnel program " + name + " is running. Stop it first.");
        }
        if (trustedProxies != null && !trustedProxies.isBlank()) throw refused("SDV_TRUSTED_PROXIES is set: take it away.");
        String address = serverAddress == null ? "" : serverAddress.trim().toLowerCase(Locale.ROOT);
        if (!THIS_COMPUTER.contains(address)) throw refused("server.address (SERVER_ADDRESS) must be 127.0.0.1.");
    }

    private static String program(String command) {
        String name = command.substring(Math.max(command.lastIndexOf('/'), command.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
        return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
    }

    private static StartupFailure refused(String why) {
        return new StartupFailure(SdvErrorCode.SYS_005, "Local mode is never put behind a tunnel or proxy. " + why);
    }
}
