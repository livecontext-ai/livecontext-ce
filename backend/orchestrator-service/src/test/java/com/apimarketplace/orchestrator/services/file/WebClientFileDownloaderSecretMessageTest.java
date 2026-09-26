package com.apimarketplace.orchestrator.services.file;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.orchestrator.services.file.FileDownloader.FileDownloadException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Regression, 2026-09-25: a failed download of a presigned url logged the url (signature
 * included) at ERROR and returned it in the error the step and the agent tool report. The
 * message must still say what failed and where, with the credential query values withheld.
 */
@DisplayName("WebClientFileDownloader - a failed download never prints a presigned url's signature")
class WebClientFileDownloaderSecretMessageTest {

    private static final String SIG = "deadbeefcafe0123456789FAKEsignature";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Consumer<String> ALLOW_ALL = u -> { };

    private HttpServer server;
    private WebClientFileDownloader downloader;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // No context: every path answers 404.
        server.start();
        downloader = new WebClientFileDownloader(WebClient.builder(), ALLOW_ALL);
        logger = (Logger) LoggerFactory.getLogger(WebClientFileDownloader.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        server.stop(0);
    }

    private String signed(String base) {
        return base + "/file.pdf?X-Amz-Credential=AKIAFAKE&X-Amz-Signature=" + SIG;
    }

    private void assertLogsClean() {
        logs.list.forEach(e -> assertThat(e.getFormattedMessage()).doesNotContain(SIG));
    }

    @Test
    @DisplayName("An HTTP error status names the url with its signature withheld")
    void statusErrorWithholdsSignature() {
        String url = signed("http://127.0.0.1:" + server.getAddress().getPort());

        FileDownloadException e = catchThrowableOfType(() -> downloader.download(url, TIMEOUT), FileDownloadException.class);

        assertThat(e.getMessage()).contains("404").contains("/file.pdf?").doesNotContain(SIG);
        assertLogsClean();
    }

    @Test
    @DisplayName("A transport failure (connection refused) withholds the signature in the message and the log")
    void transportFailureWithholdsSignature() throws Exception {
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        String url = signed("http://127.0.0.1:" + closedPort);

        FileDownloadException e = catchThrowableOfType(() -> downloader.download(url, TIMEOUT), FileDownloadException.class);

        assertThat(e.getMessage()).doesNotContain(SIG);
        assertLogsClean();
    }
}
