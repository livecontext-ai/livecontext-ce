package com.apimarketplace.orchestrator.config;

import com.apimarketplace.storage.client.StorageClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the Storage HTTP client.
 * Delegates all file storage operations to storage-service via HTTP.
 */
@Configuration
public class StorageClientConfig {

    @Bean
    public StorageClient storageClient(
            @Value("${services.storage-url:http://localhost:8082}") String storageServiceUrl) {
        return new StorageClient(storageServiceUrl);
    }
}
