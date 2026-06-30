package com.apimarketplace.auth.config;

import com.apimarketplace.storage.client.StorageClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the storage-service HTTP client used by {@link com.apimarketplace.auth.service.PlanStorageQuotaSyncer}
 * to push plan→quota limit changes through storage-service (the owner of the {@code storage} schema
 * and its read caches).
 *
 * <p>Microservice mode only: in monolith (CE) auth and storage share the JVM, so the syncer calls
 * {@code QuotaService} in-process instead - no client bean, no HTTP. Mirrors the gating on
 * {@code storage-service}'s {@code InternalQuotaController}.
 */
@Configuration
@ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
public class StorageClientConfig {

    @Bean
    public StorageClient storageClient(
            @Value("${services.storage-url:http://localhost:8082}") String storageUrl) {
        return new StorageClient(storageUrl);
    }
}
