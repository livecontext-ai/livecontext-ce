package com.apimarketplace.monolith.security;

import java.net.InetAddress;
import java.net.URI;
import java.util.regex.Pattern;

/**
 * Decides whether an outbound URI addresses THIS monolith over loopback (LC-032).
 *
 * <p>The in-process secret is only useful while it stays inside this JVM, so the stamping side
 * has to be at least as careful as the checking side. A CE container routinely talks to other
 * things on its own loopback interface - MinIO on 9000, the CLI bridge on 8093, Redis, a local
 * model server - and stamping the secret on any of those hands this JVM's internal-trust marker
 * to a separate process. Matching the host is therefore not enough: the PORT has to be this
 * application's own.
 *
 * <p>Host matching never resolves a DNS name. Two reasons: an outbound call to a provider API
 * would otherwise pay a lookup on every request, and a name that resolves to a loopback address
 * is not evidence of anything (the address is chosen by whoever controls the name). Only
 * {@code localhost} and IP literals are considered, which is exactly the shape the monolith's own
 * {@code services.*-url} configuration uses.
 */
public final class InProcessCallTarget {

    /**
     * IPv4/IPv6 literal shapes. Anything else is a name, and names are never resolved here.
     *
     * <p>The IPv6 branch requires a colon rather than accepting any hex-looking string, because
     * without it a perfectly ordinary hostname made of hex letters ({@code cafe}, {@code deadbeef})
     * would fall through to a DNS lookup on an outbound path that must not do one.
     */
    private static final Pattern IP_LITERAL =
            Pattern.compile("^(?:[0-9]{1,3}(?:\\.[0-9]{1,3}){3}|[0-9a-fA-F.:]*:[0-9a-fA-F.:]*)$");

    private final int configuredPort;
    private volatile int actualPort;

    /**
     * @param configuredPort the port from {@code server.port}, used until the web server reports
     *                       the one it actually bound (they differ only when the port is 0, which
     *                       CE never configures but tests do)
     */
    public InProcessCallTarget(int configuredPort) {
        this.configuredPort = configuredPort;
        this.actualPort = configuredPort;
    }

    /** Records the port the embedded web server actually bound. */
    public void bindPort(int boundPort) {
        if (boundPort > 0) {
            this.actualPort = boundPort;
        }
    }

    /** The port an in-process call must be addressed to for the secret to be stamped. */
    public int port() {
        return actualPort > 0 ? actualPort : configuredPort;
    }

    /**
     * True when {@code uri} is an http(s) call to this application's own loopback address and
     * port, i.e. the monolith calling itself.
     */
    public boolean isSelf(URI uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        boolean https = "https".equalsIgnoreCase(scheme);
        if (!https && !"http".equalsIgnoreCase(scheme)) {
            return false;
        }
        if (!isLoopbackHost(uri.getHost())) {
            return false;
        }
        int uriPort = uri.getPort() != -1 ? uri.getPort() : (https ? 443 : 80);
        return uriPort == port();
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String bare = host;
        if (bare.startsWith("[") && bare.endsWith("]") && bare.length() > 2) {
            bare = bare.substring(1, bare.length() - 1);
        }
        if ("localhost".equalsIgnoreCase(bare)) {
            return true;
        }
        if (!IP_LITERAL.matcher(bare).matches()) {
            return false;
        }
        try {
            return InetAddress.getByName(bare).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
