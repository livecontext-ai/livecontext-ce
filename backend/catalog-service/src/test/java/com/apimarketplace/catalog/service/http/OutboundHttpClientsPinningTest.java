package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.service.execution.AsyncPollExecutor;
import com.apimarketplace.catalog.service.execution.StreamingResponseHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LC-073 for the catalog transport (item 5, CASA readiness): the pinned clients connect only to an
 * address the egress policy accepts, judged on the answer the socket is dialled with, not on a
 * separate earlier lookup. A real local server on loopback stands in for "the name rebinds to an
 * internal address": the pre-flight is bypassed entirely here, so only the transport stands
 * between the request and the server.
 */
@DisplayName("OutboundHttpClients - the catalog transport is pinned to vetted addresses")
class OutboundHttpClientsPinningTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("regression: a name resolving to loopback is refused at connect, the server is never reached")
    void hostnameResolvingInternallyIsRefused() {
        RestTemplate pinned = new OutboundHttpClients(2000, 2000, new ObjectMapper()).restTemplate();
        int port = server.getAddress().getPort();

        assertThatThrownBy(() -> pinned.getForEntity(URI.create("http://localhost:" + port + "/"), String.class))
                .as("the plain HttpURLConnection RestTemplate this replaced would have connected");
        assertThat(hits).hasValue(0);
    }

    @Test
    @DisplayName("regression: an IP literal (which no resolver sees) is refused by the channel hook")
    void literalInternalAddressIsRefused() {
        RestTemplate pinned = new OutboundHttpClients(2000, 2000, new ObjectMapper()).restTemplate();
        int port = server.getAddress().getPort();

        assertThatThrownBy(() -> pinned.getForEntity(URI.create("http://127.0.0.1:" + port + "/"), String.class));
        assertThat(hits).hasValue(0);
    }

    @Test
    @DisplayName("control: the same request through an unpinned client reaches the server")
    void controlUnpinnedClientConnects() {
        new RestTemplate().getForEntity(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), String.class);
        assertThat(hits).hasValue(1);
    }

    @Test
    @DisplayName("the pinned RestTemplate runs on Reactor Netty, never HttpURLConnection")
    void pinnedClientIsReactorNetty() {
        assertThat(new OutboundHttpClients(2000, 2000, new ObjectMapper()).restTemplate().getRequestFactory())
                .isInstanceOf(ReactorClientHttpRequestFactory.class);
    }

    @Test
    @DisplayName("the execution, polling and streaming paths take the pinned transport when it is wired")
    void consumersUseThePinnedTransport() throws Exception {
        OutboundHttpClients clients = new OutboundHttpClients(2000, 2000, new ObjectMapper());

        HttpExecutionService exec = new HttpExecutionService(null, null, null, new ObjectMapper(), null,
                new RestTemplate(), new ErrorPolicyEngine());
        exec.setOutboundHttpClients(clients);
        assertThat(exec.transport()).isSameAs(clients.restTemplate());

        AsyncPollExecutor poll = new AsyncPollExecutor(new RestTemplate(), new ObjectMapper());
        java.lang.reflect.Method setPoll = AsyncPollExecutor.class.getDeclaredMethod("setOutboundHttpClients", OutboundHttpClients.class);
        setPoll.setAccessible(true);
        setPoll.invoke(poll, clients);
        java.lang.reflect.Method pollTransport = AsyncPollExecutor.class.getDeclaredMethod("transport");
        pollTransport.setAccessible(true);
        assertThat(pollTransport.invoke(poll)).isSameAs(clients.restTemplate());

        StreamingResponseHandler streaming = new StreamingResponseHandler(null);
        java.lang.reflect.Method setStream = StreamingResponseHandler.class.getDeclaredMethod("setOutboundHttpClients", OutboundHttpClients.class);
        setStream.setAccessible(true);
        setStream.invoke(streaming, clients);
        java.lang.reflect.Method consumer = StreamingResponseHandler.class.getDeclaredMethod("consumer");
        consumer.setAccessible(true);
        assertThat(consumer.invoke(streaming)).isSameAs(clients.sseStreamConsumer());
    }
}
