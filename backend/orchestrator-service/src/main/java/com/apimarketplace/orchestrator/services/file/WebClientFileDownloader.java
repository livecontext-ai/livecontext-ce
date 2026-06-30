package com.apimarketplace.orchestrator.services.file;

import com.apimarketplace.orchestrator.utils.file.FileConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * WebClient-based file downloader implementation.
 * Uses Spring's reactive WebClient with connection pooling.
 *
 * Features:
 * - Connection pooling via WebClient.Builder
 * - Configurable buffer size for large files
 * - Timeout handling with descriptive errors
 * - Status code propagation in exceptions
 */
@Service
public class WebClientFileDownloader implements FileDownloader {

    private static final Logger logger = LoggerFactory.getLogger(WebClientFileDownloader.class);

    private final WebClient webClient;

    public WebClientFileDownloader(WebClient.Builder webClientBuilder) {
        HttpClient httpClient = HttpClient.create()
            .followRedirect(false);

        this.webClient = webClientBuilder
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .codecs(configurer -> configurer
                .defaultCodecs()
                .maxInMemorySize((int) FileConstants.MAX_FILE_SIZE_BYTES))
            .build();
    }

    @Override
    public byte[] download(String url) throws FileDownloadException {
        return download(url, FileConstants.DOWNLOAD_TIMEOUT);
    }

    @Override
    public byte[] download(String url, Duration timeout) throws FileDownloadException {
        if (url == null || url.isBlank()) {
            throw new FileDownloadException("URL is required");
        }

        logger.debug("Downloading file from: {}", url);

        try {
            byte[] content = webClient.get()
                .uri(url)
                .retrieve()
                .onStatus(status -> status.isError() || status.is3xxRedirection(), response ->
                    response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(body -> Mono.error(new FileDownloadException(
                            "Download failed with status " + response.statusCode() +
                                (body.isBlank() ? "" : ": " + truncate(body, 200)),
                            response.statusCode().value()
                        )))
                )
                .bodyToMono(byte[].class)
                .timeout(timeout)
                .block();

            if (content == null) {
                throw new FileDownloadException("No content received from URL: " + url);
            }

            logger.debug("Downloaded {} bytes from {}", content.length, url);
            return content;

        } catch (FileDownloadException e) {
            throw e;
        } catch (WebClientResponseException e) {
            logger.error("HTTP error downloading from {}: {}", url, e.getMessage());
            throw new FileDownloadException(
                "HTTP " + e.getStatusCode().value() + " downloading from " + url,
                e.getStatusCode().value(),
                e
            );
        } catch (Exception e) {
            if (e.getCause() instanceof TimeoutException) {
                logger.error("Timeout downloading from {}", url);
                throw new FileDownloadException("Download timeout after " + timeout.toSeconds() + "s", e);
            }
            logger.error("Failed to download from {}: {}", url, e.getMessage());
            throw new FileDownloadException("Download failed: " + e.getMessage(), e);
        }
    }

    private String truncate(String s, int maxLength) {
        if (s == null) return "";
        return s.length() <= maxLength ? s : s.substring(0, maxLength) + "...";
    }
}
