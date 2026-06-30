package com.apimarketplace.orchestrator.services.file;

import com.apimarketplace.orchestrator.services.file.FileDownloader.FileDownloadException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WebClientFileDownloader")
class WebClientFileDownloaderTest {

    private HttpServer server;
    private AtomicInteger targetHits;
    private WebClientFileDownloader downloader;

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
        downloader = new WebClientFileDownloader(WebClient.builder());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("should reject redirects without following them")
    void shouldRejectRedirectsWithoutFollowingThem() {
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect";

        assertThatThrownBy(() -> downloader.download(url, Duration.ofSeconds(5)))
                .isInstanceOf(FileDownloadException.class)
                .hasMessageContaining("302");
        assertThat(targetHits).hasValue(0);
    }
}
