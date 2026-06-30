package com.apimarketplace.common.web;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NoRedirectSimpleClientHttpRequestFactory")
class NoRedirectSimpleClientHttpRequestFactoryTest {

    private HttpServer server;
    private AtomicInteger targetHits;

    @BeforeEach
    void setUp() throws Exception {
        targetHits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetHits.incrementAndGet();
            byte[] body = "target".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("should expose redirect response without following it")
    void shouldNotFollowRedirects() {
        RestTemplate restTemplate = new RestTemplate(new NoRedirectSimpleClientHttpRequestFactory());
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect";

        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(302);
        assertThat(response.getHeaders().getLocation()).hasPath("/target");
        assertThat(targetHits).hasValue(0);
    }
}
