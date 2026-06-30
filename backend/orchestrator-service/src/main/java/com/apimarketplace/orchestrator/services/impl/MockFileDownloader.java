package com.apimarketplace.orchestrator.services.impl;

import com.apimarketplace.orchestrator.services.file.FileDownloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Mock implementation of FileDownloader for E2E testing.
 * Returns deterministic dummy content without making real HTTP requests.
 */
@Service
@Primary
@ConditionalOnProperty(name = "orchestrator.mock.enabled", havingValue = "true")
public class MockFileDownloader implements FileDownloader {

    private static final Logger logger = LoggerFactory.getLogger(MockFileDownloader.class);

    @Override
    public byte[] download(String url) throws FileDownloadException {
        return download(url, Duration.ofSeconds(30));
    }

    @Override
    public byte[] download(String url, Duration timeout) throws FileDownloadException {
        if (url == null || url.isBlank()) {
            throw new FileDownloadException("URL is required");
        }

        logger.info("MockFileDownloader: Returning mock content for URL: {}", url);

        // Return mock file content that includes the URL for traceability
        String mockContent = "Mock file content downloaded from: " + url;
        return mockContent.getBytes(StandardCharsets.UTF_8);
    }
}
