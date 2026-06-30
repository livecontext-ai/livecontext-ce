package com.apimarketplace.orchestrator.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

/**
 * Marker configuration for the image-generation tool.
 *
 * <p><b>Opt-in</b> - disabled by default ({@code matchIfMissing=false}).
 * Operators must set {@code image-generation.enabled=true} explicitly.
 *
 * <p><b>HTTP transport delegated to the API catalog</b> - unlike the v1
 * implementation, this config no longer creates a dedicated
 * {@code RestTemplate}. The image providers call OpenAI / Gemini via
 * {@code CatalogToolsGateway} ({@code orchestrator.catalog.enabled=true}
 * is therefore a prerequisite - Spring will fail to wire the providers
 * if the gateway bean is absent). Credentials, retries, response
 * projection, and error mapping all live in the catalog runtime.
 *
 * <p>This class stays as a marker so other beans (e.g. tests, future
 * sub-configs for image storage / S3) can hook off the same opt-in flag.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "image-generation.enabled", havingValue = "true", matchIfMissing = false)
public class ImageGenerationConfig {
    public ImageGenerationConfig() {
        log.info("[ImageGenerationConfig] image-generation enabled - providers will route through CatalogToolsGateway");
    }
}
