package com.apimarketplace.catalog.config;

import com.apimarketplace.storage.client.StorageClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Storage HTTP client wiring for catalog-service.
 *
 * <p>Used by {@code BinaryResponseHandler} to upload binary tool outputs
 * (raw-byte uploads when an endpoint declares {@code response.type=binary},
 * AND inline-base64 dehydration for JSON responses carrying large binaries
 * - Gemini image-gen, OpenAI image-gen, ElevenLabs audio, …). Without this
 * bean both code paths silently no-op (the handler's
 * {@code storageClient} field is {@code @Autowired(required=false)}), which
 * is why a 2 MB base64 PNG used to round-trip back to the agent unmodified.
 */
@Configuration
public class StorageClientConfig {

    @Bean
    @ConditionalOnMissingBean
    public StorageClient storageClient(
            @Value("${services.storage-url:http://localhost:8082}") String storageServiceUrl) {
        return new StorageClient(storageServiceUrl);
    }
}
