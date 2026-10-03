package com.apimarketplace.catalog.service.http;

import com.apimarketplace.common.web.OutboundHttpTransport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import reactor.netty.http.client.HttpClient;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regression review 2026-09-29: moving catalog tool calls from HttpURLConnection to Reactor Netty
 * (LC-073 address pinning) silently took Netty's defaults, which refuse or break traffic the old
 * transport carried: response headers above 8 KB, and idle connections kept until the server had
 * dropped them. These run the tuned client (the pinned one minus the pinning, which a loopback
 * server cannot pass) against a real local server.
 */
@DisplayName("OutboundHttpClients - the pinned transport carries what HttpURLConnection carried")
class OutboundHttpClientsTransportLimitsTest {

    private HttpServer server;
    private ExecutorService serverThreads;
    /** Remote port of the connection each request arrived on: a new port is a new connection. */
    private final List<Integer> connectionPorts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        server.createContext("/big-headers", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "session=" + "a".repeat(20 * 1024));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/huge-headers", exchange -> {
            for (int i = 0; i < 30; i++) {
                exchange.getResponseHeaders().add("X-Policy-" + i, "p".repeat(10 * 1024));
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/ping", exchange -> {
            connectionPorts.add(exchange.getRemoteAddress().getPort());
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        serverThreads = Executors.newFixedThreadPool(4);
        server.setExecutor(serverThreads);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        serverThreads.shutdownNow();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    private static RestTemplate over(HttpClient client) {
        ReactorClientHttpRequestFactory factory = new ReactorClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(30));
        return new RestTemplate(factory);
    }

    @Test
    @DisplayName("regression: a 20 KB response header (a large cookie) is accepted")
    void largeResponseHeaderIsAccepted() {
        ResponseEntity<String> response = over(OutboundHttpClients.tunedHttpClient())
                .getForEntity(uri("/big-headers"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("a 300 KB header block is accepted too: HttpURLConnection's limit on the shipped JRE is 384 KB")
    void headerBlockUpToTheOldTransportsLimitIsAccepted() {
        ResponseEntity<String> response = over(OutboundHttpClients.tunedHttpClient())
                .getForEntity(uri("/huge-headers"), String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("control: Netty's default client refuses the 20 KB header, so the tests above can fail")
    void controlDefaultClientRefusesLargeHeader() {
        assertThatThrownBy(() -> over(HttpClient.create()).getForEntity(uri("/big-headers"), String.class))
                .hasStackTraceContaining("8192");
    }

    @Test
    @DisplayName("the PINNED client production uses carries the same header limit and pool")
    void pinnedClientCarriesTheLimits() {
        HttpClient pinned = OutboundHttpClients.pinnedHttpClient();

        assertThat(pinned.configuration().decoder().maxHeaderSize())
                .isEqualTo(OutboundHttpTransport.MAX_RESPONSE_HEADER_BYTES);
        assertThat(pinned.configuration().connectionProvider()).isSameAs(OutboundHttpClients.POOL);
        assertThat(OutboundHttpClients.POOL)
                .isNotSameAs(HttpClient.create().configuration().connectionProvider());
    }

    @Test
    @DisplayName("regression: a connection idle past MAX_IDLE is not reused; a fresh one is")
    void idleConnectionIsNotReused() throws Exception {
        RestTemplate client = over(OutboundHttpClients.tunedHttpClient());

        client.getForEntity(uri("/ping"), String.class);
        client.getForEntity(uri("/ping"), String.class);
        Thread.sleep(OutboundHttpTransport.MAX_IDLE.toMillis() + 1_000);
        client.getForEntity(uri("/ping"), String.class);

        assertThat(connectionPorts).hasSize(3);
        // Back to back: the same keep-alive connection.
        assertThat(connectionPorts.get(1)).isEqualTo(connectionPorts.get(0));
        // After the idle limit: a new one, never a socket the server may already have dropped.
        assertThat(connectionPorts.get(2)).isNotEqualTo(connectionPorts.get(1));
    }

    /**
     * A server whose keep-alive is shorter than MAX_IDLE (gunicorn's default is 2 s): it answers,
     * keeps the connection 1 s, then closes it. Records the client port of each request.
     */
    private static java.net.ServerSocket shortKeepAliveServer(List<Integer> ports) throws Exception {
        java.net.ServerSocket listener = new java.net.ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(() -> {
            while (!listener.isClosed()) {
                try {
                    java.net.Socket socket = listener.accept();
                    socket.setSoTimeout(1_000);
                    Thread.ofVirtual().start(() -> serveUntilIdle(socket, ports));
                } catch (Exception closed) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        return listener;
    }

    private static void serveUntilIdle(java.net.Socket socket, List<Integer> ports) {
        try (socket) {
            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            java.io.OutputStream out = socket.getOutputStream();
            while (true) {
                String line = in.readLine();
                if (line == null) {
                    return;
                }
                while (line != null && !line.isEmpty()) {
                    line = in.readLine();
                }
                ports.add(socket.getPort());
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        } catch (java.net.SocketTimeoutException idle) {
            // Idle for 1 s: the server drops the connection, as a short keep-alive does.
        } catch (Exception ignored) {
            // The client went away.
        }
    }

    @Test
    @DisplayName("a server whose keep-alive (1 s) is shorter than MAX_IDLE: the dropped connection is not reused, the call succeeds")
    void shortServerKeepAliveDoesNotFailTheNextCall() throws Exception {
        List<Integer> ports = new CopyOnWriteArrayList<>();
        try (java.net.ServerSocket listener = shortKeepAliveServer(ports)) {
            RestTemplate client = over(OutboundHttpClients.tunedHttpClient());
            URI target = URI.create("http://127.0.0.1:" + listener.getLocalPort() + "/");

            assertThat(client.getForEntity(target, String.class).getBody()).isEqualTo("ok");
            // Past the server's keep-alive, well within MAX_IDLE: the pooled socket was closed by
            // the server (FIN), so the pool drops it instead of sending on it.
            Thread.sleep(2_000);
            assertThat(client.getForEntity(target, String.class).getBody()).isEqualTo("ok");

            assertThat(ports).hasSize(2);
            assertThat(ports.get(1)).isNotEqualTo(ports.get(0));
        }
    }
}
