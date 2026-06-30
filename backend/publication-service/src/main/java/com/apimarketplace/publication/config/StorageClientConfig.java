package com.apimarketplace.publication.config;

import com.apimarketplace.storage.client.StorageClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HTTP client to storage-service. Used by {@code ShowcaseSnapshotReader}
 * to mint short-lived presigned download URLs for FileRefs embedded in
 * a publication's frozen showcase snapshot. Without this rewrite anonymous
 * marketplace visitors would see {@code <img src='{"_type":"file",...}'>} in
 * the iframe (raw JSON-stringified FileRef) and the publisher's tenant-
 * scoped {@code /api/files/proxy} endpoint would 401 them anyway.
 */
@Configuration
public class StorageClientConfig {

    @Bean
    public StorageClient storageClient(
            @Value("${services.storage-url:http://localhost:8082}") String storageServiceUrl) {
        return new StorageClient(storageServiceUrl);
    }
}
