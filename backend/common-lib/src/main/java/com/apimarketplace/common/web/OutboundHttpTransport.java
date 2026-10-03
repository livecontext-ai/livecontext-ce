package com.apimarketplace.common.web;

import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * Transport limits of the Reactor Netty clients that carry user-directed outbound calls (catalog
 * tool executions, the HTTP Request node, the RSS node). Those clients moved off
 * HttpURLConnection / java.net.http so the socket is dialled to the address the SSRF policy vetted
 * ({@link SafeAddressResolverGroup}); left on Netty's defaults they refused traffic the old
 * transports carried. Each call site keeps its own pinning and builds its client from here.
 *
 * <p>Optional dependency: only services that already run Reactor Netty load this class.
 */
public final class OutboundHttpTransport {

    private OutboundHttpTransport() {
    }

    /**
     * Largest response header block accepted: HttpURLConnection's limit on the shipped JRE
     * ({@code jdk.http.maxHeaderSize}, 384 KB since 21.0.4). Netty's default, 8 KB, refused real
     * APIs whose cookies or security-policy headers are larger.
     */
    public static final int MAX_RESPONSE_HEADER_BYTES = 384 * 1024;

    /**
     * A pooled connection unused this long is closed, and never reused. Just under the 5 s
     * keep-alive of the most common servers (Node, uvicorn) and of HttpURLConnection itself: a
     * connection the server has already dropped fails the next request with "Connection
     * prematurely closed BEFORE response", and Netty never retries a request once it is sent.
     */
    public static final Duration MAX_IDLE = Duration.ofSeconds(4);

    /**
     * A connection this old is closed when it is next released (or found idle), so a provider's DNS
     * change is followed. It is never cut while in use: a long download or stream runs to its end.
     */
    public static final Duration MAX_LIFE = Duration.ofMinutes(5);

    /** A per-host pool unused this long is disposed: hosts are user-chosen and otherwise accumulate for good. */
    public static final Duration INACTIVE_POOL_DISPOSAL = Duration.ofMinutes(5);

    /**
     * Connections per remote host: Reactor Netty's global default, so a dedicated pool is not a
     * tighter one. Pending acquisitions keep the builder default (twice this).
     */
    public static final int MAX_CONNECTIONS_PER_HOST = 500;

    /**
     * A pool of its own for one kind of outbound traffic, so it neither shares connections with
     * nor exhausts the global pool every other Netty client of the JVM uses (the whole CE
     * monolith). Build it once per call site and keep it: every pool holds its own timers.
     */
    public static ConnectionProvider pool(String name) {
        return ConnectionProvider.builder(name)
                .maxConnections(MAX_CONNECTIONS_PER_HOST)
                .maxIdleTime(MAX_IDLE)
                .maxLifeTime(MAX_LIFE)
                // Most recently used first: the connection least likely to have been dropped.
                .lifo()
                .evictInBackground(Duration.ofSeconds(30))
                .disposeInactivePoolsInBackground(Duration.ofMinutes(1), INACTIVE_POOL_DISPOSAL)
                .build();
    }

    /** A client on {@code pool} with the header limit above; callers add their pinning and timeouts. */
    public static HttpClient client(ConnectionProvider pool) {
        return HttpClient.create(pool)
                .httpResponseDecoder(spec -> spec.maxHeaderSize(MAX_RESPONSE_HEADER_BYTES));
    }
}
